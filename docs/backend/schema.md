# 数据库 Schema 设计

**版本**：v1.0（2026-09-28）
**数据库**：PostgreSQL 16；ORM = SQLAlchemy 2.0；迁移 = Alembic（README 8.1）
**依据**：本文件是 `PROJECT_REQUIREMENTS.md` 第 7 章（数据对象）与 `modules/` 六个模块 + 通知与推送模块的**物理落地**。所有约束均标注了来源决策，改设计先改源头。

---

## 0. 全局约定

| # | 约定 | 说明 |
|---|---|---|
| 1 | 主键一律 `UUID v7`（时间有序，索引友好），文本形式 `acc_01H...` 加业务前缀仅在 API 层 | DB 内裸 UUID |
| 2 | 时间戳一律 `timestamptz`（UTC） | 毫秒时间戳只在 API 层转换 |
| 3 | 软删除字段统一 `deleted_at timestamptz null`；**软删除只是清理任务的标记，终态是硬删除**（数据生命周期 1.2） | |
| 4 | 枚举一律 `text` + CHECK 约束（不用 PG 原生 enum，迁移方便） | |
| 5 | **审计表与业务角色隔离**：应用数据库角色对 `audit_logs` 无 `UPDATE/DELETE` 权限（数据生命周期 11"不可被业务代码修改"的落地方案） | |
| 6 | 敏感字段加密用 **pgcrypto / 应用层 AES-GCM**（邮箱原文、pushToken）；哈希用 HMAC-SHA256（服务端密钥） | 手机号方案平移到邮箱 |
| 7 | 所有幂等靠**唯一约束兜底**，不用应用层"先查再插" | 并发安全 |
| 8 | 迁移纪律：每次变更一个 Alembic revision；**禁止手改线上库** | README 8.1 |

---

## 1. 账号与身份

```sql
-- 账号（家属账号 = 唯一账号类型；决策 #18）
CREATE TABLE accounts (
    id              uuid PRIMARY KEY,
    username        text NOT NULL UNIQUE,
    email_encrypted bytea NOT NULL,            -- 应用层 AES-GCM
    email_hash      bytea NOT NULL UNIQUE,     -- HMAC-SHA256，登录匹配（API-01 1.2）
    password_hash   text NOT NULL,             -- Argon2id
    role            text NOT NULL DEFAULT 'FAMILY'
                    CHECK (role IN ('FAMILY','RESEARCHER','OPS_ADMIN')),  -- 4.2a，运维 DB 提权
    status          text NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','DELETING','DELETED')),    -- 3.1 注销状态机
    cooling_off_until timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- 会话（refresh token 有状态；README 8.6；多会话语义 3.4）
CREATE TABLE sessions (
    id                uuid PRIMARY KEY,
    account_id        uuid NOT NULL REFERENCES accounts(id),
    refresh_token_hash bytea NOT NULL UNIQUE,
    device_id         text NOT NULL,
    client            text NOT NULL CHECK (client IN ('ELDER_APP','FAMILY_APP')),
    locked_subject_id uuid REFERENCES subjects(id),   -- 老人端会话锁定（3.3/3.4），家属端为 NULL
    created_at        timestamptz NOT NULL DEFAULT now(),
    last_active_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at        timestamptz
);
CREATE INDEX idx_sessions_account ON sessions(account_id) WHERE revoked_at IS NULL;
-- 上限 5 的并发控制：登录时 count 活跃会话，超限吊销最久未活跃者（应用层逻辑 + 上表索引支撑）

-- 老人（Subject）
CREATE TABLE subjects (
    id                  uuid PRIMARY KEY,
    elder_name          text NOT NULL,          -- 匹配键，创建后不可改（2.1②）
    display_name        text NOT NULL,          -- 展示名，可改（API-01 1.10）
    bind_code_hash      bytea NOT NULL,         -- 系统生成 8 位强码，哈希存储，仅创建时返回一次
    age_band            text NOT NULL CHECK (age_band IN ('60-64','65-69','70-74','75-79','80-84','85+')),
    dialect             text,
    hearing_issue       boolean NOT NULL DEFAULT false,
    retention_months    int CHECK (retention_months IN (6,12,24) OR retention_months IS NULL),  -- NULL=永久
    created_by_account_id uuid NOT NULL REFERENCES accounts(id),  -- 仅审计用，无特殊权限
    status              text NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','DELETING','DELETED')),
    purge_requested_at  timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (elder_name, bind_code_hash)         -- 同名同码 = 同一位老人（决策 #18，并发绑定只产一个 Subject）
);

-- 绑定关系（CareLink；替代原 Binding）
CREATE TABLE care_links (
    id                uuid PRIMARY KEY,
    account_id        uuid NOT NULL REFERENCES accounts(id),
    subject_id        uuid NOT NULL REFERENCES subjects(id),
    relation_display  text NOT NULL CHECK (relation_display IN ('儿子','女儿')),  -- 绑定时按账号 gender 派生快照
    created_at        timestamptz NOT NULL DEFAULT now(),
    revoked_at        timestamptz,
    UNIQUE (account_id, subject_id, revoked_at)  -- 同一账号对同一老人只有一个有效绑定（partial 见下）
);
CREATE UNIQUE INDEX uq_care_links_active ON care_links(account_id, subject_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_care_links_subject ON care_links(subject_id) WHERE revoked_at IS NULL;  -- "谁还绑着这位老人"（注销处置用）

-- 同意（2026-09-28 用户决策：全部挂账号；账号与身份 2.2）
CREATE TABLE consents (
    id          uuid PRIMARY KEY,
    account_id  uuid NOT NULL REFERENCES accounts(id),
    type        text NOT NULL CHECK (type IN ('PRODUCT','SENSITIVE','ELDER_INFORMED','RESEARCH')),
    version     text NOT NULL,
    granted_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    evidence    jsonb NOT NULL              -- 时间、设备、IP 摘要（不存原文）
);
CREATE UNIQUE INDEX uq_consents_active ON consents(account_id, type) WHERE revoked_at IS NULL;
-- 授予校验：访问敏感数据 = 查询请求账号 SENSITIVE 是否有 revoked_at IS NULL 的行（缓存 1 分钟，撤回主动失效）
```

