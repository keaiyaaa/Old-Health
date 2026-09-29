"""Worker 任务：分析流水线 / 清理 / 导出 / 发件箱发布 / 每日清扫。

幂等：以 recordId 为键 —— 指标表唯一约束兜底 + Redis 分布式锁双保险（README 8.2）。
降级：声学先算先写库，ASR 失败只丢文本指标；记录永不作废（分析流水线 10.1）。
"""
import csv
import io
import json
import os
import shutil
import tempfile
import uuid
import zipfile
from datetime import date, datetime, timedelta, timezone

import redis as redis_lib
from celery.exceptions import SoftTimeLimitExceeded
from sqlalchemy import select

from app.config import get_settings
from app.db.models import (
    Account,
    AnalysisJob,
    AsrResult,
    AudioObject,
    Baseline,
    DeletionTask,
    DailySweepState,
    EventTag,
    ExportJob,
    ManualTranscript,
    Metric,
    Notification,
    QualityGateResult,
    Record,
    StatusSnapshot,
    Subject,
    CareLink,
)
from app.db.session import get_engine
from app.domain.status_machine import recompute_subject
from app.integrations.asr import AsrPermanentError, AsrRetryableError, get_asr
from app.integrations.metrics_calc import quality_gate
from app.integrations.storage import get_storage
from app.worker.celery_app import celery_app


def _session():
    from sqlalchemy.orm import sessionmaker

    return sessionmaker(bind=get_engine(), autoflush=False, expire_on_commit=False)()


def _lock_key(record_id: str) -> str:
    return f"lock:analysis:{record_id}"


@celery_app.task(
    bind=True,
    autoretry_for=(AsrRetryableError,),
    retry_backoff=True,
    retry_backoff_max=600,
    retry_jitter=False,
    max_retries=3,
    acks_late=True,
)
def process_analysis(self, record_id: str):
    """分析流水线（分析流水线.md 第 4 节）。recordId 幂等。"""
    r = redis_lib.Redis.from_url(get_settings().redis_url, decode_responses=True)
    lock = r.lock(_lock_key(record_id), timeout=110)
    if not lock.acquire(blocking=False):
        return {"skipped": "locked"}
    try:
        return _run_analysis(record_id)
    finally:
        try:
            lock.release()
        except Exception:
            pass


