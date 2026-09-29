# 家庭认知健康日记

> 给担心父母认知退化的子女用的语音日记 App。记录老人日常说话，看**个人纵向**的语言趋势，出现持续变化时提示"建议就医评估"，**永远不给诊断结论**。

---

## 1. 项目现状

**一期前后端已实现并联通：服务端可运行、客户端可连真实账号体系录音上云，端到端冒烟测试通过。**

| 部分 | 状态 |
|---|---|
| 需求与验收基线 | 已完成（v3.0） |
| 前端 / 后端 / API 设计文档 | 已完成（索引 + 模块文档 + API-01~07 + 字段设计表） |
| 可视化原型 | 已完成（15 屏静态稿，`cognitive-health-diary/`） |
| OpenAPI 机器可读契约 | 已生成（`docs/api/openapi/openapi.yaml`，由后端代码生成，含合规禁字段扫描） |
| 客户端代码 | 一期已实现并**接入真实后端**：真实账号体系（登录 / 注册 / 邮箱验证码 / 改密）、老人端录音直传上云、家属端指标与状态展示；离线构建通过 |
| 服务端代码 | 一期已实现：FastAPI 单体 + Celery worker + PostgreSQL（32 表 Alembic 迁移），覆盖 API-01~07 全部接口域，48 条 `/v1` 路由 |
| 端到端验证 | 冒烟测试 40 项全通过（真实 Docker PostgreSQL + Redis，`android/backend/tests/smoke_e2e.py`） |
| Git 仓库 | 已初始化，远程 `github.com/keaiyaaa/Health`，日常开发分支 `old_psq` |
| 待办 | ASR 厂商选型（当前 Mock）、邮件通道接入、CI、真机验收 |

### 1.1 主要功能

| 分区 | 功能 |
|---|---|
| 老人端 | 一键录音（唯一大按钮）、话题卡、完成反馈、日历回看、设置；录音走预签名 URL 直传对象存储 |
| 家属端 | 总览 Dashboard、四指标时间趋势图（速度 / 流畅度 / 丰富度 / 连贯性，横轴时间）、记录列表与详情、事件标注、添加老人与绑定码、就医准备、数据与隐私 |
| 研究模式 | 匿名受试者、临床量表录入、依从性报表、数据集导出（默认关闭，关闭时全域 404） |

### 1.2 三条不可逾越的设计边界

1. **不做诊断、筛查、风险评估** —— 不输出概率、评分、量表分数
2. **只做个人纵向比较** —— 不做跨人对比、不做常模
3. **老人端零指标、零趋势** —— 只有录音，全部分析结果只在家属端出现

理由见 `docs/planning/合规风险备忘.md`。

---

## 2. 仓库结构

```
认知健康日记/
├── AGENTS.md                          Agent 入口：必读规范、Skill 映射、开发规则、Git 规则
├── .agents/skills/                    Agent Skill 库
├── README.md                          本文件
├── 日常提交指南.md                      日常 git 提交流程（极简版）
│
├── docs/                              全部正式文档
│   ├── PROJECT_REQUIREMENTS.md        需求与验收基线（唯一主文档）
│   ├── frontend/                      前端设计（README + 原型设计说明书 + modules/）
│   ├── backend/                       后端设计（README + schema.md + modules/ + 指标口径字典）
│   ├── api/                           接口唯一事实来源（通则 + API-01~07 + 字段设计表 + openapi/）
│   └── planning/                      过程记录（不作产品事实来源）
│
├── android/                           全部代码，按前后端分两侧
│   ├── frontend/                      Android 客户端（Kotlin + Compose，API 26+）
│   └── backend/                       服务端（Python FastAPI + Celery + PostgreSQL + Redis）
│       ├── app/                       业务代码（core / db / domain / integrations / modules / worker）
│       ├── alembic/                   数据库迁移（0001_initial：32 张表）
│       ├── tests/                     端到端冒烟测试 + 演示数据灌入脚本
│       ├── docker-compose.yml         postgres + redis + api + worker + beat
│       └── requirements.txt
│
├── cognitive-health-diary/            可视化原型（15 屏静态稿）
│
├── 01_需求分析说明书_v2.2.md            已被 docs/PROJECT_REQUIREMENTS.md 取代
└── 02_原型设计说明书_v1.0.md            已被 docs/frontend/原型设计说明书.md 取代
```

---

## 3. 快速开始（开发）

### 3.1 环境准备

| 工具 | 用途 |
|---|---|
| Git | 版本控制（远程：`github.com/keaiyaaa/Old-Health`，分支 `psq`） |
| JDK 17 + Android Studio | 客户端构建与开发 |
| Python 3.12+ | 服务端 |
| Docker Desktop | 本地 PostgreSQL / Redis |

### 3.2 获取代码

```bash
git clone https://github.com/keaiyaaa/Old-Health.git
cd Old-Health
git checkout psq
```

### 3.3 启动后端

