# API-02 · 录音与上传

**业务域**：预签名上传、记录登记、分析状态轮询
**对应后端模块**：`../backend/modules/音频存储与上传.md`、`../backend/modules/分析流水线.md`
**对应前端模块**：`../frontend/modules/录音采集与上传.md`

---

## 1. 接口清单

| # | 方法 | 路径 | 权限 | 幂等 | 限流 |
|---|---|---|---|---|---|
| 2.1 | POST | `/v1/records/upload-url` | `canWrite` + SENSITIVE 同意 | — | 中等 |
| 2.2 | POST | `/v1/records` | `canWrite` + SENSITIVE 同意 | **需幂等键** | 中等 |
| 2.3 | GET | `/v1/records/{recordId}/status` | `canAccess` | — | 宽松 |

---

## 2. 上传时序

```
客户端                          API                    对象存储
  │                              │                        │
  │─ 2.1 请求上传地址 ──────────▶│                        │
  │                              │ 校验权限 + 同意         │
  │                              │ 生成 objectKey          │
  │                              │ 签发预签名 URL          │
  │◀── { sessionId, url } ───────│                        │
  │                              │                        │
  │─ PUT 音频（直传）────────────────────────────────────▶│
  │◀──────────────────────────────── 200 ────────────────│
  │                              │                        │
  │─ 2.2 登记记录 ──────────────▶│                        │
  │   Idempotency-Key            │ 校验 objectKey 归属     │
  │                              │ 校验对象存在 + sha256    │
  │                              │ 创建 Record（事务）      │
  │                              │ 入队 analysis（outbox）  │
  │◀── { recordId } ─────────────│                        │
  │                              │                        │
  │─ 2.3 轮询状态 ──────────────▶│                        │
  │◀── { analysisState } ────────│                        │
```

---

## 3. 接口详情

### 2.1 获取上传地址

```
POST /v1/records/upload-url
```

**请求**

```json
{
  "subjectId": "sub_01H...",
  "contentType": "audio/mp4",
  "sizeBytes": 524288,
  "durationSec": 42,
  "taskType": "DAILY_FREE"
}
```

**响应** `200`

```json
{
  "sessionId": "ups_01H...",
  "objectKey": "sub_01H.../2026/09/9f8c...c2.m4a",
  "uploadUrl": "https://<oss-host>/...&Signature=...",
  "method": "PUT",
  "expiresAt": 1790000900000,
  "maxSizeBytes": 10485760,
  "requiredHeaders": { "Content-Type": "audio/mp4" }
}
```

**字段说明**

| 字段 | 说明 |
|---|---|
| `objectKey` | **服务端生成**，含 `subjectId` 前缀，客户端不得修改 |
| `uploadUrl` | 预签名 URL，**≤15 分钟**，单对象绑定，单次使用 |
| `expiresAt` | 过期时间（毫秒时间戳） |
| `maxSizeBytes` | 上限 10MB |

**错误**：`CONSENT_REQUIRED`、`INVALID_FILE`、`QUOTA_EXCEEDED`、`NOT_FOUND`

**合规**

- 必须先校验 `SENSITIVE` 同意有效
- `objectKey` 服务端生成，**不接受客户端指定**
- 签名只允许 PUT，不给 DELETE

---

### 2.2 登记记录

```
POST /v1/records
Idempotency-Key: <uuid v4>
```

**请求**

```json
{
  "sessionId": "ups_01H...",
  "objectKey": "sub_01H.../2026/09/9f8c...c2.m4a",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb924...",
  "subjectId": "sub_01H...",
  "recorderRole": "ELDER_SESSION",
  "taskType": "DAILY_FREE",
  "scheduledAt": 1789990000000,
  "actualStartedAt": 1789990032000,
  "durationSec": 42,
  "sampleRate": 16000,
  "dialect": "ANHUI"
}
```

**字段说明**

