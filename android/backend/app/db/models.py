"""SQLAlchemy 模型 —— docs/backend/schema.md v1.0 的物理落地。

约定（schema.md 第 0 节）：
- 主键 UUID v7；对外 ID 前缀只在 API 层
- 时间戳 timestamptz（UTC）
- 软删除 deleted_at 只是清理标记，终态是硬删除
- 枚举 text + CHECK
- 幂等靠唯一约束兜底
"""
import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, BYTEA
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.core.ids import uuid7


def _now() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


def pk() -> Mapped[uuid.UUID]:
    return mapped_column(primary_key=True, default=uuid7)


def ts() -> Mapped[datetime]:
    return mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


def ts_opt() -> Mapped[datetime | None]:
    return mapped_column(DateTime(timezone=True), nullable=True)


# ============================================================
# 1. 账号与身份
# ============================================================

class Account(Base):
    __tablename__ = "accounts"
    id: Mapped[uuid.UUID] = pk()
    username: Mapped[str] = mapped_column(Text, unique=True)
    email_encrypted: Mapped[bytes] = mapped_column(BYTEA)
    email_hash: Mapped[bytes] = mapped_column(BYTEA, unique=True)
    password_hash: Mapped[str] = mapped_column(Text)
    gender: Mapped[str] = mapped_column(String(10))  # MALE | FEMALE → relationDisplay 派生
    role: Mapped[str] = mapped_column(
        Text, CheckConstraint("role IN ('FAMILY','RESEARCHER','OPS_ADMIN')",
                              name="ck_accounts_role"), default="FAMILY",
    )
    status: Mapped[str] = mapped_column(
        Text, CheckConstraint("status IN ('ACTIVE','DELETING','DELETED')",
                              name="ck_accounts_status"), default="ACTIVE",
    )
    cooling_off_until: Mapped[datetime | None] = ts_opt()
    onboarding_completed: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = ts()
    updated_at: Mapped[datetime] = ts()


class Session(Base):
    __tablename__ = "sessions"
    id: Mapped[uuid.UUID] = pk()
    account_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    refresh_token_hash: Mapped[bytes] = mapped_column(BYTEA, unique=True)
    device_id: Mapped[str] = mapped_column(Text)
    client: Mapped[str] = mapped_column(
        Text, CheckConstraint("client IN ('ELDER_APP','FAMILY_APP')", name="ck_sessions_client"),
    )
    locked_subject_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("subjects.id", use_alter=True), nullable=True,
    )
    created_at: Mapped[datetime] = ts()
    last_active_at: Mapped[datetime] = ts()
    revoked_at: Mapped[datetime | None] = ts_opt()


class Subject(Base):
    __tablename__ = "subjects"
    id: Mapped[uuid.UUID] = pk()
    elder_name: Mapped[str] = mapped_column(Text)  # 匹配键，创建后不可改
    display_name: Mapped[str] = mapped_column(Text)
    bind_code_hash: Mapped[bytes] = mapped_column(BYTEA)
    age_band: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "age_band IN ('60-64','65-69','70-74','75-79','80-84','85+')", name="ck_subjects_age"),
    )
    dialect: Mapped[str | None] = mapped_column(Text, nullable=True)
    hearing_issue: Mapped[bool] = mapped_column(Boolean, default=False)
    retention_months: Mapped[int | None] = mapped_column(Integer, nullable=True)  # NULL=永久
    created_by_account_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    status: Mapped[str] = mapped_column(
        Text, CheckConstraint("status IN ('ACTIVE','DELETING','DELETED')",
                              name="ck_subjects_status"), default="ACTIVE",
    )
    purge_requested_at: Mapped[datetime | None] = ts_opt()
    created_at: Mapped[datetime] = ts()
    updated_at: Mapped[datetime] = ts()

    __table_args__ = (
        UniqueConstraint("elder_name", "bind_code_hash", name="uq_subjects_name_code"),
    )


class CareLink(Base):
    __tablename__ = "care_links"
    id: Mapped[uuid.UUID] = pk()
    account_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    relation_display: Mapped[str] = mapped_column(
        Text, CheckConstraint("relation_display IN ('儿子','女儿')", name="ck_care_links_rel"),
    )
    created_at: Mapped[datetime] = ts()
    revoked_at: Mapped[datetime | None] = ts_opt()

    __table_args__ = (
        Index("uq_care_links_active", "account_id", "subject_id", unique=True,
              postgresql_where=text("revoked_at IS NULL")),
        Index("idx_care_links_subject", "subject_id",
              postgresql_where=text("revoked_at IS NULL")),
    )