```powershell
cd android\backend
cp .env.example .env            # 按需修改；本地开发用默认值即可
docker compose up -d postgres redis
$env:APP_DATABASE_URL = "postgresql+psycopg://cogni:cogni@localhost:5432/cogni"
python -m alembic upgrade head  # 建全部 32 张表
python -m uvicorn app.main:app --port 8000
# 健康检查：http://127.0.0.1:8000/healthz
```

Celery worker 与定时任务（分析流水线 / 清理 / 每日清扫）：

```bash
celery -A app.worker.celery_app.celery_app worker -Q analysis,cleanup,notify -l info
celery -A app.worker.celery_app.celery_app beat -l info
```

### 3.4 客户端构建

```powershell
cd android\frontend
.\gradlew.bat assembleDebug --offline --console=plain
# 产物：app\build\outputs\apk\debug\app-debug.apk
```

App 内后端地址默认 `http://192.168.58.33:8000`（真机联调用，见 `LocalPrefs.kt` 的 `DEFAULT_BASE_URL`）；
模拟器改回 `http://10.0.2.2:8000`。

### 3.5 演示数据与测试

```powershell
# 给测试账号灌 14 天演示数据（走真实上传-分析链路）
python tests\seed_demo.py --username demo --password demo123456 --days 14

# 端到端冒烟（需 uvicorn 已启动；40 项检查含合规禁字段扫描）
$env:APP_DATABASE_URL = "postgresql+psycopg://cogni:cogni@localhost:5432/cogni"
python tests\smoke_e2e.py
python tests\smoke_bind.py
```

### 3.6 测试账号

| 项 | 值 |
|---|---|
| 账号 / 密码 | `demo` / `demo123456`（或 `aaa` / `12345678`） |
| 家属端查看密码 | `000000`（本机本地校验） |
| 老人端 | 同账号登录，登录后自动锁定已绑定的老人 |

---

## 4. 部署

`android/backend/docker-compose.yml` 一键起全套（postgres + redis + api + worker + beat）：

```bash
cp android/backend/.env.example android/backend/.env   # 填入真实密钥与境内 OSS/COS、ASR 配置
cd android/backend
docker compose up -d --build
```

生产环境硬要求（完整清单见 `docs/PROJECT_REQUIREMENTS.md` 11.3）：

1. ASR 与对象存储**必须境内**（配置出站白名单）
2. 只有网关暴露公网，其余服务内网
3. 备份可恢复且验证过（DEP-06 未通过不得上线）
4. 密钥只走环境变量，绝不进仓库

---

## 5. 文档入口

| 想知道什么 | 去哪里 |
|---|---|
| 需求与验收基线 | `docs/PROJECT_REQUIREMENTS.md` |
| 界面长什么样 | `docs/frontend/原型设计说明书.md` + `cognitive-health-diary/` |
| 前端按模块怎么拆 | `docs/frontend/README.md` → `docs/frontend/modules/` |
| 后端怎么实现 / 表结构 | `android/backend/README.md` + `docs/backend/schema.md` |
| 接口怎么调 | `docs/api/README.md` → `docs/api/00-接口通则.md` → `docs/api/API-0x-*.md` |
| 接口机器可读契约 | `docs/api/openapi/openapi.yaml`（生成物，禁手改） |
| 合规边界在哪 | `docs/planning/合规风险备忘.md` |
| 还没拍板的事 | `docs/planning/待确认事项.md` |
| 日常 git 怎么用 | `日常提交指南.md` |
| 开发规则与 Skill 映射 | `AGENTS.md` |

**文档优先级**（冲突时）：用户最新要求 > `docs/api/` > 前后端模块文档 > `docs/PROJECT_REQUIREMENTS.md` > `docs/planning/`。

---

## 6. 分支与提交规则

| 项 | 约定 |
|---|---|
| 远程仓库 | `github.com/keaiyaaa/Old-Health` |
| 日常开发分支 | `psq`（已设远程跟踪，直接 `git push`） |
| 提交信息语言 | **中文**，类型前缀（`feature:` / `fix:` / `perf:` / `docs:`） |
| 直接提交稳定分支 | 未经明确允许不做 |
| force-push / 重写历史 / reset | 未经批准不做 |

日常提交流程见 `日常提交指南.md`。提交前检查：`git status`、`git diff`，确认无密钥、无录音、无日志。

---

## 7. 当前待办与风险

| # | 事项 | 说明 |
|---|---|---|
| 1 | **医疗器械边界的书面法律意见** | 结论若为"去掉趋势分析只留记录"，需求与原型要重做 |
| 2 | **ASR 厂商选型（C3）** | 当前 Mock 只保证链路可跑；选定后只需换 `integrations/asr.py` 的 adapter |
| 3 | **邮件通道接入** | 验证码当前打在后端日志（开发模式），生产需接境内邮件推送服务 |
| 4 | 伦理审查（IRB） | 若论文用自采数据则必需，周期 1–2 个月 |
| 5 | CI 建立 | openapi 重生成比对、契约测试、禁字段扫描（方案见 `docs/api/openapi/README.md` 第 4 节） |
| 6 | 真机评审 | 构建通过不等于验收，需真机过一遍全部页面 |