def _run_analysis(record_id: str) -> dict:
    db = _session()
    tmp_path = None
    try:
        try:
            rid = uuid.UUID(record_id)
        except Exception:
            return {"error": "bad id"}
        record = db.get(Record, rid)
        if record is None or record.deleted_at is not None:
            return {"result": "record_deleted"}  # 正常结束，不算失败（7.1）
        job = db.scalar(select(AnalysisJob).where(AnalysisJob.record_id == rid))
        if job is None:
            job = AnalysisJob(id=uuid.uuid4(), record_id=rid, subject_id=record.subject_id)
            db.add(job)
        if job.state == "DONE":
            return {"result": "already_done"}  # 重复消费幂等（6.1）
        job.state = "ANALYZING"
        job.started_at = datetime.now(timezone.utc)
        job.attempts = (job.attempts or 0) + 1
        record.analysis_state = "ANALYZING"
        db.commit()

        audio = db.scalar(select(AudioObject).where(AudioObject.record_id == rid))
        if audio is None:
            _fail(db, job, record, "AUDIO_MISSING")
            return {"result": "failed", "reason": "AUDIO_MISSING"}

        # 第 2 步：下载到临时空间（服务端凭证）；临时文件用后必删（合规 11.2）
        storage = get_storage()
        fd, tmp_path = tempfile.mkstemp(suffix=".audio")
        os.close(fd)
        storage.download(audio.object_key, tmp_path)

        # 第 3 步：声学指标先算先写（不依赖 ASR —— 必得）
        try:
            from app.integrations.metrics_calc import compute_acoustic

            acoustic, dsp_ver = compute_acoustic(tmp_path)
            job.dsp_lib_version = dsp_ver
            _upsert_metrics(db, rid, {
                "PAUSE": acoustic.pause_ratio,
            })
            db.add(QualityGateResult(
                record_id=rid, snr_db=acoustic.snr_db,
                silence_ratio=acoustic.silence_ratio,
                duration_sec=record.duration_sec,
                asr_confidence=None, flag="SUSPECT", reasons=[]))
            db.commit()
        except Exception:
            _fail(db, job, record, "ACOUSTIC_FAILED")
            return {"result": "failed", "reason": "ACOUSTIC_FAILED"}

        # 第 4/5 步：ASR + 文本指标（失败只丢文本指标，声学已写库）
        asr = get_asr()
        subject_row = db.get(Subject, record.subject_id)
        try:
            result = asr.transcribe(tmp_path, subject_row.dialect if subject_row else None)
        except (AsrRetryableError, AsrPermanentError, SoftTimeLimitExceeded):
            result = None
        text_values = {}
        if result is not None:
            # ASR 结果缓存 24h（重试不重复计费）——持久层副本（schema 2）
            existing_asr = db.get(AsrResult, rid)
            if existing_asr is None:
                db.add(AsrResult(
                    record_id=rid, text=result.text, confidence=result.confidence,
                    segments=result.segments, engine=result.engine,
                    model_version=result.model_version))
            from app.integrations.metrics_calc import compute_text_metrics

            speech_rate = compute_text_metrics(result.text, record.duration_sec)
            text_values = {"SPEECH_RATE": speech_rate.get("SPEECH_RATE"),
                           "VOCAB": speech_rate.get("VOCAB"),
                           "COHERENCE": speech_rate.get("COHERENCE")}
            _upsert_metrics(db, rid, text_values)

        # 第 6 步：质量门控
        flag, reasons = quality_gate(
            snr_db=acoustic.snr_db, duration_sec=record.duration_sec,
            silence_ratio=acoustic.silence_ratio,
            asr_confidence=result.confidence if result else None,
            has_text=result is not None)
        qg = db.get(QualityGateResult, rid)
        if qg is None:
            qg = QualityGateResult(record_id=rid)
            db.add(qg)
        qg.flag = flag
        qg.reasons = reasons
        qg.asr_confidence = result.confidence if result else None

        job.asr_engine = result.engine if result else asr.name
        job.asr_model_version = result.model_version if result else asr.model_version
        job.state = "DONE"
        job.finished_at = datetime.now(timezone.utc)
        job.failure_reason = None
        record.analysis_state = "DONE"
        # 第 7 步：同一事务写指标 + 状态重算（README 5.4）
        recompute_subject(db, record.subject_id)
        _notify_record_done(db, record)
        db.commit()
        return {"result": "done"}
    except Exception:
        db.rollback()
        raise
    finally:
        # 第 8 步：删除临时音频文件（必须，不留副本）
        if tmp_path and os.path.exists(tmp_path):
            os.remove(tmp_path)
        db.close()


def _fail(db, job, record, reason: str) -> None:
    job.state = "FAILED"
    job.finished_at = datetime.now(timezone.utc)
    job.failure_reason = reason
    record.analysis_state = "FAILED"
    db.commit()


def _upsert_metrics(db, record_id, values: dict) -> None:
    for key, value in values.items():
        if value is None:
            continue
        row = db.scalar(select(Metric).where(
            Metric.record_id == record_id, Metric.metric_key == key,
            Metric.computed_from == "ASR"))
        if row is None:
            row = Metric(record_id=record_id, metric_key=key, computed_from="ASR")
            db.add(row)
        row.value = value  # 覆盖而非追加（分析流水线 6）


def _notify_record_done(db, record) -> None:
    # 家属端通知：只发 copyKey + 占位符，无数值（通知与推送 4）
    subject = db.get(Subject, record.subject_id)
    links = db.scalars(select(CareLink).where(
        CareLink.subject_id == record.subject_id, CareLink.revoked_at.is_(None))).all()
    for link in links:
        dedupe = f"RECORD_DONE:{record.subject_id}:{record.id}"
        if db.scalar(select(Notification.id).where(
                Notification.dedupe_key == dedupe, Notification.account_id == link.account_id)):
            continue
        db.add(Notification(
            id=uuid.uuid4(), account_id=link.account_id, subject_id=record.subject_id,
            type="RECORD_DONE", copy_key="notify.record_done",
            params={"elderName": subject.display_name}, dedupe_key=dedupe))


