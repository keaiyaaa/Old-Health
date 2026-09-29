# API-07 · 通知与推送

**业务域**：推送令牌、通知列表、已读状态
**对应后端模块**：`../backend/modules/通知与推送.md`
**对应前端模块**：`../frontend/modules/状态与提醒.md`（通知中心）

---

## 1. 接口清单

| # | 方法 | 路径 | 权限 | 幂等 | 限流 |
|---|---|---|---|---|---|
| 7.1 | POST | `/v1/devices/push-token` | 已认证 | 天然（同 clientId 覆盖） | 宽松 |
| 7.2 | DELETE | `/v1/devices/push-token/{clientId}` | 已认证 | 天然 | 宽松 |
| 7.3 | GET | `/v1/notifications?cursor=&limit=` | 已认证 | — | 宽松 |
| 7.4 | POST | `/v1/notifications/{notificationId}/read` | 已认证 | 天然 | 宽松 |

---

## 2. 接口详情

### 7.1 注册推送令牌

```
POST /v1/devices/push-token
```

**请求**

```json
{ "clientId": "d_xxx", "channel": "XIAOMI", "pushToken": "eyJ..." }
```

**响应** `200`：`{ "tokenId": "tok_01H..." }`

**说明**：同 `clientId` 重复注册**覆盖旧 token**（设备重装场景）；登出与被踢出时服务端自动吊销。

---

### 7.2 吊销推送令牌

```
DELETE /v1/devices/push-token/{clientId}
```

**响应** `204`

---

### 7.3 通知列表（游标分页）

```
GET /v1/notifications?cursor=&limit=20
```

**响应** `200`

```json
{
  "items": [
    {
      "notificationId": "ntf_01H...",
      "subjectId": "sub_01H...",
      "type": "STATUS_CHANGED",
      "copyKey": "notify.status.s4",
      "params": { "elderName": "李华" },
      "createdAt": 1790000000000,
      "readAt": null
    }
  ],
  "nextCursor": "cur_01J..."
}
```

**合规硬约束**：payload 只含 `copyKey` + 占位符，**不含指标数值、不含判断性词汇**（红线见 `../backend/modules/通知与推送.md` 第 4 节）。

**说明**：只返回本人 `accountId` 的通知；`limit` 上限 50。

---

### 7.4 标记已读

```
POST /v1/notifications/{notificationId}/read
```

**响应** `200`：`{ "readAt": 1790003600000 }`

**幂等**：重复调用返回同一 `readAt`。

---

## 3. 本域合规约束

| # | 约束 |
|---|---|
| 1 | 通知 payload 无数值、无判断性词汇（红线测试覆盖） |
| 2 | pushToken 加密存储，日志中不出现 |
| 3 | 通知列表只返回本人数据 |
| 4 | 解绑后历史通知保留，但跳转页面按越权返回 404 |
