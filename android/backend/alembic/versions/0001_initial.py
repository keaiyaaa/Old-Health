"""初始 schema（docs/backend/schema.md v1.0 + 提醒设置/复诊回填补充表）。

Revision ID: 0001_initial
Revises:
Create Date: 2026-09-29
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql as pg

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "accounts",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("username", sa.Text(), nullable=False, unique=True),
        sa.Column("email_encrypted", sa.LargeBinary(), nullable=False),
        sa.Column("email_hash", sa.LargeBinary(), nullable=False, unique=True),
        sa.Column("password_hash", sa.Text(), nullable=False),
        sa.Column("gender", sa.String(10), nullable=False),
        sa.Column("role", sa.Text(), nullable=False, server_default="FAMILY"),
        sa.Column("status", sa.Text(), nullable=False, server_default="ACTIVE"),
        sa.Column("cooling_off_until", sa.DateTime(timezone=True), nullable=True),
        sa.Column("onboarding_completed", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint("role IN ('FAMILY','RESEARCHER','OPS_ADMIN')", name="ck_accounts_role"),
        sa.CheckConstraint("status IN ('ACTIVE','DELETING','DELETED')", name="ck_accounts_status"),
    )
    op.create_table(
        "subjects",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("elder_name", sa.Text(), nullable=False),
        sa.Column("display_name", sa.Text(), nullable=False),
        sa.Column("bind_code_hash", sa.LargeBinary(), nullable=False),
        sa.Column("age_band", sa.Text(), nullable=False),
        sa.Column("dialect", sa.Text(), nullable=True),
        sa.Column("hearing_issue", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("retention_months", sa.Integer(), nullable=True),
        sa.Column("created_by_account_id", pg.UUID(),
                  sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("status", sa.Text(), nullable=False, server_default="ACTIVE"),
        sa.Column("purge_requested_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint(
            "age_band IN ('60-64','65-69','70-74','75-79','80-84','85+')", name="ck_subjects_age"),
        sa.CheckConstraint("status IN ('ACTIVE','DELETING','DELETED')", name="ck_subjects_status"),
        sa.UniqueConstraint("elder_name", "bind_code_hash", name="uq_subjects_name_code"),
    )
    op.create_table(
        "sessions",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("account_id", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("refresh_token_hash", sa.LargeBinary(), nullable=False, unique=True),
        sa.Column("device_id", sa.Text(), nullable=False),
        sa.Column("client", sa.Text(), nullable=False),
        sa.Column("locked_subject_id", pg.UUID(),
                  sa.ForeignKey("subjects.id", use_alter=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("last_active_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("client IN ('ELDER_APP','FAMILY_APP')", name="ck_sessions_client"),
    )
    op.create_index("idx_sessions_account", "sessions", ["account_id"],
                    postgresql_where=sa.text("revoked_at IS NULL"))
    op.create_table(
        "care_links",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("account_id", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("relation_display", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("relation_display IN ('儿子','女儿')", name="ck_care_links_rel"),
    )
    op.create_index("uq_care_links_active", "care_links", ["account_id", "subject_id"],
                    unique=True, postgresql_where=sa.text("revoked_at IS NULL"))
    op.create_index("idx_care_links_subject", "care_links", ["subject_id"],
                    postgresql_where=sa.text("revoked_at IS NULL"))
    op.create_table(
        "consents",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("account_id", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("type", sa.Text(), nullable=False),
        sa.Column("version", sa.Text(), nullable=False),
        sa.Column("granted_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("evidence", pg.JSONB(), nullable=False),
        sa.CheckConstraint(
            "type IN ('PRODUCT','SENSITIVE','ELDER_INFORMED','RESEARCH')", name="ck_consents_type"),
    )
    op.create_index("uq_consents_active", "consents", ["account_id", "type"],
                    unique=True, postgresql_where=sa.text("revoked_at IS NULL"))
    op.create_table(
        "upload_sessions",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("object_key", sa.Text(), nullable=False, unique=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_table(
        "records",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("recorder_role", sa.Text(), nullable=False),
        sa.Column("task_type", sa.Text(), nullable=False),
        sa.Column("scheduled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("actual_started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("duration_sec", sa.Integer(), nullable=False),
        sa.Column("sync_state", sa.Text(), nullable=False, server_default="SYNCED"),
        sa.Column("analysis_state", sa.Text(), nullable=False, server_default="PENDING"),
        sa.Column("family_note", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "recorder_role IN ('ELDER_SESSION','FAMILY_PROXY')", name="ck_records_role"),
        sa.CheckConstraint(
            "task_type IN ('DAILY_FREE','WEEKLY_PICTURE','WEEKLY_FLUENCY','WEEKLY_FREE')",
            name="ck_records_task"),
        sa.CheckConstraint("sync_state IN ('SYNCED','FAILED')", name="ck_records_sync"),
        sa.CheckConstraint(
            "analysis_state IN ('PENDING','ANALYZING','DONE','FAILED')", name="ck_records_analysis"),
    )
    op.create_index("idx_records_subject_time", "records", ["subject_id", "created_at"],
                    postgresql_where=sa.text("deleted_at IS NULL"))
    op.create_table(
        "audio_objects",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("record_id", pg.UUID(), sa.ForeignKey("records.id"), nullable=False, unique=True),
        sa.Column("object_key", sa.Text(), nullable=False, unique=True),
        sa.Column("size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("sha256", sa.LargeBinary(), nullable=False),
        sa.Column("content_type", sa.Text(), nullable=False),
        sa.Column("sample_rate", sa.Integer(), nullable=False),
        sa.Column("storage_class", sa.Text(), nullable=False, server_default="STANDARD"),
        sa.Column("uploaded_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("size_bytes <= 10485760", name="ck_audio_size"),
        sa.CheckConstraint("storage_class IN ('STANDARD','ARCHIVE')", name="ck_audio_class"),
    )
    op.create_table(
        "analysis_jobs",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("record_id", pg.UUID(), sa.ForeignKey("records.id"), nullable=False, unique=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("state", sa.Text(), nullable=False, server_default="PENDING"),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("asr_engine", sa.Text(), nullable=True),
        sa.Column("asr_model_version", sa.Text(), nullable=True),
        sa.Column("dsp_lib_version", sa.Text(), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("failure_reason", sa.Text(), nullable=True),
        sa.CheckConstraint("state IN ('PENDING','ANALYZING','DONE','FAILED')",
                           name="ck_analysis_state"),
    )
    op.create_table(
        "asr_results",
        sa.Column("record_id", pg.UUID(), sa.ForeignKey("records.id"), primary_key=True),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("confidence", sa.Numeric(5, 4), nullable=False),
        sa.Column("segments", pg.JSONB(), nullable=False),
        sa.Column("dialect", sa.Text(), nullable=True),
        sa.Column("engine", sa.Text(), nullable=False),
        sa.Column("model_version", sa.Text(), nullable=False),
    )
    op.create_table(
        "metrics",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("record_id", pg.UUID(), sa.ForeignKey("records.id"), nullable=False),
        sa.Column("metric_key", sa.Text(), nullable=False),
        sa.Column("value", sa.Numeric(12, 6), nullable=True),
        sa.Column("computed_from", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint("metric_key IN ('SPEECH_RATE','PAUSE','VOCAB','COHERENCE')",
                           name="ck_metrics_key"),
        sa.CheckConstraint("computed_from IN ('ASR','MANUAL')", name="ck_metrics_from"),
        sa.UniqueConstraint("record_id", "metric_key", "computed_from", name="uq_metrics_record_key"),
    )
    op.create_index("idx_metrics_record", "metrics", ["record_id"])
    op.create_table(
        "quality_gate_results",
        sa.Column("record_id", pg.UUID(), sa.ForeignKey("records.id"), primary_key=True),
        sa.Column("snr_db", sa.Numeric(8, 2), nullable=True),
        sa.Column("silence_ratio", sa.Numeric(6, 4), nullable=True),
        sa.Column("duration_sec", sa.Integer(), nullable=True),
        sa.Column("asr_confidence", sa.Numeric(5, 4), nullable=True),
        sa.Column("flag", sa.Text(), nullable=False),
        sa.Column("reasons", pg.JSONB(), nullable=False, server_default="[]"),
        sa.CheckConstraint("flag IN ('VALID','SUSPECT','INVALID')", name="ck_qg_flag"),
    )
    op.create_table(
        "event_tags",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("record_id", pg.UUID(), sa.ForeignKey("records.id"), nullable=False),
        sa.Column("type", sa.Text(), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("created_by", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("type IN ('COLD','MOVE','HOSPITAL','TRAVEL','OTHER')",
                           name="ck_event_type"),
    )
    op.create_table(
        "baselines",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("task_type", sa.Text(), nullable=False),
        sa.Column("metric_key", sa.Text(), nullable=False),
        sa.Column("computed_from", sa.Text(), nullable=False),
        sa.Column("window_start", sa.DateTime(timezone=True), nullable=False),
        sa.Column("window_end", sa.DateTime(timezone=True), nullable=False),
        sa.Column("mean", sa.Numeric(12, 6), nullable=False),
        sa.Column("std_dev", sa.Numeric(12, 6), nullable=False),
        sa.Column("sample_count", sa.Integer(), nullable=False),
        sa.Column("is_established", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.UniqueConstraint("subject_id", "task_type", "metric_key", "computed_from",
                            name="uq_baselines"),
    )
    op.create_table(
        "status_snapshots",
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), primary_key=True),
        sa.Column("status_code", sa.Text(), nullable=False),
        sa.Column("copy_key", sa.Text(), nullable=False),
        sa.Column("non_disease_key", sa.Text(), nullable=True),
        sa.Column("valid_record_count", sa.Integer(), nullable=False),
        sa.Column("since", sa.DateTime(timezone=True), nullable=False),
        sa.Column("computed_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint("status_code IN ('S0','S1','S2','S3','S4','S5','S6')",
                           name="ck_status_code"),
        sa.CheckConstraint(
            "status_code NOT IN ('S4','S5') OR non_disease_key IS NOT NULL",
            name="ck_status_nondisease"),
    )
    op.create_table(
        "status_transitions",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("from_code", sa.Text(), nullable=True),
        sa.Column("to_code", sa.Text(), nullable=False),
        sa.Column("trigger", sa.Text(), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("idx_status_transitions_subject", "status_transitions",
                    ["subject_id", "occurred_at"])
    op.create_table(
        "deletion_tasks",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("scope", sa.Text(), nullable=False),
        sa.Column("target_id", pg.UUID(), nullable=False),
        sa.Column("state", sa.Text(), nullable=False, server_default="PENDING"),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("requested_by", pg.UUID(), nullable=True),
        sa.Column("requested_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "scope IN ('RECORD','SUBJECT_PURGE','ACCOUNT_DELETE','RETENTION_EXPIRED')",
            name="ck_del_scope"),
        sa.CheckConstraint("state IN ('PENDING','RUNNING','DONE','FAILED','DEAD')",
                           name="ck_del_state"),
    )
    op.create_table(
        "export_jobs",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("scope", sa.Text(), nullable=False),
        sa.Column("state", sa.Text(), nullable=False, server_default="PENDING"),
        sa.Column("object_key", sa.Text(), nullable=True),
        sa.Column("size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("missing_audio_count", sa.Integer(), nullable=True),
        sa.Column("include", pg.JSONB(), nullable=False, server_default="[]"),
        sa.Column("requested_by", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("scope IN ('FULL','METRICS_ONLY')", name="ck_export_scope"),
        sa.CheckConstraint("state IN ('PENDING','GENERATING','READY','FAILED','EXPIRED')",
                           name="ck_export_state"),
    )
    op.create_table(
        "outbox",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("aggregate_type", sa.Text(), nullable=False),
        sa.Column("aggregate_id", pg.UUID(), nullable=False),
        sa.Column("event_type", sa.Text(), nullable=False),
        sa.Column("payload", pg.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("idx_outbox_unpublished", "outbox", ["created_at"],
                    postgresql_where=sa.text("published_at IS NULL"))
    op.create_table(
        "idempotency_keys",
        sa.Column("key", sa.Text(), primary_key=True),
        sa.Column("account_id", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("request_hash", sa.LargeBinary(), nullable=False),
        sa.Column("response_body", pg.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "audit_logs",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("actor_id", pg.UUID(), nullable=True),
        sa.Column("actor_role", sa.Text(), nullable=True),
        sa.Column("action", sa.Text(), nullable=False),
        sa.Column("target_type", sa.Text(), nullable=False),
        sa.Column("target_id", pg.UUID(), nullable=True),
        sa.Column("ip_digest", sa.LargeBinary(), nullable=True),
        sa.Column("purpose", sa.Text(), nullable=True),
        sa.Column("occurred_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint(
            "action IN ('AUDIO_READ','EXPORT','CONSENT_CHANGE','DELETE','ADMIN_ACCESS',"
            "'BIND_CHANGE','ROLE_CHANGE','AUTH_EVENT','STATUS_CHANGE')", name="ck_audit_action"),
        sa.CheckConstraint("action != 'ADMIN_ACCESS' OR purpose IS NOT NULL",
                           name="ck_audit_purpose"),
    )
    op.create_index("idx_audit_actor", "audit_logs", ["actor_id", "occurred_at"])
    op.create_index("idx_audit_target", "audit_logs", ["target_type", "target_id", "occurred_at"])
    op.create_table(
        "notifications",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("account_id", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("subject_id", pg.UUID(),
                  sa.ForeignKey("subjects.id"), nullable=True),
        sa.Column("type", sa.Text(), nullable=False),
        sa.Column("copy_key", sa.Text(), nullable=False),
        sa.Column("params", pg.JSONB(), nullable=False, server_default="{}"),
        sa.Column("dedupe_key", sa.Text(), nullable=False, unique=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("read_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("type IN ('STATUS_CHANGED','RECORD_DONE','REMINDER')",
                           name="ck_notif_type"),
    )
    op.create_index("idx_notifications_account", "notifications", ["account_id", "created_at"])
    op.create_table(
        "device_tokens",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("account_id", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("client_id", sa.Text(), nullable=False, unique=True),
        sa.Column("channel", sa.Text(), nullable=False),
        sa.Column("push_token_encrypted", sa.LargeBinary(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_table(
        "research_projects",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("principal_investigator", sa.Text(), nullable=False),
        sa.Column("irb_approval_ref", sa.Text(), nullable=True),
        sa.Column("consent_version", sa.Text(), nullable=False),
        sa.Column("status", sa.Text(), nullable=False, server_default="ACTIVE"),
        sa.CheckConstraint("status IN ('ACTIVE','CLOSED')", name="ck_rp_status"),
    )
    op.create_table(
        "research_subjects",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("research_subject_id", sa.Text(), nullable=False, unique=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("project_id", pg.UUID(), sa.ForeignKey("research_projects.id"), nullable=False),
        sa.Column("enrolled_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("withdrawn_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("uq_research_subjects_active", "research_subjects", ["subject_id"],
                    unique=True, postgresql_where=sa.text("withdrawn_at IS NULL"))
    op.create_table(
        "research_consents",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("research_subject_id", pg.UUID(),
                  sa.ForeignKey("research_subjects.id"), nullable=False),
        sa.Column("consent_version", sa.Text(), nullable=False),
        sa.Column("granted_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("evidence", pg.JSONB(), nullable=False),
    )
    op.create_table(
        "clinical_scales",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("research_subject_id", pg.UUID(),
                  sa.ForeignKey("research_subjects.id"), nullable=False),
        sa.Column("scale_type", sa.Text(), nullable=False),
        sa.Column("score", sa.Numeric(6, 2), nullable=False),
        sa.Column("assessed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("assessor", sa.Text(), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
        sa.CheckConstraint("scale_type IN ('MOCA_B','MMSE','FLUENCY')", name="ck_scale_type"),
    )
    op.create_table(
        "task_protocols",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("week_index", sa.Integer(), nullable=False),
        sa.Column("task_type", sa.Text(), nullable=False),
        sa.Column("guide_text", sa.Text(), nullable=False),
        sa.Column("duration_sec", sa.Integer(), nullable=False),
        sa.Column("image_url", sa.Text(), nullable=True),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint(
            "task_type IN ('WEEKLY_PICTURE','WEEKLY_FLUENCY','WEEKLY_FREE')",
            name="ck_protocol_task"),
        sa.UniqueConstraint("week_index", "task_type", "version", name="uq_protocol_version"),
    )
    op.create_table(
        "manual_transcripts",
        sa.Column("record_id", pg.UUID(), sa.ForeignKey("records.id"), primary_key=True),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("transcribed_by", sa.Text(), nullable=False),
        sa.Column("transcribed_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("inter_rater_agreement", sa.Numeric(5, 4), nullable=True),
    )
    op.create_table(
        "reminder_settings",
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), primary_key=True),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("reminder_time", sa.String(5), nullable=False, server_default="19:30"),
        sa.Column("timezone", sa.Text(), nullable=False, server_default="Asia/Shanghai"),
        sa.Column("target", sa.Text(), nullable=False, server_default="BOTH"),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.CheckConstraint("target IN ('ELDER_DEVICE','FAMILY','BOTH')", name="ck_reminder_target"),
    )
    op.create_table(
        "followups",
        sa.Column("id", pg.UUID(), primary_key=True),
        sa.Column("subject_id", pg.UUID(), sa.ForeignKey("subjects.id"), nullable=False),
        sa.Column("treated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("visited", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("doctor_note", sa.Text(), nullable=True),
        sa.Column("hospital", sa.Text(), nullable=True),
        sa.Column("created_by", pg.UUID(), sa.ForeignKey("accounts.id"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_table(
        "daily_sweep_state",
        sa.Column("day", sa.Date(), primary_key=True),
        sa.Column("ran_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )


def downgrade() -> None:
    # 一期不提供自动降级：回滚需手工评估（禁止手改线上库）
    raise NotImplementedError("downgrade not supported; restore from backup")