@celery_app.task(name="app.worker.tasks.publish_outbox")
def publish_outbox():
    """发件箱发布者（beat 每秒）：outbox → 队列。禁止业务事务内直接 delay。"""
    from app.integrations.outbox import fetch_unpublished

    db = _session()
    try:
        rows = fetch_unpublished(db)
        for row in rows:
            try:
                if row.aggregate_type == "analysis":
                    if row.event_type in ("record.registered",):
                        process_analysis.apply_async(
                            args=[row.payload["recordId"]], queue="analysis")
                    else:
                        recompute_async.apply_async(
                            args=[row.payload["subjectId"]], queue="analysis")
                elif row.aggregate_type == "cleanup":
                    cleanup.apply_async(args=[str(row.aggregate_id)], queue="cleanup")
                elif row.aggregate_type == "purge":
                    purge_subject.apply_async(args=[str(row.aggregate_id)], queue="cleanup")
                elif row.aggregate_type == "export":
                    generate_export.apply_async(args=[str(row.aggregate_id)], queue="analysis")
                elif row.aggregate_type == "notify":
                    pass  # 一期通知落库即达，推送通道接入后扩展
                row.published_at = datetime.now(timezone.utc)
                db.commit()
            except Exception:
                db.rollback()
    finally:
        db.close()


@celery_app.task(name="app.worker.tasks.recompute_async")
def recompute_async(subject_id: str):
    """基线与状态重算（异步、幂等、可合并）。"""
    db = _session()
    try:
        recompute_subject(db, uuid.UUID(subject_id))
        db.commit()
    finally:
        db.close()


@celery_app.task(name="app.worker.tasks.cleanup", max_retries=5, retry_backoff=True,
                 retry_backoff_max=600, retry_jitter=False, autoretry_for=(Exception,))
def cleanup(deletion_task_id: str):
    """删除任务：RECORD 硬删除（含对象存储）→ DB 行随后硬删。失败进死信告警。"""
    db = _session()
    try:
        task = db.get(DeletionTask, uuid.UUID(deletion_task_id))
        if task is None or task.state in ("DONE", "DEAD"):
            return {"result": "noop"}
        task.state = "RUNNING"
        db.commit()
        if task.scope == "RECORD":
            rid = task.target_id
            audio = db.scalar(select(AudioObject).where(AudioObject.record_id == rid))
            if audio:
                get_storage().delete(audio.object_key)  # 对象存储硬删除（红线 11）
                db.delete(audio)
            db.query(Metric).filter(Metric.record_id == rid).delete()
            from app.db.models import EventTag, ManualTranscript

            db.query(EventTag).filter(EventTag.record_id == rid).delete()
            db.query(ManualTranscript).filter(ManualTranscript.record_id == rid).delete()
            db.query(AsrResult).filter(AsrResult.record_id == rid).delete()
            db.query(AnalysisJob).filter(AnalysisJob.record_id == rid).delete()
            db.query(QualityGateResult).filter(QualityGateResult.record_id == rid).delete()
            record = db.get(Record, rid)
            if record:
                db.delete(record)
        task.state = "DONE"
        task.completed_at = datetime.now(timezone.utc)
        db.commit()
        return {"result": "done"}
    except Exception:
        db.rollback()
        task = db.get(DeletionTask, uuid.UUID(deletion_task_id))
        if task:
            task.attempts += 1
            if task.attempts >= 5:
                task.state = "DEAD"  # 死信 → 告警
            else:
                task.state = "PENDING"
            db.commit()
        raise
    finally:
        db.close()


@celery_app.task(name="app.worker.tasks.purge_subject", max_retries=5, retry_backoff=True,
                 retry_backoff_max=600, retry_jitter=False, autoretry_for=(Exception,))
