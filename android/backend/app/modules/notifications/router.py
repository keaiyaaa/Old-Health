"""API-07 · 通知与推送。

合规：payload 只含 copyKey + 占位符，不含指标数值与判断性词汇；
pushToken 加密存储，日志不出现。
"""
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.deps import get_principal
from app.core.errors import AppError, ErrorCode
from app.core.ids import uuid7
from app.core.pagination import cursor_limit, encode_cursor, to_millis
from app.core.security import aes_encrypt
from app.db.models import DeviceToken, Notification
from app.db.session import get_db

router = APIRouter(tags=["notifications"])


class PushTokenIn(BaseModel):
    clientId: str
    channel: str = Field(pattern="^(HUAWEI|XIAOMI|OPPO|VIVO|FCM|APNS|POLLING)$")
    pushToken: str


@router.post("/devices/push-token")
def register_push_token(body: PushTokenIn, principal=Depends(get_principal),
                        db: Session = Depends(get_db)):
    # 同 clientId 重复注册覆盖旧 token（设备重装场景）
    row = db.scalar(select(DeviceToken).where(DeviceToken.client_id == body.clientId))
    if row is None:
        row = DeviceToken(id=uuid7(), account_id=principal.account.id,
                          client_id=body.clientId)
        db.add(row)
    row.account_id = principal.account.id
    row.channel = body.channel
    row.push_token_encrypted = aes_encrypt(body.pushToken)
    row.revoked_at = None
    db.flush()
    return {"tokenId": f"tok_{row.id}"}


@router.delete("/devices/push-token/{clientId}", status_code=204)
def revoke_push_token(clientId: str, principal=Depends(get_principal),
                      db: Session = Depends(get_db)):
    row = db.scalar(select(DeviceToken).where(DeviceToken.client_id == clientId))
    if row is not None and row.account_id == principal.account.id:
        row.revoked_at = datetime.now(timezone.utc)


@router.get("/notifications")
def list_notifications(cursor: str | None = None, limit: int = 20,
                       principal=Depends(get_principal), db: Session = Depends(get_db)):
    cur, lim = cursor_limit(cursor, limit, max_limit=50)
    q = select(Notification).where(
        Notification.account_id == principal.account.id
    ).order_by(Notification.created_at.desc(), Notification.id.desc())
    if cur and len(cur) == 2:
        c_at = datetime.fromisoformat(cur[0])
        q = q.where((Notification.created_at < c_at) |
                    ((Notification.created_at == c_at) &
                     (Notification.id < uuid.UUID(cur[1]))))
    rows = db.execute(q.limit(lim + 1)).scalars().all()
    has_more = len(rows) > lim
    rows = rows[:lim]
    return {
        "items": [
            {"notificationId": f"ntf_{n.id}",
             "subjectId": f"sub_{n.subject_id}" if n.subject_id else None,
             "type": n.type, "copyKey": n.copy_key, "params": n.params,
             "createdAt": to_millis(n.created_at),
             "readAt": to_millis(n.read_at)}
            for n in rows
        ],
        "nextCursor": encode_cursor([rows[-1].created_at.isoformat(), str(rows[-1].id)])
        if has_more and rows else None,
    }


@router.post("/notifications/{notificationId}/read")
def mark_read(notificationId: str, principal=Depends(get_principal),
              db: Session = Depends(get_db)):
    try:
        nid = uuid.UUID(notificationId.split("_")[-1])
    except Exception:
        raise AppError(ErrorCode.NOT_FOUND)
    n = db.get(Notification, nid)
    if n is None or n.account_id != principal.account.id:
        raise AppError(ErrorCode.NOT_FOUND)
    if n.read_at is None:
        n.read_at = datetime.now(timezone.utc)
    return {"readAt": to_millis(n.read_at)}  # 幂等：重复调用返回同一 readAt
