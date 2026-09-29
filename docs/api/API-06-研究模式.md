# API-06 · 研究模式

**业务域**：研究同意、匿名受试者、临床量表、依从性、数据集导出、人工转录
**对应后端模块**：`../backend/modules/研究数据管理.md`
**对应前端模块**：`../frontend/modules/研究模式.md`
**默认状态**：关闭。关闭时本域全部接口返回 404。

---

## 1. 接口清单

| # | 方法 | 路径 | 权限 | 幂等 | 限流 |
|---|---|---|---|---|---|
| 6.1 | POST | `/v1/research/consent` | `RESEARCHER` | 需幂等键 | 中等 |
| 6.2 | GET | `/v1/research/subjects` | `RESEARCHER` | — | 宽松 |
| 6.3 | POST | `/v1/research/subjects` | `RESEARCHER` | 需幂等键 | 中等 |
| 6.4 | POST | `/v1/research/clinical` | `RESEARCHER` | **需幂等键** | 中等 |
| 6.5 | GET | `/v1/research/clinical?researchSubjectId=` | `RESEARCHER` | — | 宽松 |
| 6.6 | GET | `/v1/research/adherence` | `RESEARCHER` | — | 宽松 |
| 6.7 | GET | `/v1/research/protocol?weekIndex=` | `RESEARCHER` | — | 宽松 |
| 6.8 | POST | `/v1/research/transcript` | `RESEARCHER` | 需幂等键 | 中等 |
| 6.9 | POST | `/v1/research/export` | `RESEARCHER` | 需幂等键 | **最严格** |
| 6.10 | DELETE | `/v1/research/consent/{researchSubjectId}` | 本人或 `RESEARCHER` | 天然 | 中等 |

---

## 2. 前置条件

**本域所有接口的三重前置条件，缺一即返回 404：**

1. 当前账号角色 = `RESEARCHER`
2. 研究模式开关已开启
3. 目标受试者的研究同意有效（除 6.1、6.3 外）

**为什么返回 404 而不是 403**：403 会暴露"研究功能存在"这一事实。对普通用户来说，研究模式应该**完全不可感知**。

---

## 3. 接口详情

### 6.1 授予研究知情同意

```
POST /v1/research/consent
Idempotency-Key: <uuid>
```

**请求**

```json
{ "subjectId": "sub_01H...", "consentVersion": "1.0", "irbApprovalRef": "IRB-2026-0123" }
```

**响应** `201`

```json
{
  "researchSubjectId": "SUBJ-0007",
  "consentVersion": "1.0",
  "grantedAt": 1790000000000,
  "withdrawable": true,
  "withdrawEffect": "撤回后停止研究数据使用；已导出的匿名数据集无法追溯撤回"
}
```

**合规硬约束**

| 约束 | 说明 |
|---|---|
| 研究同意与产品同意**完全独立** | 撤回研究同意不影响产品功能 |
| `withdrawEffect` 必返 | **必须明确告知"已导出的匿名数据集无法追溯撤回"** |
| 匿名 ID 在同意时分配 | 事务内完成，并发安全 |

**`withdrawEffect` 的措辞是伦理要求**：让受试者知道撤回的边界在哪，而不是暗示"随时可以全部收回"。

---

### 6.2 匿名受试者列表

```
GET /v1/research/subjects
```

**响应** `200`

```json
{
  "items": [
    {
      "researchSubjectId": "SUBJ-0007",
      "enrolledAt": 1789000000000,
      "consentValid": true,
      "recordCount": 34,
      "validRecordCount": 30,
      "caregiverRecordCount": 4,
      "scaleCount": 3
    }
  ]
}
```

**⚠️ 合规硬约束**

| 禁止返回 | 原因 |
|---|---|
| 真实姓名 | 匿名化 |
| 手机号 | 匿名化 |
| 精确生日 | 匿名化 |
| 内部 `subjectId` | 映射关系不外泄 |
| 昵称（如"妈妈"） | 也可能识别到人 |

**只返回 `researchSubjectId`。** 研究者如需关联，通过独立的、有额外授权的流程。

**`caregiverRecordCount` 必返**：论文取数必须能识别代录样本（FR-8.5）。

---

### 6.3 受试者入组

```
POST /v1/research/subjects
Idempotency-Key: <uuid>
```

**请求**

