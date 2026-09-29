"""审计日志（README 5.7：必须审计的操作写入 AuditLog，保留 12 个月）。

禁止记录：音频内容、转写全文、手机号明文、令牌、密钥。
"""
import uuid

from sqlalchemy.orm import Session

from app.core.security import hmac_hash
from app.db.models import AuditLog


def audit(
    db: Session,
    *,
    action: str,
    target_type: str,
    target_id: uuid.UUID | None = None,
    actor_id: uuid.UUID | None = None,
    actor_role: str | None = None,
    ip: str | None = None,
    purpose: str | None = None,
) -> None:
    db.add(AuditLog(
        actor_id=actor_id,
        actor_role=actor_role,
        action=action,
        target_type=target_type,
        target_id=target_id,
        ip_digest=hmac_hash(ip) if ip else None,
        purpose=purpose,
    ))
