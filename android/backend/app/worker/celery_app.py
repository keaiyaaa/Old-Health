"""Celery worker 配置（README 8.2/8.3）。"""
from celery import Celery
from celery.schedules import crontab

from app.config import get_settings

s = get_settings()
celery_app = Celery(
    "cogni",
    broker=s.redis_url,
    backend=s.redis_url,
    include=["app.worker.tasks"],
)
celery_app.conf.update(
    task_queues={
        "analysis": {"exchange": "analysis", "routing_key": "analysis"},
        "cleanup": {"exchange": "cleanup", "routing_key": "cleanup"},
        "notify": {"exchange": "notify", "routing_key": "notify"},
    },
    task_default_queue="analysis",
    # 分析队列：最多 3 次，指数退避；单任务 120s（分析流水线 7）
    task_acks_late=True,
    worker_prefetch_multiplier=1,
    task_time_limit=120,
    task_soft_time_limit=110,
    worker_concurrency=4,  # 按 ASR 配额设定，上限写死
    beat_schedule={
        "publish-outbox": {
            "task": "app.worker.tasks.publish_outbox",
            "schedule": 1.0,  # 每秒轮询事务性发件箱
        },
        "daily-sweep": {
            "task": "app.worker.tasks.daily_sweep",
            "schedule": crontab(hour=3, minute=0),
        },
        "retention-scan": {
            "task": "app.worker.tasks.retention_scan",
            "schedule": crontab(hour=4, minute=0),
        },
        "export-expiry-cleanup": {
            "task": "app.worker.tasks.export_expiry_cleanup",
            "schedule": crontab(minute=0),  # 每小时：24h 产物必删
        },
    },
)
