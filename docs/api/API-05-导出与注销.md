# API-05 · 导出与注销

**业务域**：全量导出、留存期限、单条与全量删除、账号注销、复诊回填
**对应后端模块**：`../backend/modules/数据生命周期与合规.md`
**对应前端模块**：`../frontend/modules/引导与账号设置.md`、`../frontend/modules/就医衔接与数据导出.md`

> ⚠️ **本域承载"用户能不能真的删掉自己的数据"。** 改动前必须重读 `../PROJECT_REQUIREMENTS.md` 第 7 章。
>
> **⚠️ 2026-09-28 变更（B3）**：**就诊摘要功能整体移除**（服务端与客户端都不再生成），仅保留 `FULL` / `METRICS_ONLY` 导出。
> 另（决策 #18）：删除/清空的"本人"语义由**老人端会话**承载，注销后老人数据按绑定关系处置。

---

## 1. 接口清单

| # | 方法 | 路径 | 权限 | 幂等 | 限流 |
|---|---|---|---|---|---|
| 5.1 | POST | `/v1/export` | `canAccess` + SENSITIVE 同意 | **需幂等键** | **严格** |
| 5.2 | GET | `/v1/export/{jobId}` | 任务发起者 | — | 中等 |
| 5.3 | GET | `/v1/subjects/{subjectId}/retention` | `canAccess` | — | 宽松 |
| 5.4 | PUT | `/v1/subjects/{subjectId}/retention` | `canWrite` | 天然 | 中等 |
| 5.5 | DELETE | `/v1/records/{recordId}` | `canWrite` | 天然 | 中等 |
| 5.6 | POST | `/v1/subjects/{subjectId}/purge` | **仅老人端会话** | 天然 | 严格 |
| 5.7 | DELETE | `/v1/accounts/me` | 本人 | 天然 | 严格 |
| 5.8 | POST | `/v1/accounts/me/cancel-deletion` | 本人（冷静期内） | 天然 | 中等 |
| 5.9 | POST | `/v1/subjects/{subjectId}/followup` | `canWrite` | 需幂等键 | 中等 |
| 5.10 | GET | `/v1/audit-logs?cursor=&limit=` | 本人（只看涉及自己的） | — | 宽松 |

### 1.1 明确不存在的接口

**以下接口不得存在**，且应有自动化测试断言其 404：

```
❌ GET  /v1/medical/summary        （面向医生的临床摘要）
❌ GET  /v1/medical/risk           （风险分级）
❌ GET  /v1/medical/recommendation （检查建议）
❌ POST /v1/export  scope=SUMMARY  （就诊摘要——B3 后连枚举值都删除）
❌ 任何返回 clinical judgment 的接口
```

**理由**：只要存在这样的接口，就构成"给出临床诊断治疗依据和/或建议的决策支持软件"，落入《医疗器械分类目录》21-04，按第三类医疗器械管理。

**B3 后边界收紧**：就诊摘要**全链路不存在**——服务端不生成、客户端本地也不生成。产品只记录趋势与话语变化，不给判断。

---

## 2. 接口详情

### 5.1 创建全量导出任务

```
POST /v1/export
Idempotency-Key: <uuid>
```

**请求**

```json
{
  "subjectId": "sub_01H...",
  "scope": "FULL",
  "from": 1787000000000,
  "to": 1790000000000,
  "include": ["AUDIO", "METRICS", "EVENTS", "TRANSCRIPT"]
}
```

**`scope` 枚举**

| 值 | 说明 |
|---|---|
| `FULL` | 全量导出（音频 + 指标 + 标注 + 转写） |
| `METRICS_ONLY` | 只导指标 CSV（体积小，便于论文快速取数） |

> ~~`SUMMARY` 就诊摘要~~ **已随 B3 决策移除**，传入返回 400。

**`include` 枚举**：`AUDIO` \| `METRICS` \| `EVENTS` \| `TRANSCRIPT`

**响应** `202`

