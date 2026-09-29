# API-01 · 认证与账号

**业务域**：认证、账号、老人（Subject）、绑定关系、老人端选人会话、同意管理
**对应后端模块**：`../backend/modules/账号与身份.md`
**对应前端模块**：`../frontend/modules/引导与账号设置.md`

> **⚠️ 2026-09-28 重构**：随账号模型重构（决策 #18）全面改版——
> 手机号体系 → **邮箱 + 密码**；老人自有账号 → **家属账号 + 老人端选人会话**；
> 邀请码绑定 → **「老人名字 + 绑定代码」匹配**。旧版接口定义作废。

---

## 1. 接口清单

| # | 方法 | 路径 | 权限 | 幂等 | 限流 |
|---|---|---|---|---|---|
| 1.1 | POST | `/v1/auth/email-code` | 公开 | — | **严格** |
| 1.2 | POST | `/v1/auth/register` | 公开 | 邮箱唯一约束 | 中等 |
| 1.3 | POST | `/v1/auth/login` | 公开 | — | **严格** |
| 1.4 | POST | `/v1/auth/password-reset` | 公开（凭邮箱验证码） | — | **严格** |
| 1.5 | POST | `/v1/auth/refresh` | 公开（凭 refreshToken） | — | 严格 |
| 1.6 | POST | `/v1/auth/logout` | 已认证 | 天然 | 宽松 |
| 1.7 | GET | `/v1/accounts/me` | 已认证 | — | 宽松 |
| 1.8 | GET | `/v1/subjects` | 已认证 | — | 宽松 |
| 1.9 | POST | `/v1/subjects` | 已认证 | 需幂等键 | **中等 + 防枚举限流** |
| 1.10 | PATCH | `/v1/subjects/{subjectId}` | 有效绑定 | 天然 | 中等 |
| 1.11 | DELETE | `/v1/subjects/{subjectId}/link` | 有效绑定 | 天然 | 中等 |
| 1.12 | GET | `/v1/subjects/{subjectId}/links` | 有效绑定 | — | 宽松 |
| 1.13 | POST | `/v1/session/subject` | 已认证 | — | 宽松 |
| 1.14 | GET | `/v1/consents` | 已认证 | — | 宽松 |
| 1.15 | POST | `/v1/consents` | 视类型（见 1.15） | 需幂等键 | 中等 |
| 1.16 | DELETE | `/v1/consents/{type}` | 视类型（见 1.16） | 天然 | 中等 |

---

## 2. 接口详情

### 1.1 发送邮箱验证码

```
POST /v1/auth/email-code
```

**请求**

```json
{ "email": "user@qq.com", "scene": "REGISTER" }
```

`scene` ∈ `REGISTER` | `PASSWORD_RESET`

> **验证码只有两个场景**：注册验证邮箱、忘记密码重置。**登录不发验证码**（用密码）。

**响应** `200`

```json
{ "expiresInSec": 300, "retryAfterSec": 60 }
```

**错误**：`INVALID_PARAM`、`RATE_LIMITED`、`CODE_TOO_MANY`

**合规**：响应**不透露**该邮箱是否已注册。

---

### 1.2 注册

```
POST /v1/auth/register
```

**请求**

```json
{
  "username": "xiaoming",
  "email": "user@qq.com",
  "code": "123456",
  "password": "********",
  "gender": "MALE"
}
```

| 字段 | 说明 |
|---|---|
| `username` | 账号名，全局唯一 |
| `code` | `REGISTER` 场景邮箱验证码，一次性 |
| `password` | 长度 ≥8；服务端 Argon2id 哈希存储 |
| `gender` | `MALE` \| `FEMALE`；服务端派生 `relationDisplay`（儿子/女儿） |

**响应** `201`

```json
{
  "accountId": "acc_01H...",
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "accessTokenExpiresAt": 1790000000000,
  "relationDisplay": "儿子",
  "onboardingCompleted": false
}
```

**副作用（事务内）**：创建 Account + 初始 `PRODUCT` 同意记录 + 核销验证码。

**错误**：`CODE_INVALID`、`EMAIL_TAKEN`、`USERNAME_TAKEN`、`WEAK_PASSWORD`

---

### 1.3 登录

```
POST /v1/auth/login
```

**请求**