> **验证码不进库**：邮箱验证码存 Redis（`email_code:{scene}:{emailHash}`，TTL 5 分钟，一次性核销），失败次数同 Redis 计数。库表里不留验证码。

---

## 2. 音频存储与上传 / 分析流水线

```sql
-- 预签名上传会话
CREATE TABLE upload_sessions (
    id          uuid PRIMARY KEY,
    subject_id  uuid NOT NULL REFERENCES subjects(id),
    object_key  text NOT NULL UNIQUE,           -- 服务端生成，含 subjectId 前缀
    expires_at  timestamptz NOT NULL,           -- ≤15 分钟
    consumed_at timestamptz,                    -- 单次使用（API-02 2.2 校验）
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- 录音记录
CREATE TABLE records (
    id               uuid PRIMARY KEY,
    subject_id       uuid NOT NULL REFERENCES subjects(id),
    recorder_role    text NOT NULL CHECK (recorder_role IN ('ELDER_SESSION','FAMILY_PROXY')),  -- 服务端按会话类型判定
    task_type        text NOT NULL CHECK (task_type IN ('DAILY_FREE','WEEKLY_PICTURE','WEEKLY_FLUENCY','WEEKLY_FREE')),
    scheduled_at     timestamptz,
    actual_started_at timestamptz NOT NULL,
    duration_sec     int NOT NULL,
    sync_state       text NOT NULL DEFAULT 'SYNCED' CHECK (sync_state IN ('SYNCED','FAILED')),  -- LOCAL_ONLY/UPLOADING 是客户端状态，服务端不感知
    analysis_state   text NOT NULL DEFAULT 'PENDING'
                     CHECK (analysis_state IN ('PENDING','ANALYZING','DONE','FAILED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz               -- 软删标记 → DeletionTask 硬删
);
CREATE INDEX idx_records_subject_time ON records(subject_id, created_at DESC) WHERE deleted_at IS NULL;

-- 音频对象（1:1 record）
CREATE TABLE audio_objects (
    id            uuid PRIMARY KEY,
    record_id     uuid NOT NULL UNIQUE REFERENCES records(id),
    object_key    text NOT NULL UNIQUE,         -- {subjectId}/{yyyy}/{mm}/{uuid}.m4a
    size_bytes    bigint NOT NULL CHECK (size_bytes <= 10485760),
    sha256        bytea NOT NULL,
    content_type  text NOT NULL,
    sample_rate   int NOT NULL,
    storage_class text NOT NULL DEFAULT 'STANDARD' CHECK (storage_class IN ('STANDARD','ARCHIVE')),
    uploaded_at   timestamptz NOT NULL,
    deleted_at    timestamptz                   -- 对象本体由 cleanup 队列删，DB 行随后硬删
);

-- 分析任务（1:1 record —— recordId 幂等的物理保证）
CREATE TABLE analysis_jobs (
    id                uuid PRIMARY KEY,
    record_id         uuid NOT NULL UNIQUE REFERENCES records(id),
    subject_id        uuid NOT NULL REFERENCES subjects(id),
    state             text NOT NULL DEFAULT 'PENDING'
                      CHECK (state IN ('PENDING','ANALYZING','DONE','FAILED')),
    attempts          int NOT NULL DEFAULT 0,
    asr_engine        text,
    asr_model_version text,
    dsp_lib_version   text,                     -- 口径追溯（指标口径字典 1）
    started_at        timestamptz,
    finished_at       timestamptz,
    failure_reason    text
);

-- ASR 结果（1:1 record；不缓存进 Redis 的持久层副本）
CREATE TABLE asr_results (
    record_id   uuid PRIMARY KEY REFERENCES records(id),
    text        text NOT NULL,                  -- 日志禁写此字段
    confidence  real NOT NULL,
    segments    jsonb NOT NULL,
    dialect     text,
    engine      text NOT NULL,
    model_version text NOT NULL
);

-- 指标（声学 + 文本统一存；指标口径字典）
CREATE TABLE metrics (
    id            uuid PRIMARY KEY,
    record_id     uuid NOT NULL REFERENCES records(id),
    metric_key    text NOT NULL CHECK (metric_key IN ('SPEECH_RATE','PAUSE','VOCAB','COHERENCE')),
    value         double precision,             -- NULL = 该口径下不可用（如 ASR 失败时的文本指标）
    computed_from text NOT NULL CHECK (computed_from IN ('ASR','MANUAL')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (record_id, metric_key, computed_from)   -- 重复消费幂等的兜底（分析流水线 6.1）
);
CREATE INDEX idx_metrics_record ON metrics(record_id);

-- 质量门控结果（1:1 record）
CREATE TABLE quality_gate_results (
    record_id      uuid PRIMARY KEY REFERENCES records(id),
    snr_db         double precision,
    silence_ratio  double precision,
    duration_sec   int,
    asr_confidence real,
    flag           text NOT NULL CHECK (flag IN ('VALID','SUSPECT','INVALID')),
    reasons        jsonb NOT NULL DEFAULT '[]'
);

-- 事件标注
CREATE TABLE event_tags (
    id         uuid PRIMARY KEY,
    record_id  uuid NOT NULL REFERENCES records(id),
    type       text NOT NULL CHECK (type IN ('COLD','MOVE','HOSPITAL','TRAVEL','OTHER')),
    note       text,
    created_by uuid NOT NULL REFERENCES accounts(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);
```

