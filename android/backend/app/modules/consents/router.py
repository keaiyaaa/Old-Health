"""同意管理（API-01 1.14–1.16）。同意全部挂账号（2026-09-28 决策）。"""
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.audit import audit
from app.core.deps import get_principal, require_elder_session
from app.core.errors import AppError, ErrorCode
from app.core.ids import uuid7
from app.core.security import hmac_hash
from app.db.models import Consent
from app.db.session import get_db

router = APIRouter(tags=["consents"])

_CONSENT_META = {
    "PRODUCT": {"revocable": False, "revokeEffect": "撤回即注销账号，走 API-05"},
    "SENSITIVE": {"revocable": True, "revokeEffect": "撤回后录音与分析功能将不可用"},
    "ELDER_INFORMED": {"revocable": True,
                       "revokeEffect": "撤回后将停止采集，已采集数据按老人意愿处理"},
    "RESEARCH": {"revocable": True, "revokeEffect": "撤回后停止研究数据使用"},
}


class ConsentIn(BaseModel):
    type: str = Field(pattern="^(SENSITIVE|ELDER_INFORMED|RESEARCH|PRODUCT)$")
    version: str


def _to_dict(c: Consent | None, ctype: str) -> dict:
    meta = _CONSENT_META[ctype]
    return {
        "type": ctype,
        "version": c.version if c else "1.0",
        "grantedAt": int(c.granted_at.timestamp() * 1000) if c else None,
        "revokedAt": int(c.revoked_at.timestamp() * 1000) if c and c.revoked_at else None,
        "revocable": meta["revocable"],
        "revokeEffect": meta["revokeEffect"],
    }


def _active(db: Session, account_id, ctype: str) -> Consent | None:
    return db.scalar(select(Consent).where(
        Consent.account_id == account_id, Consent.type == ctype,
        Consent.revoked_at.is_(None)))


@router.get("/consents")
def list_consents(principal=Depends(get_principal), db: Session = Depends(get_db)):
    return {"items": [
        _to_dict(_active(db, principal.account.id, t), t) for t in _CONSENT_META
    ]}


@router.post("/consents", status_code=201)
def grant_consent(body: ConsentIn, request: Request,
                  principal=Depends(get_principal), db: Session = Depends(get_db)):
    # 一次只授予一类；PRODUCT 注册时自动授予，不可通过本接口授予
    if body.type == "PRODUCT":
        raise AppError(ErrorCode.NOT_FOUND)
    if body.type in ("SENSITIVE", "ELDER_INFORMED"):
        # 仅老人端会话可授予（家属端 404，不暴露能力存在）
        require_elder_session(principal)
    if body.version != "1.0":
        raise AppError(ErrorCode.INVALID_PARAM, "同意版本无效")
    if _active(db, principal.account.id, body.type):
        return _to_dict(_active(db, principal.account.id, body.type), body.type)
    consent = Consent(
        id=uuid7(), account_id=principal.account.id, type=body.type,
        version=body.version,
        evidence={
            "ts": datetime.now(timezone.utc).isoformat(),
            "ipDigest": hmac_hash(request.client.host if request.client else "").hex(),
            "deviceId": principal.session.device_id,
        },
    )
    db.add(consent)
    db.flush()
    audit(db, action="CONSENT_CHANGE", target_type="consent", target_id=consent.id,
          actor_id=principal.account.id)
    return _to_dict(consent, body.type)


@router.delete("/consents/{consentType}", status_code=204)
def revoke_consent(consentType: str, principal=Depends(get_principal),
                   db: Session = Depends(get_db)):
    if consentType not in ("SENSITIVE", "ELDER_INFORMED", "RESEARCH"):
        raise AppError(ErrorCode.NOT_FOUND)  # PRODUCT 不可撤回（撤回即注销）
    consent = _active(db, principal.account.id, consentType)
    if consent is None:
        return
    consent.revoked_at = datetime.now(timezone.utc)
    audit(db, action="CONSENT_CHANGE", target_type="consent", target_id=consent.id,
          actor_id=principal.account.id)