```json
{ "account": "xiaoming", "password": "********", "client": "ELDER_APP", "deviceId": "d_xxx" }
```

| 字段 | 说明 |
|---|---|
| `account` | **账号名或邮箱**均可 |
| `client` | `ELDER_APP` \| `FAMILY_APP`，仅用于审计与会话标记，**不改变权限** |

**响应** `200`

```json
{
  "accountId": "acc_01H...",
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "accessTokenExpiresAt": 1790000000000,
  "pendingSubjectSelection": true,
  "accountDeleting": false
}
```

| 字段 | 说明 |
|---|---|
| `pendingSubjectSelection` | 该账号存在有效绑定时为 `true`。**老人端必须先调 1.8 列表 → 1.13 选人**，才能进入业务功能 |
| `accountDeleting` | 冷静期中为 `true`，响应含 `coolingOffUntil`，提示可撤销注销 |

**错误**：`PASSWORD_WRONG`（累计错误触发限流）、`RATE_LIMITED`

**合规**：`account` 不原样返回；账号信息通过 1.7 获取，邮箱以 `use***@qq.com` 形式返回。

---

### 1.4 重置密码（忘记密码）

```
POST /v1/auth/password-reset
```

**请求**

```json
{ "email": "user@qq.com", "code": "123456", "newPassword": "********" }
```

**响应** `204`

**副作用**：核销验证码 + 更新 passwordHash + **吊销该账号全部 refresh token**（踢出所有会话）。

**错误**：`CODE_INVALID`、`RATE_LIMITED`

**合规**：**响应不透露该邮箱是否已注册**——邮箱不存在时同样返回 `204`（验证码根本没发出去，用户只会以为邮件延迟）。防邮箱枚举。

---

### 1.5 刷新令牌

```
POST /v1/auth/refresh
```

**请求**：`{ "refreshToken": "eyJ..." }`

**响应**：同 1.3 的令牌部分

**错误**：`REFRESH_INVALID`

> 客户端并发刷新必须**只发一次**，见 `00-接口通则.md` 3.2。

---

### 1.6 登出

```
POST /v1/auth/logout
```

**请求**：`{ "refreshToken": "eyJ..." }`（吊销当前设备会话）

**响应**：`204`

---

### 1.7 当前账号信息

```
GET /v1/accounts/me
```

**响应** `200`

```json
{
  "accountId": "acc_01H...",
  "username": "xiaoming",
  "emailMasked": "use***@qq.com",
  "gender": "MALE",
  "relationDisplay": "儿子",
  "onboardingCompleted": true,
  "createdAt": 1789000000000
}
```

---

### 1.8 绑定老人列表（老人端选人 / 家属端切换，共用）

```
GET /v1/subjects
```

**响应** `200`

```json
{
  "items": [
    {
      "subjectId": "sub_01H...",
      "displayName": "李华",
      "ageBand": "75-79",
      "dialect": "ANHUI",
      "hearingIssue": false,
      "retentionMonths": 24,
      "lastSyncAt": 1790000000000,
      "lastRecordAt": 1789990000000,
      "validRecordCount": 12,
      "baselineEstablished": { "acoustic": true, "text": false }
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `lastSyncAt` | 最后一次数据同步时间，用于前端显示"数据可能滞后" |
| `lastRecordAt` | 最后一次**实际录音**时间 |
| `validRecordCount` | 有效记录数，用于数据充分度 |
| `baselineEstablished` | **声学与文本分别报告**，见 `../backend/modules/基线与状态机.md` 3.1 |

**用法**：

| 客户端 | 用法 |
|---|---|
| 老人端 | 登录后弹出此列表（显示 `displayName`），选择后调 1.13 锁定会话 |
| 家属端 | 首页老人列表，点谁看谁（列表切换，无需选人接口也可直接带 subjectId 请求） |

**关键**：`lastSyncAt` 与 `lastRecordAt` **必须分开返回**——前端要区分"老人没录"和"数据没同步"。

---

### 1.9 绑定 / 创建老人（系统生成绑定代码）

```
POST /v1/subjects
Idempotency-Key: <uuid>
```

**请求**

```json
{
  "elderName": "李华",
  "bindCode": "K7mP2xQ9",
  "ageBand": "75-79",
  "dialect": "ANHUI",
  "hearingIssue": false
}
```

| 字段 | 必需 | 说明 |
|---|---|---|
| `bindCode` | 否 | **系统生成的 8 位强码**（不支持自定义，2026-09-28 决策）。**创建新老人时不传**；绑定他人已创建的老人时**必传**（向创建家属索取） |

**匹配规则（2026-09-28 决策 #18 + 系统生成强码）**：

```
bindCode 未提供
  → 新建 Subject，系统生成 8 位绑定代码，仅本次响应返回一次