| 字段 | 必需 | 说明 |
|---|---|---|
| `sessionId` + `objectKey` | ✅ | 服务端校验两者的绑定关系 |
| `sha256` | ✅ | 服务端校验，防篡改 |
| `recorderRole` | ✅ | `ELDER_SESSION` \| `FAMILY_PROXY`；**一期两者都启用**（2026-09-28 决策：家属端可代录，FR-1.5 升为一等能力）。服务端按会话类型校验：老人端会话只允许 `ELDER_SESSION`，家属端只允许 `FAMILY_PROXY`——**客户端声明不可信** |
| `taskType` | ✅ | `DAILY_FREE` \| `WEEKLY_PICTURE` \| `WEEKLY_FLUENCY` \| `WEEKLY_FREE` |
| `actualStartedAt` | ✅ | 客户端声明时间，用于研究协变量 |
| `durationSec` / `sampleRate` | ✅ | 录音元数据（非指标），仅描述性存储 |
| ~~`acousticMetrics` / `snrDb` / `silenceRatio` / `clientQualityFlag`~~ | — | **已于 2026-09-28（C5）删除**：声学指标由云端 worker 统一计算 |

**响应** `201`

```json
{
  "recordId": "rec_01H...",
  "analysisState": "PENDING",
  "syncState": "SYNCED",
  "createdAt": 1789990100000
}
```

**错误**：`CONSENT_REQUIRED`、`URL_EXPIRED`、`INVALID_OBJECT_KEY`、`OBJECT_NOT_FOUND`、`CHECKSUM_MISMATCH`、`INVALID_FILE`、`CONFLICT`（幂等键相同但请求体不同）

**幂等**：**这是最需要幂等的接口。** 弱网重试若不幂等，会产生重复记录，污染趋势与论文数据。重复请求返回首次的 `recordId`。

**合规**：客户端**不传任何指标**；声学指标（语速、停顿）与文本指标全部由云端 worker 计算，质量门控输入（snrDb / silenceRatio）也来自 worker，见 `../backend/modules/分析流水线.md` 第 4 节。

---

### 2.3 查询分析状态

```
GET /v1/records/{recordId}/status
```

**响应** `200`

```json
{
  "recordId": "rec_01H...",
  "analysisState": "DONE",
  "qualityFlag": "VALID",
  "hasAcoustic": true,
  "hasText": true,
  "failureReason": null
}
```

| 字段 | 说明 |
|---|---|
| `hasAcoustic` | 声学指标是否可用（worker 第 3 步先算先写，仅音频损坏时为 false） |
| `hasText` | 文本指标是否可用；**ASR 失败时为 false** |
| `failureReason` | 失败原因枚举，**不返回技术细节** |

**`failureReason` 枚举**：`ASR_FAILED` \| `ACOUSTIC_FAILED` \| `AUDIO_MISSING` \| `TIMEOUT` \| `UNKNOWN`

**降级规则**：`analysisState = FAILED` 且 `hasAcoustic = true` 时，记录**仍然可用**，只是文本指标为 `null`。前端必须能呈现这个状态。

---

## 4. 本域合规约束

| # | 约束 |
|---|---|
| 1 | 所有接口校验 `SENSITIVE` 同意有效 |
| 2 | `objectKey` 服务端生成，含 `subjectId` 前缀 |
| 3 | 签名 URL ≤15 分钟、单对象、单次使用 |
| 4 | 音频不经业务服务器（直传对象存储） |
| 5 | 登记接口强制幂等 |
| 6 | `recorderRole` 必填，服务端按会话类型判定（老人端=自录，家属端=代录），论文取数据此区分 |
| 7 | 服务端不返回音频二进制，只返回签名 URL |
| 8 | 响应含敏感数据时 `Cache-Control: no-store` |
| 9 | **客户端不传任何指标**；声学 + 文本指标由云端 worker 统一计算（C5） |
| 10 | ASR 失败不导致记录作废；声学指标先算先写，不受 ASR 影响 |

---

## 5. 本域错误码补充

| code | HTTP | 触发 |
|---|---|---|
| `URL_EXPIRED` | 400 | 签名过期，客户端重新调 2.1 |
| `INVALID_OBJECT_KEY` | 400 | objectKey 与 sessionId 不匹配 |
| `OBJECT_NOT_FOUND` | 400 | 对象存储中不存在 |
| `CHECKSUM_MISMATCH` | 400 | sha256 不匹配 |
| `INVALID_FILE` | 400 | 类型 / 大小 / 魔数不符 |
| `QUOTA_EXCEEDED` | 429 | 存储或日上传配额超限 |