---

## 3. 基线与状态机

```sql
-- 个人基线（按 taskType 分组的分组键放哪？——放 metrics 的聚合查询侧：
--   基线只存计算结果；"按 taskType 分组"在重算任务的查询里 GROUP BY，见下方 task_type 维度说明）
CREATE TABLE baselines (
    id             uuid PRIMARY KEY,
    subject_id     uuid NOT NULL REFERENCES subjects(id),
    task_type      text NOT NULL CHECK (task_type IN ('DAILY_FREE','WEEKLY_PICTURE','WEEKLY_FLUENCY','WEEKLY_FREE')),  -- 3.2 任务同质比较
    metric_key     text NOT NULL CHECK (metric_key IN ('SPEECH_RATE','PAUSE','VOCAB','COHERENCE')),
    computed_from  text NOT NULL CHECK (computed_from IN ('ASR','MANUAL')),   -- 两种口径分开建基线（口径字典 4）
    window_start   timestamptz NOT NULL,
    window_end     timestamptz NOT NULL,
    mean           double precision NOT NULL,
    std_dev        double precision NOT NULL,
    sample_count   int NOT NULL,
    is_established boolean NOT NULL DEFAULT false,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (subject_id, task_type, metric_key, computed_from)   -- 重算覆盖，不追加
);

-- 状态快照（每老人当前态，1 行）+ 状态流转历史（追加）
CREATE TABLE status_snapshots (
    subject_id         uuid PRIMARY KEY REFERENCES subjects(id),
    status_code        text NOT NULL CHECK (status_code IN ('S0','S1','S2','S3','S4','S5','S6')),
    copy_key           text NOT NULL,
    non_disease_key    text,                    -- S4/S5 必填（4.4 守卫：缺失 → 500 + 告警，DB 层再加 CHECK：
                                               --   CHECK (status_code NOT IN ('S4','S5') OR non_disease_key IS NOT NULL)）
    valid_record_count int NOT NULL,
    since              timestamptz NOT NULL,
    computed_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE status_transitions (               -- 审计用（基线与状态机 9：状态变更留痕，不含数值）
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id  uuid NOT NULL REFERENCES subjects(id),
    from_code   text,
    to_code     text NOT NULL,
    trigger     text NOT NULL,                  -- RECOMPUTE | MANUAL_S6 | SWEEP
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_status_transitions_subject ON status_transitions(subject_id, occurred_at DESC);
```