bindCode 已提供
  → (elderName, bindCode) 哈希匹配既有 Subject？
      ├─ 命中 → 创建 CareLink 绑定到该老人，数据与已绑定家属互通
      └─ 未命中 → 400 + `BIND_CODE_INVALID`（不区分"码错"还是"老人不存在"）
```

**响应** `201`

```json
{
  "subjectId": "sub_01H...",
  "matched": false,
  "relationDisplay": "儿子",
  "bindCode": "K7mP2xQ9"
}
```

| 字段 | 说明 |
|---|---|
| `matched` | `true` = 绑定到既有老人；`false` = 新建 |
| `bindCode` | **仅新建时返回，且仅此一次**——服务端只存哈希，后续任何接口不可再获取；家属需自行保存并分享给其他家属 |

**错误**：`INVALID_PARAM`、`BIND_CODE_INVALID`（提供的代码或名字匹配不上）、`CONFLICT`（该账号已绑定该老人）

**安全硬约束**：

1. `bindCode` 系统生成（8 位字母数字，去除易混淆字符），服务端哈希存储，**创建后不可再查询**
2. 此接口按账号 + IP **防枚举限流**（防止试探他人绑定码）
3. 并发同 `(elderName, bindCode)` 必须靠唯一约束兜底，**禁止产生两个 Subject**
4. 绑定**不能代替老人同意**：绑定成功后，敏感信息接口仍要求 `SENSITIVE` / `ELDER_INFORMED` 同意有效（在老人端会话中授予，见 1.15）

---

### 1.10 更新老人信息

```
PATCH /v1/subjects/{subjectId}
```

**权限**：`canWrite(accountId, subjectId)`（任意有效绑定账号）

**请求**：`{ "displayName": "李华", "hearingIssue": true }`（仅传需修改的字段；`elderName` 与 `bindCode` 是匹配键，**不可通过此接口修改**）

**响应** `200`：更新后的 subject 对象

---

### 1.11 解绑（撤销自己与该老人的绑定）

```
DELETE /v1/subjects/{subjectId}/link
```

**响应** `204`

**副作用（必须）**：

1. 立即吊销当前账号对该 subject 的权限缓存
2. 立即吊销该账号已签发的音频签名 URL
3. 写审计日志
4. **检查该老人是否仍被其他账号绑定**：无人绑定时按生命周期规则进入清理（见 `../backend/modules/账号与身份.md` 6）

---

### 1.12 该老人的绑定家属列表

```
GET /v1/subjects/{subjectId}/links
```

**响应** `200`

```json
{
  "items": [
    { "linkId": "lnk_01H...", "relationDisplay": "儿子", "createdAt": 1789000000000 },
    { "linkId": "lnk_01J...", "relationDisplay": "女儿", "createdAt": 1789100000000 }
  ]
}
```

**合规**：**不返回家属的邮箱、账号名、真实姓名**，只返回关系称谓（儿子/女儿）。

---

### 1.13 老人端选人（会话锁定）

```
POST /v1/session/subject
```

**请求**

```json
{ "subjectId": "sub_01H..." }
```

**前置**：`canAccess(accountId, subjectId)` 通过。

**响应** `200`

```json
{ "subjectId": "sub_01H...", "displayName": "李华", "lockedAt": 1790000000000 }
```

**行为**：

1. 当前设备会话锁定到该 subjectId
2. **此后该会话的一切业务请求，服务端强制校验 `subjectId = 会话锁定值`**，不一致返回 404
3. 切换老人 = 再次调用本接口（产生新的锁定，写审计）
4. 未选人就请求业务接口 → `400 + SUBJECT_NOT_SELECTED`

---

### 1.14 查询同意状态

```
GET /v1/consents
```

> **2026-09-28（用户决策）：同意全部挂账号**，不再按 subjectId 查询——同一账号的同意覆盖其全部绑定老人。

**响应** `200`

```json
{
  "items": [
    {
      "type": "PRODUCT",
      "version": "1.0",
      "grantedAt": 1789000000000,
      "revokedAt": null,
      "revocable": false,
      "revokeEffect": "撤回即注销账号，走 API-05"
    },
    {
      "type": "SENSITIVE",
      "version": "1.0",
      "grantedAt": null,
      "revokedAt": null,
      "revocable": true,
      "revokeEffect": "撤回后录音与分析功能将不可用"
    },
    {
      "type": "ELDER_INFORMED",
      "version": "1.0",
      "grantedAt": null,
      "revokedAt": null,
      "revocable": true,
      "revokeEffect": "撤回后将停止采集，已采集数据按老人意愿处理"
    },
    {
      "type": "RESEARCH",
      "version": "1.0",
      "grantedAt": null,
      "revokedAt": null,
      "revocable": true,
      "revokeEffect": "撤回后停止研究数据使用"
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `revocable` | 是否可撤回 |
| `revokeEffect` | **撤回后果说明**，前端必须展示在撤回按钮旁边 |

**合规**：`revokeEffect` 是刚需。只给"已同意"状态而不给撤回后果说明，等于让用户盲签。

---

### 1.15 授予同意

```
POST /v1/consents
Idempotency-Key: <uuid>
```

**请求**

```json
{ "type": "SENSITIVE", "version": "1.0" }
```

> 同意挂账号（2026-09-28 决策），请求**不再带 subjectId**。

**权限按类型区分**：

| 类型 | 授予者 | 场景 |
|---|---|---|
| `PRODUCT` | 注册时自动授予 | 不可通过本接口授予 |
| `SENSITIVE` | **仅老人端会话** | 在老人设备上、老人在场时授予（为该账号授予，覆盖其全部绑定老人） |
| `ELDER_INFORMED` | **仅老人端会话** | 同上 |
| `RESEARCH` | 见 `API-06-研究模式.md` | 研究模式 |

**设计依据**：老人没有账号，**老人端会话（老人设备 + 老人在场）视为老人本人的意思表示**。同意记录挂账号，但授予场景仍锁定在老人端会话——家属端无法代授 `SENSITIVE` / `ELDER_INFORMED`。

**响应** `201`：同意对象

**合规硬约束**：

1. **一次只能授予一类同意**。不接受数组、不接受 `type: "ALL"`
2. 必须传 `version`，服务端校验是否为当前有效版本
3. 服务端记录留痕（时间、设备、IP 摘要）
4. 家属端调用授予 `SENSITIVE` / `ELDER_INFORMED` → `404`（不暴露该能力存在）

---

### 1.16 撤回同意

```
DELETE /v1/consents/{type}
```

**权限**：

| 类型 | 撤回者 |
|---|---|
| `PRODUCT` | 不可撤回（撤回即注销，走 `API-05`） |
| `SENSITIVE` / `ELDER_INFORMED` | **账号本人**（挂账号后语义统一；授予场景仍在老人端会话，撤回随时可做） |
| `RESEARCH` | 见 `API-06-研究模式.md` |

**响应** `204`

**合规硬约束**：

1. 撤回后立即失效权限与同意缓存，**无延迟窗口**
2. 撤回写审计日志

---

## 3. 本域合规约束

| # | 约束 |
|---|---|
| 1 | 邮箱不明文返回，以 `use***@qq.com` 形式；密码与绑定代码**永不返回** |
| 2 | 老人端只显示绑定老人名字；家属端绑定列表只显示关系称谓（儿子/女儿），双向最小暴露 |
| 3 | 只存年龄段，不存精确生日 |
| 4 | 绑定接口防枚举限流；并发同 (名字, 代码) 唯一约束兜底 |
| 5 | 老人端会话锁定 subjectId，未选人不得访问业务接口 |
| 6 | 同意一次只授予一类，无"全部同意"接口；响应含 `revocable` 与 `revokeEffect` |
| 7 | `SENSITIVE` / `ELDER_INFORMED` 挂账号、仅老人端会话可授予；账号本人可撤回 |
| 8 | 解绑立即失效权限与签名 URL；无人绑定的老人数据进入清理 |
| 9 | 验证码接口不透露邮箱是否已注册 |
| 10 | 重置密码后吊销全部会话 |