class Consent(Base):
    __tablename__ = "consents"
    id: Mapped[uuid.UUID] = pk()
    account_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    type: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "type IN ('PRODUCT','SENSITIVE','ELDER_INFORMED','RESEARCH')", name="ck_consents_type"),
    )
    version: Mapped[str] = mapped_column(Text)
    granted_at: Mapped[datetime] = ts()
    revoked_at: Mapped[datetime | None] = ts_opt()
    evidence: Mapped[dict] = mapped_column(JSONB)

    __table_args__ = (
        Index("uq_consents_active", "account_id", "type", unique=True,
              postgresql_where=text("revoked_at IS NULL")),
    )


# ============================================================
# 2. 音频存储与上传 / 分析流水线
# ============================================================

class UploadSession(Base):
    __tablename__ = "upload_sessions"
    id: Mapped[uuid.UUID] = pk()
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    object_key: Mapped[str] = mapped_column(Text, unique=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    consumed_at: Mapped[datetime | None] = ts_opt()
    created_at: Mapped[datetime] = ts()


class Record(Base):
    __tablename__ = "records"
    id: Mapped[uuid.UUID] = pk()
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    recorder_role: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "recorder_role IN ('ELDER_SESSION','FAMILY_PROXY')", name="ck_records_role"),
    )
    task_type: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "task_type IN ('DAILY_FREE','WEEKLY_PICTURE','WEEKLY_FLUENCY','WEEKLY_FREE')",
            name="ck_records_task"),
    )
    scheduled_at: Mapped[datetime | None] = ts_opt()
    actual_started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    duration_sec: Mapped[int] = mapped_column(Integer)
    sync_state: Mapped[str] = mapped_column(
        Text, CheckConstraint("sync_state IN ('SYNCED','FAILED')", name="ck_records_sync"),
        default="SYNCED",
    )
    analysis_state: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "analysis_state IN ('PENDING','ANALYZING','DONE','FAILED')",
            name="ck_records_analysis"), default="PENDING",
    )
    family_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = ts()
    deleted_at: Mapped[datetime | None] = ts_opt()

    __table_args__ = (
        Index("idx_records_subject_time", "subject_id", "created_at",
              postgresql_where=text("deleted_at IS NULL")),
    )


class AudioObject(Base):
    __tablename__ = "audio_objects"
    id: Mapped[uuid.UUID] = pk()
    record_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("records.id"), unique=True)
    object_key: Mapped[str] = mapped_column(Text, unique=True)
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[bytes] = mapped_column(BYTEA)
    content_type: Mapped[str] = mapped_column(Text)
    sample_rate: Mapped[int] = mapped_column(Integer)
    storage_class: Mapped[str] = mapped_column(
        Text, CheckConstraint("storage_class IN ('STANDARD','ARCHIVE')", name="ck_audio_class"),
        default="STANDARD",
    )
    uploaded_at: Mapped[datetime] = ts()
    deleted_at: Mapped[datetime | None] = ts_opt()


class AnalysisJob(Base):
    __tablename__ = "analysis_jobs"
    id: Mapped[uuid.UUID] = pk()
    record_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("records.id"), unique=True)
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    state: Mapped[str] = mapped_column(
        Text, CheckConstraint("state IN ('PENDING','ANALYZING','DONE','FAILED')",
                              name="ck_analysis_state"), default="PENDING",
    )
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    asr_engine: Mapped[str | None] = mapped_column(Text, nullable=True)
    asr_model_version: Mapped[str | None] = mapped_column(Text, nullable=True)
    dsp_lib_version: Mapped[str | None] = mapped_column(Text, nullable=True)
    started_at: Mapped[datetime | None] = ts_opt()
    finished_at: Mapped[datetime | None] = ts_opt()
    failure_reason: Mapped[str | None] = mapped_column(Text, nullable=True)


class AsrResult(Base):
    __tablename__ = "asr_results"
    record_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("records.id"), primary_key=True)
    text: Mapped[str] = mapped_column(Text)  # 日志禁写此字段
    confidence: Mapped[float] = mapped_column(Numeric(5, 4))
    segments: Mapped[list] = mapped_column(JSONB)
    dialect: Mapped[str | None] = mapped_column(Text, nullable=True)
    engine: Mapped[str] = mapped_column(Text)
    model_version: Mapped[str] = mapped_column(Text)


