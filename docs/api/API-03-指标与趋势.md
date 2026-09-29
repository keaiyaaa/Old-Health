# API-03 · 指标与趋势

**业务域**：指标序列、基线、状态机结果
**对应后端模块**：`../backend/modules/基线与状态机.md`
**对应前端模块**：`../frontend/modules/指标与趋势呈现.md`、`../frontend/modules/状态与提醒.md`

> ⚠️ **本域是合规风险最高的接口域。** 所有接口都受"不返回数值统计量、不返回判断结论"的硬约束。改动本域前必须重读 `../PROJECT_REQUIREMENTS.md` 第 9 章。

---

## 1. 接口清单

| # | 方法 | 路径 | 权限 | 限流 |
|---|---|---|---|---|
| 3.1 | GET | `/v1/metrics/{subjectId}/series` | `canAccess`（仅家属端） | 宽松 |
| 3.2 | GET | `/v1/metrics/{subjectId}/baseline` | `canAccess`（仅家属端） | 宽松 |
| ~~3.3~~ | ~~GET~~ | ~~`/v1/metrics/{subjectId}/plain`~~ | **已删除（B2）**，存在性测试断言 404 | — |
| 3.4 | GET | `/v1/status/{subjectId}` | `canAccess`（仅家属端） | 宽松 |
| 3.5 | POST | `/v1/status/{subjectId}/mark-treated` | `canWrite` | 中等 |

---

## 2. 指标枚举

`metricKey` 是稳定枚举，与 `../PROJECT_REQUIREMENTS.md` 5.2 指标字典一一对应。

| metricKey | 展示名 | 模态 | 可为 null |
|---|---|---|---|
| `SPEECH_RATE` | 说话速度 | 声学 | 否 |
| `PAUSE` | 说话流畅度 | 声学 | 否 |
| `VOCAB` | 用词丰富程度 | 文本 | **是**（ASR 失败时） |
| `COHERENCE` | 说话的连贯性 | 文本 | **是**（ASR 失败时） |

---

## 3. 接口详情

### 3.1 指标时间序列

```
GET /v1/metrics/{subjectId}/series?key=SPEECH_RATE&range=30d&taskType=DAILY_FREE
```

**查询参数**

| 参数 | 必需 | 默认 | 说明 |
|---|---|---|---|
| `key` | ✅ | — | `metricKey` 枚举 |
| `range` | 否 | `30d` | `7d` \| `30d` \| `90d` \| `all` |
| `taskType` | 否 | `DAILY_FREE` | **必须同质比较**，见 `../backend/modules/基线与状态机.md` 3.2 |

**响应** `200`

