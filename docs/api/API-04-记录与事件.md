# API-04 · 记录与事件

**业务域**：记录列表与详情、音频播放地址、转写修正、事件标注、提醒设置
**对应后端模块**：`../backend/modules/音频存储与上传.md`
**对应前端模块**：`../frontend/modules/家属端协作.md`、`../frontend/modules/状态与提醒.md`

---

## 1. 接口清单

| # | 方法 | 路径 | 权限 | 幂等 | 限流 |
|---|---|---|---|---|---|
| 4.1 | GET | `/v1/records` | `canAccess` | — | 宽松 |
| 4.2 | GET | `/v1/records/{recordId}` | `canAccess` | — | 宽松 |
| 4.3 | GET | `/v1/records/{recordId}/audio-url` | `canAccess` + SENSITIVE 同意 | — | 中等 |
| 4.4 | PATCH | `/v1/records/{recordId}/transcript` | `canWrite` | **需幂等键** | 中等 |
| 4.5 | POST | `/v1/records/{recordId}/events` | `canWrite` | **需幂等键** | 中等 |
| 4.6 | DELETE | `/v1/records/{recordId}/events/{eventId}` | `canWrite` | 天然 | 中等 |
| 4.7 | GET | `/v1/subjects/{subjectId}/reminder` | `canAccess` | — | 宽松 |
| 4.8 | PUT | `/v1/subjects/{subjectId}/reminder` | `canWrite` | 天然（全量覆盖） | 中等 |

---

## 2. 接口详情

### 4.1 记录列表

```
GET /v1/records?subjectId=sub_01H...&cursor=&limit=20&from=&to=&taskType=&qualityFlag=&hasNote=
```

**查询参数**

| 参数 | 说明 |
|---|---|
| `subjectId` | 必需 |
| `cursor` / `limit` | 游标分页，`limit` 默认 20，最大 100 |
| `from` / `to` | 毫秒时间戳，左闭右开 |
| `taskType` | 枚举过滤 |
| `qualityFlag` | 枚举过滤 |
| `hasNote` | boolean |
| `sort` | 白名单：`-recordedAt`（默认）、`recordedAt`、`-durationSec` |

**响应** `200`

```json
{
  "items": [
    {
      "recordId": "rec_01H...",
      "recordedAt": 1789990000000,
      "taskType": "DAILY_FREE",
      "recorderRole": "ELDER_SESSION",
      "durationSec": 42,
      "qualityFlag": "VALID",
      "hasNote": true,
      "hasText": true,
      "eventTags": ["COLD"]
    }
  ],
  "nextCursor": "eyJ...",
  "hasMore": true,
  "lastSyncAt": 1790000000000
}
```

**`lastSyncAt` 在列表响应中也返回**：前端需要用它显示"数据可能滞后"提示（见 `../frontend/modules/家属端协作.md` 7.1）。

---

### 4.2 记录详情

```
GET /v1/records/{recordId}
```

**响应** `200`