class Metric(Base):
    __tablename__ = "metrics"
    id: Mapped[uuid.UUID] = pk()
    record_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("records.id"))
    metric_key: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "metric_key IN ('SPEECH_RATE','PAUSE','VOCAB','COHERENCE')", name="ck_metrics_key"),
    )
    value: Mapped[float | None] = mapped_column(Numeric(12, 6), nullable=True)
    computed_from: Mapped[str] = mapped_column(
        Text, CheckConstraint("computed_from IN ('ASR','MANUAL')", name="ck_metrics_from"),
    )
    created_at: Mapped[datetime] = ts()

    __table_args__ = (
        UniqueConstraint("record_id", "metric_key", "computed_from", name="uq_metrics_record_key"),
        Index("idx_metrics_record", "record_id"),
    )


class QualityGateResult(Base):
    __tablename__ = "quality_gate_results"
    record_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("records.id"), primary_key=True)
    snr_db: Mapped[float | None] = mapped_column(Numeric(8, 2), nullable=True)
    silence_ratio: Mapped[float | None] = mapped_column(Numeric(6, 4), nullable=True)
    duration_sec: Mapped[int | None] = mapped_column(Integer, nullable=True)
    asr_confidence: Mapped[float | None] = mapped_column(Numeric(5, 4), nullable=True)
    flag: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "flag IN ('VALID','SUSPECT','INVALID')", name="ck_qg_flag"),
    )
    reasons: Mapped[list] = mapped_column(JSONB, default=list)


class EventTag(Base):
    __tablename__ = "event_tags"
    id: Mapped[uuid.UUID] = pk()
    record_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("records.id"))
    type: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "type IN ('COLD','MOVE','HOSPITAL','TRAVEL','OTHER')", name="ck_event_type"),
    )
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_by: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    created_at: Mapped[datetime] = ts()
    deleted_at: Mapped[datetime | None] = ts_opt()


# ============================================================
# 3. 基线与状态机
# ============================================================

class Baseline(Base):
    __tablename__ = "baselines"
    id: Mapped[uuid.UUID] = pk()
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    task_type: Mapped[str] = mapped_column(Text)
    metric_key: Mapped[str] = mapped_column(Text)
    computed_from: Mapped[str] = mapped_column(Text)
    window_start: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    window_end: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    mean: Mapped[float] = mapped_column(Numeric(12, 6))
    std_dev: Mapped[float] = mapped_column(Numeric(12, 6))
    sample_count: Mapped[int] = mapped_column(Integer)
    is_established: Mapped[bool] = mapped_column(Boolean, default=False)
    updated_at: Mapped[datetime] = ts()

    __table_args__ = (
        UniqueConstraint("subject_id", "task_type", "metric_key", "computed_from",
                         name="uq_baselines"),
    )


class StatusSnapshot(Base):
    __tablename__ = "status_snapshots"
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"), primary_key=True)
    status_code: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "status_code IN ('S0','S1','S2','S3','S4','S5','S6')", name="ck_status_code"),
    )
    copy_key: Mapped[str] = mapped_column(Text)
    non_disease_key: Mapped[str | None] = mapped_column(Text, nullable=True)
    valid_record_count: Mapped[int] = mapped_column(Integer)
    since: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    computed_at: Mapped[datetime] = ts()

    __table_args__ = (
        CheckConstraint(
            "status_code NOT IN ('S4','S5') OR non_disease_key IS NOT NULL",
            name="ck_status_nondisease"),
    )


class StatusTransition(Base):
    __tablename__ = "status_transitions"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    from_code: Mapped[str | None] = mapped_column(Text, nullable=True)
    to_code: Mapped[str] = mapped_column(Text)
    trigger: Mapped[str] = mapped_column(Text)  # RECOMPUTE | MANUAL_S6 | SWEEP
    occurred_at: Mapped[datetime] = ts()

    __table_args__ = (
        Index("idx_status_transitions_subject", "subject_id", "occurred_at"),
    )


# ============================================================
# 4. 数据生命周期与合规
# ============================================================

class DeletionTask(Base):
    __tablename__ = "deletion_tasks"
    id: Mapped[uuid.UUID] = pk()
    scope: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "scope IN ('RECORD','SUBJECT_PURGE','ACCOUNT_DELETE','RETENTION_EXPIRED')",
            name="ck_del_scope"),
    )
    target_id: Mapped[uuid.UUID] = mapped_column()
    state: Mapped[str] = mapped_column(
        Text, CheckConstraint("state IN ('PENDING','RUNNING','DONE','FAILED','DEAD')",
                              name="ck_del_state"), default="PENDING",
    )
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    requested_by: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    requested_at: Mapped[datetime] = ts()
    completed_at: Mapped[datetime | None] = ts_opt()