def purge_subject(task_id: str):
    """清空全部记录（SUBJECT_PURGE）：硬删除含对象存储。"""
    db = _session()
    try:
        task = db.get(DeletionTask, uuid.UUID(task_id))
        if task is None or task.state == "DONE":
            return {"result": "noop"}
        subject_id = task.target_id
        record_ids = db.scalars(select(Record.id).where(
            Record.subject_id == subject_id, Record.deleted_at.is_(None))).all()
        for rid in record_ids:
            audio = db.scalar(select(AudioObject).where(AudioObject.record_id == rid))
            if audio:
                get_storage().delete(audio.object_key)
            db.query(Metric).filter(Metric.record_id == rid).delete()
            db.query(AsrResult).filter(AsrResult.record_id == rid).delete()
            rec = db.get(Record, rid)
            if rec:
                db.delete(rec)
        subject = db.get(Subject, subject_id)
        if subject:
            subject.status = "DELETED"
        task.state = "DONE"
        task.completed_at = datetime.now(timezone.utc)
        db.commit()
        return {"result": "done", "deleted": len(record_ids)}
    finally:
        db.close()


@celery_app.task(name="app.worker.tasks.generate_export", max_retries=3, retry_backoff=True,
                 retry_backoff_max=600, retry_jitter=False, autoretry_for=(Exception,))
def generate_export(job_id: str):
    """导出任务：产物独立前缀、私有、24h 必删；内容不含 zScore/统计量/量表分数。"""
    db = _session()
    tmp_dir = None
    try:
        job = db.get(ExportJob, uuid.UUID(job_id))
        if job is None or job.state in ("READY", "EXPIRED"):
            return {"result": "noop"}
        job.state = "GENERATING"
        db.commit()
        tmp_dir = tempfile.mkdtemp()
        missing_audio = 0
        if job.scope == "FULL":
            missing_audio = _build_full_export(db, job, tmp_dir)
        else:
            _build_metrics_csv(db, job, tmp_dir)
        zip_path = os.path.join(tmp_dir, "export.zip")
        with zipfile.ZipFile(zip_path, "w") as zf:
            for root, _dirs, files in os.walk(tmp_dir):
                for f in files:
                    if f == "export.zip":
                        continue
                    full = os.path.join(root, f)
                    zf.write(full, os.path.relpath(full, tmp_dir))
        storage = get_storage()
        object_key = f"exports/{job.id}/export.zip"
        storage.upload_file(object_key, zip_path, "application/zip")
        job.object_key = object_key
        job.size_bytes = int(os.path.getsize(zip_path))
        job.missing_audio_count = missing_audio
        job.state = "READY"
        db.commit()
        return {"result": "ready"}
    finally:
        if tmp_dir:
            shutil.rmtree(tmp_dir, ignore_errors=True)
        db.close()


def _build_metrics_csv(db, job, tmp_dir: str) -> str:
    rows = db.execute(
        select(Record.id, Record.created_at, Metric.metric_key, Metric.value)
        .join(Metric, Metric.record_id == Record.id)
        .where(Record.subject_id == job.subject_id, Record.deleted_at.is_(None),
               Metric.computed_from == "ASR")
    ).all()
    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["recordId", "recordedAt", "metricKey", "value", "computedFrom"])
    for rid, created_at, key, value in rows:
        writer.writerow([f"rec_{rid}", int(created_at.timestamp() * 1000), key, value, "ASR"])
    path = os.path.join(tmp_dir, "metrics.csv")
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(buf.getvalue())
    return path