> **基线按 taskType 分组**（基线与状态机 3.2 的物理落地）：`baselines` 带 `task_type` 维度；重算时对 `metrics ⨝ records` 按 `(task_type, metric_key, computed_from)` 分组聚合。接口按 metricKey+taskType 分别返回 `isEstablished`。

---

## 4. 数据生命周期与合规

```sql
-- 删除任务
CREATE TABLE deletion_tasks (
    id           uuid PRIMARY KEY,
    scope        text NOT NULL CHECK (scope IN ('RECORD','SUBJECT_PURGE','ACCOUNT_DELETE','RETENTION_EXPIRED')),
    target_id    uuid NOT NULL,                 -- recordId / subjectId / accountId
    state        text NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','RUNNING','DONE','FAILED','DEAD')),
    attempts     int NOT NULL DEFAULT 0,
    requested_by uuid,
    requested_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

-- 导出任务
CREATE TABLE export_jobs (
    id                  uuid PRIMARY KEY,
    subject_id          uuid NOT NULL REFERENCES subjects(id),
    scope               text NOT NULL CHECK (scope IN ('FULL','METRICS_ONLY')),   -- SUMMARY 已删（B3）
    state               text NOT NULL DEFAULT 'PENDING'
                        CHECK (state IN ('PENDING','GENERATING','READY','FAILED','EXPIRED')),
    object_key          text,                   -- 产物独立前缀、私有、24h 必删
    missing_audio_count int,
    requested_by        uuid NOT NULL REFERENCES accounts(id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL
);

-- 事务性发件箱（README 8.2；所有入队必须走这里，禁止事务内 task.delay）
CREATE TABLE outbox (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type text NOT NULL,               -- analysis | cleanup | export | purge | notify
    aggregate_id   uuid NOT NULL,
    event_type     text NOT NULL,
    payload        jsonb NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;

-- 幂等键（API 层 Idempotency-Key 的存储；TTL 24h 由 cleanup 删）
CREATE TABLE idempotency_keys (
    key           text PRIMARY KEY,             -- 客户端提供的 Idempotency-Key（scope 前缀化：如 "records:{uuid}"）
    account_id    uuid NOT NULL REFERENCES accounts(id),
    request_hash  bytea NOT NULL,               -- 请求体哈希：同 key 不同 body → 409 CONFLICT
    response_body jsonb NOT NULL,               -- 首次响应体，重放用
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL
);

-- 审计日志（应用角色无 UPDATE/DELETE 权限 —— 全局约定 5；保留 12 个月）
CREATE TABLE audit_logs (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    uuid,                           -- 系统身份（SYSTEM）时为 NULL
    actor_role  text,
    action      text NOT NULL CHECK (action IN ('AUDIO_READ','EXPORT','CONSENT_CHANGE','DELETE',
                                                'ADMIN_ACCESS','BIND_CHANGE','ROLE_CHANGE','AUTH_EVENT')),
    target_type text NOT NULL,
    target_id   uuid,
    ip_digest   bytea,
    purpose     text,                           -- 仅 ADMIN_ACCESS 必填（CHECK 保证）
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CHECK (action != 'ADMIN_ACCESS' OR purpose IS NOT NULL)
);
CREATE INDEX idx_audit_actor ON audit_logs(actor_id, occurred_at DESC);      -- API-05 5.10 本人查询
CREATE INDEX idx_audit_target ON audit_logs(target_type, target_id, occurred_at DESC);
-- 12 个月到期清理：pg_partman 按月分区（一期可退化为定时 DELETE，但必须用独立运维身份执行）
```