class ExportJob(Base):
    __tablename__ = "export_jobs"
    id: Mapped[uuid.UUID] = pk()
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    scope: Mapped[str] = mapped_column(
        Text, CheckConstraint("scope IN ('FULL','METRICS_ONLY')", name="ck_export_scope"),
    )
    state: Mapped[str] = mapped_column(
        Text, CheckConstraint("state IN ('PENDING','GENERATING','READY','FAILED','EXPIRED')",
                              name="ck_export_state"), default="PENDING",
    )
    object_key: Mapped[str | None] = mapped_column(Text, nullable=True)
    size_bytes: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    missing_audio_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    include: Mapped[list] = mapped_column(JSONB, default=list)
    requested_by: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    created_at: Mapped[datetime] = ts()
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class Outbox(Base):
    __tablename__ = "outbox"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    aggregate_type: Mapped[str] = mapped_column(Text)  # analysis|cleanup|export|purge|notify
    aggregate_id: Mapped[uuid.UUID] = mapped_column()
    event_type: Mapped[str] = mapped_column(Text)
    payload: Mapped[dict] = mapped_column(JSONB)
    created_at: Mapped[datetime] = ts()
    published_at: Mapped[datetime | None] = ts_opt()

    __table_args__ = (
        Index("idx_outbox_unpublished", "created_at",
              postgresql_where=text("published_at IS NULL")),
    )


class IdempotencyKey(Base):
    __tablename__ = "idempotency_keys"
    key: Mapped[str] = mapped_column(Text, primary_key=True)  # scope 前缀化
    account_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    request_hash: Mapped[bytes] = mapped_column(BYTEA)
    response_body: Mapped[dict] = mapped_column(JSONB)
    created_at: Mapped[datetime] = ts()
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class AuditLog(Base):
    __tablename__ = "audit_logs"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    actor_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)  # SYSTEM 时为 NULL
    actor_role: Mapped[str | None] = mapped_column(Text, nullable=True)
    action: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "action IN ('AUDIO_READ','EXPORT','CONSENT_CHANGE','DELETE','ADMIN_ACCESS',"
            "'BIND_CHANGE','ROLE_CHANGE','AUTH_EVENT','STATUS_CHANGE')", name="ck_audit_action"),
    )
    target_type: Mapped[str] = mapped_column(Text)
    target_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)
    ip_digest: Mapped[bytes | None] = mapped_column(BYTEA, nullable=True)
    purpose: Mapped[str | None] = mapped_column(Text, nullable=True)
    occurred_at: Mapped[datetime] = ts()

    __table_args__ = (
        CheckConstraint("action != 'ADMIN_ACCESS' OR purpose IS NOT NULL",
                        name="ck_audit_purpose"),
        Index("idx_audit_actor", "actor_id", "occurred_at"),
        Index("idx_audit_target", "target_type", "target_id", "occurred_at"),
    )


# ============================================================
# 5. 通知与推送
# ============================================================

class Notification(Base):
    __tablename__ = "notifications"
    id: Mapped[uuid.UUID] = pk()
    account_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    subject_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("subjects.id"), nullable=True)
    type: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "type IN ('STATUS_CHANGED','RECORD_DONE','REMINDER')", name="ck_notif_type"),
    )
    copy_key: Mapped[str] = mapped_column(Text)
    params: Mapped[dict] = mapped_column(JSONB, default=dict)
    dedupe_key: Mapped[str] = mapped_column(Text, unique=True)
    created_at: Mapped[datetime] = ts()
    read_at: Mapped[datetime | None] = ts_opt()

    __table_args__ = (
        Index("idx_notifications_account", "account_id", "created_at"),
    )


class DeviceToken(Base):
    __tablename__ = "device_tokens"
    id: Mapped[uuid.UUID] = pk()
    account_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    client_id: Mapped[str] = mapped_column(Text, unique=True)
    channel: Mapped[str] = mapped_column(Text)
    push_token_encrypted: Mapped[bytes] = mapped_column(BYTEA)
    created_at: Mapped[datetime] = ts()
    revoked_at: Mapped[datetime | None] = ts_opt()


# ============================================================
# 6. 研究数据管理
# ============================================================

