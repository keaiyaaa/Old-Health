# 代码总目录

本目录存放**全部代码**，按前后端分两侧。文档不在这里 —— 全部文档在 [`../docs/`](../docs/)。

```
android/
├── frontend/          Android 客户端（Kotlin + Jetpack Compose）
│   ├── README.md      客户端入口说明
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle/libs.versions.toml
│   └── app/           唯一模块 :app
└── backend/           服务端（当前为空，技术栈未选型）
    └── README.md      为什么是空的、将来放什么
```

---

## 1. 为什么目录叫 `android/`

它**不是**"Android 客户端目录"，而是本仓库的**代码总目录** —— 历史命名遗留。

一期只有 Android 一个客户端，所以代码根目录当时直接叫了 `android/`。现在前后端都收进来之后，这个名字有点窄了。**是否改名（如 `code/` 或 `app/`）尚未决定**，先按现状保持，避免反复挪动。

## 2. 代码 ↔ 文档 的对应关系

| 代码 | 对应文档 | 说明 |
|---|---|---|
| [`frontend/`](frontend/) | [`../docs/frontend/`](../docs/frontend/) | 客户端实现 ↔ 界面规格、模块拆分 |
| [`backend/`](backend/) | [`../docs/backend/`](../docs/backend/) | 服务端实现 ↔ 服务边界、模块拆分 |
| 两侧共同 | [`../docs/api/`](../docs/api/) | **接口的唯一事实来源**。客户端与服务端都不得自行改契约 |

**改任何接口，先改 `../docs/api/`，再改代码。**

## 3. 当前状态（如实说明）

| 侧 | 状态 |
|---|---|
| `frontend/` | ⚠️ 有 **38 个未经评审的文件**（Gradle 配置 + Manifest + res + Kotlin 源码）。**从未跑通构建**，也未经项目负责人验收。保留与否见下方第 4 节 |
| `backend/` | ❌ 空。技术栈未选型，见 [`../docs/planning/待确认事项.md`](../docs/planning/待确认事项.md) C2 |

## 4. 关于 `frontend/` 里那 38 个文件

这批文件是**在需求/原型/工程方案尚未定稿时被提前写出来的**，属于越界产物。项目负责人已决定**暂时保留**（不删除），并已按前后端结构归位到 `frontend/`。

**保留 ≠ 验收。** 在项目负责人明确说"开始实现"之前：

- 不要把这批文件当作既定架构来遵循
- 不要在其上继续叠加新功能
- 工程方案以 [`../docs/frontend/Android工程实现方案.md`](../docs/frontend/Android工程实现方案.md) 为准（该文档是**设计**，同样尚未批准执行）

## 5. 相关规则

- 目录与命名规则：`../AGENTS.md` 第 5.5 节
- 什么操作需要先确认：`../AGENTS.md` 第 9 节（**重大架构变化**在内）
- 文档优先级（冲突时）：用户最新要求 > `../docs/api/` > 前后端模块文档 > `../docs/PROJECT_REQUIREMENTS.md` > `../docs/planning/`