```json
{
  "recordId": "rec_01H...",
  "subjectId": "sub_01H...",
  "recordedAt": 1789990000000,
  "scheduledAt": 1789990000000,
  "actualStartedAt": 1789990032000,
  "durationSec": 42,
  "taskType": "DAILY_FREE",
  "recorderRole": "ELDER_SESSION",
  "qualityFlag": "VALID",
  "qualityReasons": [],
  "transcript": {
    "asrText": "今天天气不错，我去公园走了走……",
    "asrTextManual": null,
    "asrConfidence": 0.91,
    "dialect": "ANHUI",
    "computedFrom": "ASR"
  },
  "metrics": {
    "speechRate": 152.3,
    "pauseRatio": 0.18,
    "longPauseCount": 4,
    "mattr": 0.71,
    "meanSentenceLen": 12.4,
    "connectiveRate": 0.06
  },
  "zBand": {
    "SPEECH_RATE": "WITHIN",
    "PAUSE": "WITHIN",
    "VOCAB": "BELOW_MILD",
    "COHERENCE": "WITHIN"
  },
  "caregiverNote": "今天有点感冒",
  "eventTags": [ { "eventId": "evt_01H...", "type": "COLD", "note": "有点流鼻涕" } ]
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `transcript.asrTextManual` | 人工校订文本，为空表示未校订 |
| `transcript.computedFrom` | `ASR` \| `MANUAL`，**论文取数必须区分** |
| `metrics` | 单条原始数值（详情页用于调试与导出，前端不直接展示） |
| `zBand` | **分档，不给 z 分数** |
| `qualityReasons` | 质量标记的原因枚举，用于"为什么这条没计入" |

**⚠️ 合规约束**：不返回 `zScore`；不返回任何解读文字。

---

### 4.3 获取音频播放地址

```
GET /v1/records/{recordId}/audio-url
```

**响应** `200`

```json
{ "playUrl": "https://<oss-host>/...&Signature=...", "expiresAt": 1790000900000 }
```

**合规硬约束**

| 约束 | 说明 |
|---|---|
| 签名 ≤15 分钟 | 短时效 |
| 单对象绑定 | 一个签名只能读一个 key |
| 只允许 GET | 不给写权限 |
| **必须校验 `SENSITIVE` 同意有效** | 撤回同意后不可播放 |
| **每次调用写审计日志** | 记录谁在何时读了哪条音频（NFR-1.7） |
| 响应 `Cache-Control: no-store` | 禁止中间层缓存 |

---

### 4.4 修正转写文本

```
PATCH /v1/records/{recordId}/transcript
Idempotency-Key: <uuid>
```

**请求**

```json
{ "asrTextManual": "今天天气不错，我去公园走了走。" }
```

**响应** `200`：更新后的 `transcript` 对象（`computedFrom` 变为 `MANUAL`）

**权限**：`canWrite`（有效绑定账号；2026-09-28 起角色细分取消，见 `../backend/modules/账号与身份.md` 4.2）。

**副作用**：触发文本指标重算（因为文本变了）→ 触发基线重算。

**合规**：修改转写**不改变原始 `asrText`**，两者并存（FR-8.7 的前提）。

---

### 4.5 添加事件标注

```
POST /v1/records/{recordId}/events
Idempotency-Key: <uuid>
```

**请求**

```json
{ "type": "COLD", "note": "有点流鼻涕，吃了感冒药" }
```

**`type` 枚举**：`COLD` \| `MOVE` \| `HOSPITAL` \| `TRAVEL` \| `OTHER`

**响应** `201`：事件对象

**副作用**：触发状态重算（事件会影响"非疾病解释"的呈现）。

**合规**：`note` 是家属自己写的内容，**服务端不做任何分析或提取**。

---

### 4.6 删除事件标注

```
DELETE /v1/records/{recordId}/events/{eventId}
```

**响应** `204`

---

### 4.7 查询提醒设置

```
GET /v1/subjects/{subjectId}/reminder
```

**响应** `200`

```json
{
  "enabled": true,
  "reminderTime": "19:30",
  "timezone": "Asia/Shanghai",
  "target": "ELDER_DEVICE",
  "consecutiveMissedDays": 3,
  "currentFrequency": "EVERY_OTHER_DAY",
  "autoReduced": true
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `target` | `ELDER_DEVICE` \| `FAMILY` \| `BOTH`（2026-09-28 重命名：老人无账号，`ELDER` 改为 `ELDER_DEVICE`；`CAREGIVER` 改为 `FAMILY`，指家属端） |
| `consecutiveMissedDays` | 连续未录天数 |
| `currentFrequency` | `DAILY` \| `EVERY_OTHER_DAY` \| `WEEKLY` |
| `autoReduced` | 是否因连续未录而自动降频 |

**降频规则**（服务端计算，前端只呈现）：

| 连续未录 | 频率 |
|---|---|
| 0–2 天 | `DAILY` |
| 3–6 天 | `EVERY_OTHER_DAY` |
| ≥7 天 | `WEEKLY` |

**`autoReduced` 不可由用户关闭**（见 `../frontend/modules/状态与提醒.md` 6.1）。

---

### 4.8 更新提醒设置

```
PUT /v1/subjects/{subjectId}/reminder
```

**请求**

```json
{ "enabled": true, "reminderTime": "20:00", "timezone": "Asia/Shanghai", "target": "BOTH" }
```

**响应** `200`：更新后的设置对象

**合规**：**不接受客户端传入 `currentFrequency`**，该字段由服务端根据 `consecutiveMissedDays` 推导。

---

## 3. 本域合规约束

| # | 约束 |
|---|---|
| 1 | 音频播放地址 ≤15 分钟，单对象，仅 GET |
| 2 | 音频相关接口校验 `SENSITIVE` 同意有效 |
| 3 | 每次音频访问写审计日志 |
| 4 | 不返回 `zScore` / `mean` / `stdDev` |
| 5 | 不返回 `mocaB` / `mmse` / `fluencyScore` |
| 6 | 偏离程度用 `zBand` 分档，不给数值 |
| 7 | 人工校订不覆盖原始 ASR 文本，两者并存 |
| 8 | 家属备注不做任何服务端分析 |
| 9 | `currentFrequency` 由服务端推导，客户端不可写 |
| 10 | 响应含敏感数据时 `Cache-Control: no-store` |
| 11 | 越权返回 404 |