```json
{ "jobId": "exp_01H...", "state": "PENDING", "estimatedSizeBytes": 15728640 }
```

**错误**：`CONSENT_REQUIRED`、`INSUFFICIENT_DATA`、`EXPORT_TOO_LARGE`、`RATE_LIMITED`

**合规**：

- 导出内容**不含** `zScore`、基线均值、标准差、量表分数
- 产物 24 小时后**必须删除**
- 写审计日志

---

### 5.2 查询导出任务

```
GET /v1/export/{jobId}
```

**响应** `200`

```json
{
  "jobId": "exp_01H...",
  "state": "READY",
  "downloadUrl": "https://<oss-host>/...&Signature=...",
  "expiresAt": 1790003600000,
  "sizeBytes": 15728640,
  "missingAudioCount": 0
}
```

**`state` 枚举**：`PENDING` \| `GENERATING` \| `READY` \| `FAILED` \| `EXPIRED`

**`missingAudioCount`**：音频缺失条数。**必须返回**，不能静默跳过缺失（研究取数依赖这个数字）。

**`expiresAt`**：产物过期时间，**最长 24 小时**。

---

### 5.3 查询留存期限

```
GET /v1/subjects/{subjectId}/retention
```

**响应** `200`

```json
{
  "audioMonths": 24,
  "options": [6, 12, 24, null],
  "nextCleanupAt": 1795000000000,
  "policyUrl": "https://<domain>/privacy#retention"
}
```

`audioMonths = null` 表示永久保留。

---

### 5.4 更新留存期限

```
PUT /v1/subjects/{subjectId}/retention
```

**请求**：`{ "audioMonths": 12 }`

**响应** `200`：更新后的留存设置

**合规**：变更写审计日志（记录旧值、新值、操作者）。

---

### 5.5 删除单条记录

```
DELETE /v1/records/{recordId}
```

**响应** `204`

**副作用（全部必需）**

1. 硬删除对象存储中的音频
2. 硬删除转写、指标、事件标注
3. **触发基线重算**（样本数变了）
4. 写审计日志

**合规**：权限为 `canWrite`（任意有效绑定账号）。**删除操作全部审计**（操作者、recordId、时间）。

**注意**：删除后基线不重算是常见 bug，会导致后续状态判定基于已不存在的样本。

---

### 5.6 清空全部记录

```
POST /v1/subjects/{subjectId}/purge
```

**请求**

```json
{ "confirmText": "删除", "reason": "USER_REQUEST" }
```

**响应** `202`：`{ "taskId": "del_01H...", "state": "PENDING" }`

**权限**：**仅老人端会话**（老人设备上、老人在场操作，视为老人本人的意思表示——新模型下老人无账号）。家属端调用返回 404。

**合规**

| 约束 | 说明 |
|---|---|
| 必须传确认词 | 防误触 |
| 仅本人 | 见 `../PROJECT_REQUIREMENTS.md` 2.5 |
| 保留账号 | 只清记录，不注销 |
| 硬删除 | 含对象存储 |
| 写审计日志 | — |

---

### 5.7 注销账号

```
DELETE /v1/accounts/me
```

**请求**：`{ "confirmText": "注销" }`

**响应** `202`

```json
{
  "state": "DELETING",
  "coolingOffUntil": 1790604800000,
  "note": "7 天内登录可撤销注销。本地数据已清除，云端数据将在冷静期结束后彻底删除。"
}
```

**合规硬约束**

| 约束 | 说明 |
|---|---|
| 7 天冷静期 | 期间登录可撤销 |
| 幂等 | 重复调用返回同一冷静期截止时间 |
| **立即解绑** | 冷静期开始即解除所有 CareLink，他人立即无法访问 |
| **客户端本地立即清除** | 不等服务端（见 `../frontend/modules/引导与账号设置.md` 8.1） |
| 冷静期结束后按绑定关系处置 | 账号硬删除；老人仍被其他账号绑定 → 数据保留；无人绑定 → 硬删除（决策 #18） |
| 保留匿名统计 | 不含任何可识别信息 |
| 审计日志保留 12 个月 | 记录"某账号已注销"，不含内容 |