---

## 5. 通知与推送

```sql
-- 通知
CREATE TABLE notifications (
    id            uuid PRIMARY KEY,
    account_id    uuid NOT NULL REFERENCES accounts(id),
    subject_id    uuid REFERENCES subjects(id),
    type          text NOT NULL CHECK (type IN ('STATUS_CHANGED','RECORD_DONE','REMINDER')),
    copy_key      text NOT NULL,                -- 只发 key + 占位符，无数值（红线，通知与推送 4）
    params        jsonb NOT NULL DEFAULT '{}',  -- {elderName} 等
    dedupe_key    text NOT NULL UNIQUE,         -- "{type}:{subjectId}:{batchId}"——重复入队不重推
    created_at    timestamptz NOT NULL DEFAULT now(),
    read_at       timestamptz
);
CREATE INDEX idx_notifications_account ON notifications(account_id, created_at DESC);

-- 推送令牌
CREATE TABLE device_tokens (
    id                  uuid PRIMARY KEY,
    account_id          uuid NOT NULL REFERENCES accounts(id),
    client_id           text NOT NULL UNIQUE,   -- 同设备重注册覆盖
    channel             text NOT NULL CHECK (channel IN ('HUAWEI','XIAOMI','OPPO','VIVO','FCM','APNS','POLLING')),
    push_token_encrypted bytea NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    revoked_at          timestamptz
);
```

---

## 6. 研究数据管理（默认关闭；与产品数据的隔离在接口层，同库字段级）

```sql
CREATE TABLE research_projects (
    id                      uuid PRIMARY KEY,
    name                    text NOT NULL,
    principal_investigator  text NOT NULL,
    irb_approval_ref        text,
    consent_version         text NOT NULL,
    status                  text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CLOSED'))
);

CREATE TABLE research_subjects (
    id                  uuid PRIMARY KEY,
    research_subject_id text NOT NULL UNIQUE,   -- SUBJ-0001 形式，对外唯一可见
    subject_id          uuid NOT NULL REFERENCES subjects(id),  -- 内部映射，无 HTTP 接口触达
    project_id          uuid NOT NULL REFERENCES research_projects(id),
    enrolled_at         timestamptz NOT NULL DEFAULT now(),
    withdrawn_at        timestamptz
);
CREATE UNIQUE INDEX uq_research_subjects_active ON research_subjects(subject_id) WHERE withdrawn_at IS NULL;

-- 研究同意（研究域内部对象，挂匿名受试者——与产品 Consent 双闸门，研究数据管理 3.2）
CREATE TABLE research_consents (
    id                  uuid PRIMARY KEY,
    research_subject_id uuid NOT NULL REFERENCES research_subjects(id),
    consent_version     text NOT NULL,
    granted_at          timestamptz NOT NULL DEFAULT now(),
    revoked_at          timestamptz,
    evidence            jsonb NOT NULL
);

CREATE TABLE clinical_scales (
    id                  uuid PRIMARY KEY,
    research_subject_id uuid NOT NULL REFERENCES research_subjects(id),
    scale_type          text NOT NULL CHECK (scale_type IN ('MOCA_B','MMSE','FLUENCY')),
    score               numeric NOT NULL,       -- 不进日志（研究数据管理 8）
    assessed_at         timestamptz NOT NULL,
    assessor            text NOT NULL,
    note                text
);

CREATE TABLE task_protocols (
    id           uuid PRIMARY KEY,
    week_index   int NOT NULL,
    task_type    text NOT NULL CHECK (task_type IN ('WEEKLY_PICTURE','WEEKLY_FLUENCY','WEEKLY_FREE')),
    guide_text   text NOT NULL,                 -- 逐字固定
    duration_sec int NOT NULL,
    version      int NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (week_index, task_type, version)     -- 指导语变更必须升版本（研究数据管理 2）
);

CREATE TABLE manual_transcripts (
    record_id            uuid PRIMARY KEY REFERENCES records(id),
    text                 text NOT NULL,
    transcribed_by       text NOT NULL,
    transcribed_at       timestamptz NOT NULL DEFAULT now(),
    inter_rater_agreement numeric
);
-- 人工转录 → 触发文本指标重算（computedFrom = MANUAL），与 ASR 文本并存（API-04 4.4 合规）
```