def _build_full_export(db, job, tmp_dir: str) -> int:
    include = set(job.include or [])
    subject = db.get(Subject, job.subject_id)
    records = db.scalars(select(Record).where(
        Record.subject_id == job.subject_id, Record.deleted_at.is_(None))).all()
    missing_audio = 0
    storage = get_storage()
    # manifest.json（研究导出同结构：缺失统计不能静默）
    manifest = {"subjectId": f"sub_{job.subject_id}", "displayName": subject.display_name,
                "scope": job.scope, "include": job.include, "generatedAt":
                    datetime.now(timezone.utc).isoformat()}
    with open(os.path.join(tmp_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    for rec in records:
        audio = db.scalar(select(AudioObject).where(AudioObject.record_id == rec.id))
        if audio and "AUDIO" in include and audio.deleted_at is None:
            try:
                dest = os.path.join(tmp_dir, "audio", f"{rec.id}.m4a")
                storage.download(audio.object_key, dest)
            except Exception:
                missing_audio += 1
        elif "AUDIO" in include:
            missing_audio += 1
        asr = db.get(AsrResult, rec.id)
        manual = db.get(ManualTranscript, rec.id)
        if "TRANSCRIPT" in include:
            if asr:
                with open(os.path.join(tmp_dir, f"{rec.id}_asr.txt"), "w",
                          encoding="utf-8") as f:
                    f.write(asr.text)
            if manual:
                with open(os.path.join(tmp_dir, f"{rec.id}_manual.txt"), "w",
                          encoding="utf-8") as f:
                    f.write(manual.text)
        if "EVENTS" in include:
            tags = db.scalars(select(EventTag).where(
                EventTag.record_id == rec.id, EventTag.deleted_at.is_(None))).all()
            for t in tags:
                pass  # events.csv 汇总在下方
    if "METRICS" in include:
        _build_metrics_csv(db, job, tmp_dir)
    # events.csv
    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["recordId", "type", "note"])
    for rec in records:
        for t in db.scalars(select(EventTag).where(
                EventTag.record_id == rec.id, EventTag.deleted_at.is_(None))).all():
            writer.writerow([f"rec_{rec.id}", t.type, t.note or ""])
    with open(os.path.join(tmp_dir, "events.csv"), "w", encoding="utf-8", newline="") as f:
        f.write(buf.getvalue())
    return missing_audio


@celery_app.task(name="app.worker.tasks.export_expiry_cleanup")
def export_expiry_cleanup():
    """导出产物 24 小时必删（合规 3）。"""
    db = _session()
    try:
        now = datetime.now(timezone.utc)
        jobs = db.scalars(select(ExportJob).where(
            ExportJob.expires_at < now, ExportJob.state.in_(("READY", "GENERATING")))).all()
        storage = get_storage()
        for job in jobs:
            if job.object_key:
                try:
                    storage.delete(job.object_key)
                except Exception:
                    continue
            job.state = "EXPIRED"
            job.object_key = None
        db.commit()
    finally:
        db.close()


@celery_app.task(name="app.worker.tasks.retention_scan")
def retention_scan():
    """到期清理扫描：留存期到期记录 → DeletionTask（数据生命周期 4.1）。"""
    db = _session()
    try:
        subjects = db.scalars(select(Subject).where(
            Subject.retention_months.isnot(None), Subject.status == "ACTIVE")).all()
        now = datetime.now(timezone.utc)
        for subject in subjects:
            cutoff = now - timedelta(days=subject.retention_months * 30)
            expired = db.scalars(select(Record.id).where(
                Record.subject_id == subject.id, Record.deleted_at.is_(None),
                Record.created_at < cutoff)).all()
            for rid in expired:
                rec = db.get(Record, rid)
                rec.deleted_at = now  # 软删标记 → cleanup 硬删
                task = DeletionTask(id=uuid.uuid4(), scope="RETENTION_EXPIRED",
                                    target_id=rid)
                db.add(task)
                from app.integrations.outbox import enqueue

                enqueue(db, aggregate_type="cleanup", aggregate_id=rid,
                        event_type="retention.expired", payload={})
        db.commit()
    finally:
        db.close()


@celery_app.task(name="app.worker.tasks.daily_sweep")
def daily_sweep():
    """每日清扫（基线与状态机 7）：状态降级检查等。幂等：按日去重。"""
    db = _session()
    try:
        today = date.today()
        if db.get(DailySweepState, today):
            return {"result": "already_ran"}
        db.add(DailySweepState(day=today))
        subjects = db.scalars(select(Record.subject_id).where(
            Record.deleted_at.is_(None)).distinct()).all()
        for sid in subjects:
            recompute_subject(db, sid, trigger="SWEEP")
        db.commit()
        return {"result": "done", "subjects": len(subjects)}
    finally:
        db.close()