```json
{
  "subjectId": "sub_01H...",
  "metricKey": "SPEECH_RATE",
  "taskType": "DAILY_FREE",
  "range": "30d",
  "points": [
    { "recordedAt": 1789000000000, "value": 152.3, "qualityFlag": "VALID", "zBand": "WITHIN" },
    { "recordedAt": 1789086400000, "value": 148.9, "qualityFlag": "VALID", "zBand": "WITHIN" },
    { "recordedAt": 1789172800000, "value": null,  "qualityFlag": "INVALID", "zBand": null }
  ],
  "insufficientData": false,
  "excludedCount": 1,
  "baselineEstablished": true,
  "lastSyncAt": 1790000000000
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `value` | **原始数值**。前端负责"不显示数值"的呈现规则（见 `00-接口通则.md` 原则 2） |
| `zBand` | **分档而非数值**：`WITHIN` \| `BELOW_MILD` \| `BELOW_STRONG` \| `ABOVE_MILD`。**刻意不给 z 分数** |
| `qualityFlag` | `VALID` \| `SUSPECT` \| `INVALID`；只有 `VALID` 参与趋势 |
| `insufficientData` | 有效记录 <4 条时为 `true`，前端**不画折线** |
| `excludedCount` | 被排除的记录数，前端需说明"有 N 次记录因音质原因未计入" |
| `baselineEstablished` | 该指标基线是否已建立 |
| `lastSyncAt` | 最后一次同步时间，用于提示"数据可能滞后" |

**⚠️ 合规硬约束（本接口最重要）**

| 禁止返回 | 原因 |
|---|---|
| `zScore`（数值） | 内部统计量，会诱导做阈值解读 |
| `mean` / `stdDev` | 同上 |
| `probability` / `score` / `riskLevel` | X-1 |
| `mocaB` / `mmse` / `fluencyScore` | X-2 |
| 任何文字结论（如 `interpretation`） | 解读属于医生 |

**为什么给 `zBand` 而不是 `zScore`**：前端需要知道"是否偏离"，但不需要知道"偏离多少"。给分档既满足功能，又消除了"1.5SD 意味着什么"的解读空间。分档阈值由服务端定，前端无从反推。

---

### 3.2 基线

```
GET /v1/metrics/{subjectId}/baseline?taskType=DAILY_FREE
```

**响应** `200`

```json
{
  "subjectId": "sub_01H...",
  "taskType": "DAILY_FREE",
  "items": [
    {
      "metricKey": "SPEECH_RATE",
      "isEstablished": true,
      "sampleCount": 14,
      "windowStart": 1788000000000,
      "windowEnd": 1790000000000,
      "progressNeeded": 0
    },
    {
      "metricKey": "VOCAB",
      "isEstablished": false,
      "sampleCount": 2,
      "windowStart": 1789000000000,
      "windowEnd": 1790000000000,
      "progressNeeded": 2
    }
  ]
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `isEstablished` | **按指标分别报告**（声学与文本建立速度不同） |
| `sampleCount` | 参与计算的样本数 |
| `progressNeeded` | 还差几条才能建立，用于"基线建立中（还差 N 次）" |

**⚠️ 合规硬约束**：**不返回 `mean`、`stdDev`、`zScore`**。前端只需要知道"是否已建立"和"还差几条"。

---

### 3.3 ~~老人端简化趋势~~（**已删除**）

> **⚠️ 2026-09-28（B2）：`GET /v1/metrics/{subjectId}/plain` 已彻底删除。**
> "我想看趋势"开关移除，老人端无任何趋势/指标接口。该路径应有存在性测试断言 404。
> 趋势与状态的查看者仅剩家属端（3.1 / 3.2，有效绑定账号）。

---

### 3.4 状态机结果

```
GET /v1/status/{subjectId}
```

**响应** `200`

```json
{
  "subjectId": "sub_01H...",
  "statusCode": "S4",
  "copyKey": "status.s4",
  "nonDiseaseKey": "status.s4.nondisease",
  "validRecordCount": 3,
  "since": 1789900000000,
  "computedAt": 1790000000000,
  "actionKey": "action.prepare_medical",
  "baselineEstablished": { "acoustic": true, "text": false },
  "lastSyncAt": 1790000000000
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `statusCode` | `S0`–`S6` |
| `copyKey` | 文案 key，前端从 `ui/copy/` 渲染。**不返回整句文案** |
| `nonDiseaseKey` | 非疾病解释的文案 key，**S4/S5 时必非空** |
| `validRecordCount` | 有效记录数，前端用于数据充分度 |
| `actionKey` | 行动入口 key（S5 时非空），前端映射到路由 |

**⚠️ 合规硬约束**

| 约束 | 说明 |
|---|---|
| S4/S5 且 `nonDiseaseKey` 为空 | **服务端返回 500 并告警**，不返回不完整数据 |
| 不返回触发原因的具体数值 | 如不返回"低于基线 1.6SD" |
| 不返回 `probability` / `score` | X-1 |
| 不返回历史最高状态 | 降级后不保留（见 `../backend/modules/基线与状态机.md` 4.3） |

**为什么 S4/S5 缺非疾病解释要 500**：宁可页面挂掉被立刻发现，也不能让一条没有非疾病解释的"变化"提示流到用户面前。这是刻意选择的失败方向——**失败要响，不要静**。

---

### 3.5 标记已就医

```
POST /v1/status/{subjectId}/mark-treated
```

**请求**

```json
{ "treatedAt": 1790000000000, "note": "去了省医院记忆门诊" }
```

**响应** `200`：更新后的状态对象（`statusCode = "S6"`）

**权限**：`canWrite`（有效绑定账号；2026-09-28 起角色细分取消）

**副作用**：状态置 S6；写审计日志。

---

## 4. 本域合规约束汇总

| # | 约束 |
|---|---|
| 1 | **不返回** `zScore` / `mean` / `stdDev` / `probability` / `score` / `riskLevel` |
| 2 | **不返回** `mocaB` / `mmse` / `fluencyScore`（任何 C 端接口） |
| 3 | 偏离程度用 `zBand` **分档**表达，不给数值 |
| 4 | 基线按 `metricKey` 分别报告 `isEstablished`，不返回统计量 |
| 5 | 状态只返回 `copyKey` / `nonDiseaseKey` / `actionKey`，不返回文案 |
| 6 | S4/S5 缺 `nonDiseaseKey` → 500 + 告警 |
| 7 | 老人端无任何指标/趋势接口（B2）；`/plain` 路径存在性测试断言 404 |
| 8 | 不返回历史最高状态 |
| 9 | 基线按 `taskType` 分组，不混合 |
| 10 | 响应 `Cache-Control: no-store` |

---

## 5. 契约测试要求

**必须有一个自动化测试，逐字段断言以下名称不出现在本域任何响应中：**

```
zScore, z_score, mean, stdDev, stddev, variance,
probability, prob, score, riskLevel, risk,
mocaB, moca, mmse, ad8, fluencyScore, clinical,
interpretation, conclusion, diagnosis, assessment,
abnormal, normal, severity
```

**测试失败即构建失败。** 这是防止"某次重构顺手把内部字段带出去"的机制保障。