```json
{ "subjectId": "sub_01H...", "projectId": "prj_01H...", "consentVersion": "1.0" }
```

**响应** `201`：受试者对象（含分配的 `researchSubjectId`）

**注意**：入组请求**接受内部 `subjectId`**（研究者是在把已绑定的老人纳入研究），但**响应与后续所有接口都只用匿名 ID**。

---

### 6.4 录入临床量表

```
POST /v1/research/clinical
Idempotency-Key: <uuid>
```

**请求**

```json
{
  "researchSubjectId": "SUBJ-0007",
  "scaleType": "MOCA_B",
  "score": 22,
  "assessedAt": 1790000000000,
  "assessor": "李医生",
  "note": "受试者配合度良好"
}
```

**`scaleType` 枚举**：`MOCA_B` \| `MMSE` \| `FLUENCY`

**响应** `201`

**⚠️ 合规硬约束**

| 约束 | 说明 |
|---|---|
| 本接口是**唯一**接受量表分数的入口 | X-2 |
| 量表分数**不得出现在任何 C 端接口** | 契约测试断言 |
| 量表分数**不得进日志** | 审计只记"录入了某类型的量表"，不记分数 |
| 幂等 | 同一受试者 + 类型 + 日期重复录入需幂等 |

---

### 6.5 查询量表记录

```
GET /v1/research/clinical?researchSubjectId=SUBJ-0007
```

**响应** `200`：量表记录列表

**权限**：仅 `RESEARCHER`。**C 端账号调用返回 404。**

---

### 6.6 依从性报表

```
GET /v1/research/adherence?projectId=prj_01H...&from=&to=
```

**响应** `200`

```json
{
  "projectId": "prj_01H...",
  "items": [
    {
      "researchSubjectId": "SUBJ-0007",
      "expectedCount": 84,
      "completedCount": 71,
      "completionRate": 0.845,
      "missingDistribution": { "WEEK1": 1, "WEEK3": 4, "WEEK5": 3 },
      "caregiverRecordCount": 4,
      "caregiverRatio": 0.056,
      "timeOfDayDistribution": { "MORNING": 30, "AFTERNOON": 12, "EVENING": 29 }
    }
  ]
}
```

**`timeOfDayDistribution` 是研究刚需**：老年人 EMA 早间依从率高于晚间，时段本身是协变量（见 `../PROJECT_REQUIREMENTS.md` 5.5）。

**`caregiverRatio`**：代录占比，论文中需报告（FR-8.5）。

---

### 6.7 查询任务协议

```
GET /v1/research/protocol?weekIndex=3
```

**响应** `200`

```json
{
  "protocolId": "prt_01H...",
  "version": "1.0",
  "weekIndex": 3,
  "tasks": [
    {
      "taskType": "WEEKLY_PICTURE",
      "guideText": "请看一下这张图，然后告诉我图里发生了什么。",
      "durationSec": 60,
      "imageUrl": "https://<oss-host>/protocol/cookie-theft-v1.png"
    },
    {
      "taskType": "WEEKLY_FLUENCY",
      "guideText": "请在一分钟之内，尽量多说一些动物的名字。",
      "durationSec": 60
    }
  ]
}
```

**⚠️ 关键约束**

| 约束 | 说明 |
|---|---|
| `guideText` **逐字固定** | 跨被试完全一致（FR-8.4） |
| `version` 必返 | 指导语改动会让数据不可比，必须能追溯 |
| 顺序不可打乱 | 任务顺序影响表现 |
| 时长不可由客户端调整 | 固定 |

**为什么 `guideText` 要由服务端下发而不是硬编码在 App**：改指导语需要能追溯版本，且不能靠发版才能改。

---

### 6.8 导入人工转录

```
POST /v1/research/transcript
Idempotency-Key: <uuid>
```

**请求**

```json
{
  "recordId": "rec_01H...",
  "text": "今天天气不错，我去公园走了走。",
  "transcribedBy": "annotator_a",
  "secondTranscription": "今天天气不错，我去公园走了一走。"
}
```

**响应** `201`

**合规**：人工转录**不覆盖** ASR 文本，两者并存（`computedFrom` 区分）。`secondTranscription` 用于双人转录一致性（ICC）计算。

**副作用**：触发文本指标重算（基于人工转录口径）。

---

### 6.9 数据集导出