---

## 7. 实现补充表（2026-09-28 首次实现时新增）

API 契约要求但 v1.0 未列出的三张表，随实现补入（四处联动已完成）：

```sql
-- 提醒设置（API-04 4.7/4.8；currentFrequency / autoReduced 由服务端推导，不入库）
CREATE TABLE reminder_settings (
    subject_id    uuid PRIMARY KEY REFERENCES subjects(id),
    enabled       boolean NOT NULL DEFAULT true,
    reminder_time text NOT NULL DEFAULT '19:30',   -- "HH:MM"
    timezone      text NOT NULL DEFAULT 'Asia/Shanghai',
    target        text NOT NULL DEFAULT 'BOTH'
                  CHECK (target IN ('ELDER_DEVICE','FAMILY','BOTH')),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- 复诊回填（API-05 5.9；doctor_note 是家属记录的医生原话，服务端不做分析）
CREATE TABLE followups (
    id          uuid PRIMARY KEY,
    subject_id  uuid NOT NULL REFERENCES subjects(id),
    treated_at  timestamptz NOT NULL,
    visited     boolean NOT NULL DEFAULT true,
    doctor_note text,
    hospital    text,
    created_by  uuid NOT NULL REFERENCES accounts(id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- dailySweep 幂等标记（基线与状态机 7：每日清扫按日去重）
CREATE TABLE daily_sweep_state (
    day   date PRIMARY KEY,
    ran_at timestamptz NOT NULL DEFAULT now()
);
```

## 8. 与已定决策的对照表（自检用）

| 决策 | schema 落点 |
|---|---|
| #18 老人无账号、家属账号选老人 | `accounts` 单表 + `sessions.locked_subject_id` + `care_links` |
| 绑定码系统生成、同名同码同一人 | `subjects UNIQUE(elder_name, bind_code_hash)` |
| 多家属共看 | `care_links` 多行 + `uq_care_links_active` 部分唯一索引 |
| Consent 全挂账号 | `consents.account_id`（无 subject_id 列） |
| C5 云端指标 | `metrics` 无"客户端来源"；`analysis_jobs.dsp_lib_version` |
| B2 老人端无趋势接口 | schema 无需改动（路由层）；`baselines` 照存 |
| B3 无就诊摘要 | `export_jobs.scope` 枚举只有 FULL/METRICS_ONLY |
| 注销后老人数据按绑定处置 | `idx_care_links_subject` 支撑"还有谁绑着"查询 |
| 审计不可改 | 全局约定 5（应用角色无 UPDATE/DELETE） |
| 事务性发件箱 | `outbox` 表 + 部分索引 |

## 9. 未决项

| 项 | 说明 |
|---|---|
| 分区策略 | `audit_logs`、`status_transitions`、`records`（数据量大后）按月分区；一期量小可先不分，Alembic 预留 |
| `metrics.value` 的 `computed_from` 与基线分组 | 若论文要求"同一记录同一指标保留多口径"，当前 UNIQUE 已支持；确认无需求后可简化 |
| 依从性统计表 | 研究模块的预计算结果存哪（物化视图 vs 汇总表）——研究模式实现时定 |
