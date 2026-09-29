# backend —— 服务端

**一期实现已落地（2026-09-28）。** 技术栈按 C2 选型：**Python FastAPI 单体 + Celery worker + PostgreSQL + Redis + 境内对象存储（抽象层）**。

---

## 1. 目录结构

```
backend/
├── requirements.txt          # 依赖清单（版本锁定范围）
├── docker-compose.yml        # postgres + redis + api + worker + beat
├── Dockerfile
├── .env.example              # 配置模板（密钥不入仓）
├── alembic/                  # 迁移（0001_initial：32 张表，按 docs/backend/schema.md）
├── gen_openapi.py            # 生成 docs/api/openapi/openapi.yaml（生成物，禁手改）
├── tests/
│   └── smoke_e2e.py          # 端到端冒烟测试（40 项检查，含合规契约扫描）
└── app/
    ├── main.py               # FastAPI 入口（48 条 /v1 路由、错误模型、限流、安全头）
    ├── config.py             # 全部配置走环境变量（APP_ 前缀）
    ├── core/                 # 横切：鉴权/权限/幂等/限流/审计/分页/加密/UUIDv7
    ├── db/models.py          # SQLAlchemy 模型（schema.md 的物理落地）
    ├── domain/status_machine.py  # 基线重算 + S0–S6 状态机（纯函数）
    ├── integrations/         # 对象存储抽象 / ASR 抽象 / 指标计算 / 发件箱
    ├── modules/              # 7 个接口域（controller 不写业务逻辑）
    │   ├── auth/             # API-01 认证、老人、绑定、选人会话
    │   ├── consents/         # API-01 同意管理
    │   ├── records/          # API-02 上传登记 + API-04 记录、事件、提醒
    │   ├── metrics/          # API-03 指标序列、基线、状态
    │   ├── lifecycle/        # API-05 导出、留存、清空、注销、审计查询
    │   ├── research/         # API-06 研究模式（默认关闭全域 404）
    │   ├── notifications/    # API-07 通知、推送令牌
    │   └── devstorage.py     # 开发用本地对象存储直传端点（生产不注册）
    └── worker/               # Celery：分析流水线、清理、导出、发件箱发布、每日清扫
```

## 2. 运行

```powershell
# 1. 起依赖并迁移
docker compose up -d postgres redis
$env:APP_DATABASE_URL = "postgresql+psycopg://cogni:cogni@localhost:5432/cogni"
python -m alembic upgrade head

# 2. 起 API（开发；验证码打印在日志，Mock ASR / 本地对象存储）
python -m uvicorn app.main:app --port 8000

# 3. 起 worker + beat（队列 / 定时任务）
celery -A app.worker.celery_app.celery_app worker -Q analysis,cleanup,notify -l info
celery -A app.worker.celery_app.celery_app beat -l info

# 4. 端到端冒烟（起服务后）
python tests\smoke_e2e.py
```

生产部署：`docker compose up -d --build`（先复制 `.env.example` 为 `.env` 填入真实密钥与境内 OSS/COS、ASR 配置）。

## 3. 硬约束的落地位置（写代码时逐条对照）

| # | 约束 | 落点 |
|---|---|---|
| 1 | 不返回 `zScore`/`mean`/`stdDev`，偏离用 `zBand` 分档 | `domain/status_machine.py`（z 仅内部）+ 契约扫描（smoke 10.x、gen_openapi.py） |
| 2 | 不生成临床判断/风险分级 | 无此类接口；`/v1/medical/*` 不存在 |
| 3 | S4/S5 缺非疾病解释 → 500 | `modules/metrics/router.py` `_status_view` 守卫 |
| 4 | ASR 与对象存储境内 | `config.py` + `integrations/asr.py`、`storage.py`（白名单配置） |
| 5 | 删除=硬删除（含对象存储） | `worker/tasks.py` `cleanup` / `purge_subject` |
| 6 | 越权 404（同意未授予 403） | `core/deps.py`（统一校验，接口不手写） |
| 7 | 运营管理员无音频读权限 | `audit` + 存储层签名仅对绑定账号签发 |
| 8 | 幂等：唯一约束兜底 + 发件箱 | `core/idempotency.py`、`db/models.py`、`worker/tasks.py` `publish_outbox` |

## 4. 待办（下一步）

1. **C3 ASR 选型**：选定厂商后只换 `integrations/asr.py` 的 adapter（流水线代码不动）
2. 邮件通道：接入阿里云邮件推送/腾讯云 SES（现开发模式验证码打日志）
3. `audit_logs` 按月分区与独立运维身份（schema.md 第 9 节未决项）
4. CI 接入 openapi 重生成比对 + 契约扫描（docs/api/openapi/README.md 第 4 节）
5. 推送通道（华为/小米等）——`device_tokens` 已就绪，通知先落库