```
POST /v1/research/export
Idempotency-Key: <uuid>
```

**请求**

```json
{
  "projectId": "prj_01H...",
  "subjectIds": ["SUBJ-0007", "SUBJ-0008"],
  "from": 1787000000000,
  "to": 1790000000000,
  "include": ["AUDIO", "ASR_TEXT", "MANUAL_TEXT", "METRICS", "SCALES", "EVENTS"],
  "format": "ZIP"
}
```

**响应** `202`：`{ "jobId": "rexp_01H...", "state": "PENDING" }`

**⚠️ 合规硬约束（本接口最严）**

| 约束 | 说明 |
|---|---|
| **导出文件中不得出现**姓名 / 手机号 / 精确生日 / 内部 `subjectId` / 昵称 | 匿名化 |
| 只使用 `researchSubjectId` | 作为唯一标识 |
| 受试者研究同意必须有效 | 已撤回的受试者**拒绝导出**，明确报错 |
| 导出文件含 `protocolVersion` | 便于追溯任务范式 |
| 导出文件含 `computedFrom` | 区分 ASR 与人工转录口径 |
| 产物 24 小时必删 | 同产品导出 |
| 限流最严格 | 单账号 10 次/天 |
| 写审计日志 | 记录项目、范围、时间、操作者 |

**导出内容结构**

```
export_<jobId>.zip
├── manifest.json            # 项目、协议版本、导出范围、缺失统计
├── subjects.csv             # researchSubjectId, ageBand, dialect, hearingIssue
├── metrics.csv              # researchSubjectId, recordId, recordedAt, 6 项指标
├── events.csv               # researchSubjectId, recordId, type, note
├── scales.csv               # researchSubjectId, scaleType, score, assessedAt
├── transcripts/
│   ├── <recordId>_asr.txt
│   └── <recordId>_manual.txt
└── audio/
    └── <researchSubjectId>/<recordId>.m4a
```

**`manifest.json` 中的 `missingAudioCount`**：缺失音频条数，不能静默跳过。

---

### 6.10 撤回研究同意

```
DELETE /v1/research/consent/{researchSubjectId}
```

**响应** `204`

**权限**：受试者本人或 `RESEARCHER`（作为研究方）。

**副作用**

1. 停止该受试者的研究数据使用
2. 后续导出该受试者**拒绝**
3. **已导出的匿名数据集无法追溯撤回**（这一点在 6.1 的 `withdrawEffect` 中已告知）
4. 写审计日志

---

## 4. 本域合规约束汇总

| # | 约束 |
|---|---|
| 1 | 研究模式关闭时，本域全部接口返回 404 |
| 2 | 非 `RESEARCHER` 访问返回 404（不是 403） |
| 3 | 所有响应与导出只用 `researchSubjectId`，无姓名/手机号/生日/内部 ID/昵称 |
| 4 | 真实姓名映射**无 HTTP 接口**，仅内部服务可访问 |
| 5 | 量表分数不进日志 |
| 6 | **C 端任何接口不返回量表分数** |
| 7 | 任务指导语逐字固定且版本化 |
| 8 | 人工转录不覆盖 ASR 文本，两者并存 |
| 9 | 撤回研究同意后拒绝导出该受试者 |
| 10 | 导出产物 24 小时必删 |
| 11 | 导出限流最严格（10 次/天） |
| 12 | 本域不影响 C 端任何呈现（隔离测试） |
| 13 | 研究数据境内存储，不出境 |

---

## 5. 本域测试要求

| 测试 | 断言 |
|---|---|
| 隔离测试 | 研究模式开 vs 关，**C 端全部接口响应完全一致** |
| 匿名化测试 | 导出文件中扫描姓名 / 手机号 / 内部 `subjectId` / 昵称，命中数为 0 |
| 红线测试 | **C 端任何接口不返回 `mocaB` / `mmse` / `fluencyScore`**（E2E-11） |
| 404 测试 | 非研究者访问本域全部接口，断言 404 |
| 撤回测试 | 撤回后导出被拒 |
| 并发测试 | 同一 subjectId 并发入组，断言只分配一个匿名 ID |
| 一致性测试 | 指导语跨版本对比，变更必须伴随版本号变化 |
| 产物清理 | 24 小时后导出产物已删除 |
| 日志测试 | 量表分数不出现在任何日志中 |
