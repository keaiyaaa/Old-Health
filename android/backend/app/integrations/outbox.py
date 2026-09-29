"""事务性发件箱（README 8.2）：业务事务内只写 outbox，禁止事务内 task.delay。

Celery beat 的发布者任务每秒轮询 outbox 投递到队列。
"""
import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Outbox

QUEUES = {
    "analysis": "analysis",
    "cleanup": "cleanup",
    "export": "export",
    "purge": "cleanup",
    "notify": "notify",
}


def enqueue(db: Session, *, aggregate_type: str, aggregate_id: uuid.UUID,
            event_type: str, payload: dict) -> None:
    db.add(Outbox(
        aggregate_type=aggregate_type,
        aggregate_id=aggregate_id,
        event_type=event_type,
        payload=payload,
    ))


def fetch_unpublished(db: Session, limit: int = 100) -> list[Outbox]:
    return list(db.scalars(
        select(Outbox).where(Outbox.published_at.is_(None)).order_by(Outbox.id).limit(limit)
    ))