class ResearchProject(Base):
    __tablename__ = "research_projects"
    id: Mapped[uuid.UUID] = pk()
    name: Mapped[str] = mapped_column(Text)
    principal_investigator: Mapped[str] = mapped_column(Text)
    irb_approval_ref: Mapped[str | None] = mapped_column(Text, nullable=True)
    consent_version: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(
        Text, CheckConstraint("status IN ('ACTIVE','CLOSED')", name="ck_rp_status"),
        default="ACTIVE",
    )


class ResearchSubject(Base):
    __tablename__ = "research_subjects"
    id: Mapped[uuid.UUID] = pk()
    research_subject_id: Mapped[str] = mapped_column(Text, unique=True)  # SUBJ-0001
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    project_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("research_projects.id"))
    enrolled_at: Mapped[datetime] = ts()
    withdrawn_at: Mapped[datetime | None] = ts_opt()

    __table_args__ = (
        Index("uq_research_subjects_active", "subject_id", unique=True,
              postgresql_where=text("withdrawn_at IS NULL")),
    )


class ResearchConsent(Base):
    __tablename__ = "research_consents"
    id: Mapped[uuid.UUID] = pk()
    research_subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("research_subjects.id"))
    consent_version: Mapped[str] = mapped_column(Text)
    granted_at: Mapped[datetime] = ts()
    revoked_at: Mapped[datetime | None] = ts_opt()
    evidence: Mapped[dict] = mapped_column(JSONB)


class ClinicalScale(Base):
    __tablename__ = "clinical_scales"
    id: Mapped[uuid.UUID] = pk()
    research_subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("research_subjects.id"))
    scale_type: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "scale_type IN ('MOCA_B','MMSE','FLUENCY')", name="ck_scale_type"),
    )
    score: Mapped[float] = mapped_column(Numeric(6, 2))  # 不进日志
    assessed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    assessor: Mapped[str] = mapped_column(Text)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)


class TaskProtocol(Base):
    __tablename__ = "task_protocols"
    id: Mapped[uuid.UUID] = pk()
    week_index: Mapped[int] = mapped_column(Integer)
    task_type: Mapped[str] = mapped_column(
        Text, CheckConstraint(
            "task_type IN ('WEEKLY_PICTURE','WEEKLY_FLUENCY','WEEKLY_FREE')",
            name="ck_protocol_task"),
    )
    guide_text: Mapped[str] = mapped_column(Text)
    duration_sec: Mapped[int] = mapped_column(Integer)
    image_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    version: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = ts()

    __table_args__ = (
        UniqueConstraint("week_index", "task_type", "version", name="uq_protocol_version"),
    )


class ManualTranscript(Base):
    __tablename__ = "manual_transcripts"
    record_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("records.id"), primary_key=True)
    text: Mapped[str] = mapped_column(Text)
    transcribed_by: Mapped[str] = mapped_column(Text)
    transcribed_at: Mapped[datetime] = ts()
    inter_rater_agreement: Mapped[float | None] = mapped_column(Numeric(5, 4), nullable=True)


# ============================================================
# 7. schema.md 未列、API 契约要求的补充表（提醒设置 / 复诊回填）
# ============================================================

class ReminderSetting(Base):
    __tablename__ = "reminder_settings"
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"), primary_key=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    reminder_time: Mapped[str] = mapped_column(String(5), default="19:30")  # "HH:MM"
    timezone: Mapped[str] = mapped_column(Text, default="Asia/Shanghai")
    target: Mapped[str] = mapped_column(
        Text, CheckConstraint("target IN ('ELDER_DEVICE','FAMILY','BOTH')",
                              name="ck_reminder_target"), default="BOTH",
    )
    updated_at: Mapped[datetime] = ts()


class Followup(Base):
    __tablename__ = "followups"
    id: Mapped[uuid.UUID] = pk()
    subject_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("subjects.id"))
    treated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    visited: Mapped[bool] = mapped_column(Boolean, default=True)
    doctor_note: Mapped[str | None] = mapped_column(Text, nullable=True)  # 家属记录的医生原话，不做分析
    hospital: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_by: Mapped[uuid.UUID] = mapped_column(ForeignKey("accounts.id"))
    created_at: Mapped[datetime] = ts()


class DailySweepState(Base):
    """dailySweep 幂等标记（按日期只跑一次）。"""
    __tablename__ = "daily_sweep_state"
    day: Mapped[str] = mapped_column(Date, primary_key=True)
    ran_at: Mapped[datetime] = ts()