**响应文案中的 `note` 是刚需**：用户需要知道"现在删了什么、之后还会删什么"，否则会以为没删干净或已删干净。

---

### 5.8 撤销注销

```
POST /v1/accounts/me/cancel-deletion
```

**响应** `200`：`{ "state": "ACTIVE" }`

**错误**：`CONFLICT`（已过冷静期，不可撤销）

---

### 5.9 复诊回填

```
POST /v1/subjects/{subjectId}/followup
Idempotency-Key: <uuid>
```

**请求**

```json
{ "treatedAt": 1790000000000, "visited": true, "doctorNote": "医生说先观察", "hospital": "省医院记忆门诊" }
```

**响应** `201`

**合规**：`doctorNote` 是**家属自己记录的医生原话**，服务端不做分析、不提取关键词、不结构化。

---

### 5.10 查询审计日志（涉及自己的）

```
GET /v1/audit-logs?cursor=&limit=20&action=DELETE&from=&to=
```

**权限**：本人——**只返回 `actorId = 当前账号` 或目标对象归属当前账号**的记录。管理员全量查询走运营后台，不经本接口。

**响应** `200`

```json
{
  "items": [
    {
      "logId": "aud_01H...",
      "action": "AUDIO_READ",
      "targetType": "record",
      "targetId": "rec_01H...",
      "occurredAt": 1790000000000
    }
  ],
  "nextCursor": "cur_01J..."
}
```

**`action` 枚举**：`AUDIO_READ` | `EXPORT` | `CONSENT_CHANGE` | `DELETE` | `ADMIN_ACCESS` | `BIND_CHANGE`

**合规硬约束**：

1. 响应**只含**操作类型、目标、时间——**不含** IP 摘要、操作者角色、`purpose` 等内部字段（那是管理员侧视图）
2. 不返回任何音频内容、转写、数值（审计日志本身就不记录这些，见 `../backend/modules/数据生命周期与合规.md` 11）
3. 游标分页，`limit` 上限 100；只读，**无修改/删除接口**
4. 保留 12 个月，到期随审计清理策略硬删除

---

## 3. 本域合规约束

| # | 约束 |
|---|---|
| 1 | **不存在**任何面向医生的临床输出接口（1.1） |
| 2 | 导出内容不含 `zScore` / 均值 / 标准差 / 量表分数 |
| 3 | 导出产物 24 小时必删 |
| 4 | 删除为硬删除，对象存储中确实消失 |
| 5 | 删除后触发基线重算 |
| 6 | 清空全部记录**仅老人端会话**；单条删除 `canWrite`（全部审计） |
| 7 | 注销有 7 天冷静期，幂等，立即解绑 |
| 8 | 注销彻底删除，仅保留匿名统计 |
| 9 | 导出与注销写审计日志 |
| 10 | 导出限流严格（含敏感数据 + 重操作） |
| 11 | 删除 / 注销 / 导出队列的死信必须告警 |
| 12 | 越权返回 404 |

---

## 4. 本域测试要求

| 测试 | 断言 |
|---|---|
| 接口存在性 | `/v1/medical/*` 全部返回 404 |
| 删除验证 | 删除后**直接查对象存储**，断言对象不存在（非仅数据库标记） |
| 注销验证 | 注销后**所有接口**都无法再访问该账号数据 |
| 幂等 | 重复删除、重复注销、重复导出 |
| 冷静期 | 申请 → 3 天后撤销 → 数据完好 |
| 产物清理 | 模拟时间推进 24 小时，断言导出产物已删除 |
| 权限 | 家属调用 5.6 返回 404 |
| 契约 | 导出文件不含 `zScore` / 均值 / 标准差 / 量表分数 |
| 死信告警 | 注入对象存储故障，断言死信队列告警触发 |
