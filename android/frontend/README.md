# frontend —— Android 客户端

Kotlin + Jetpack Compose，单 Activity + Compose Navigation。`minSdk 26`。

---

## ⚠️ 先读这一段（2026-09-27 更新）

原越界产物问题已处理：按项目负责人指示，依据 [`../../docs/frontend/Android工程实现方案.md`](../../docs/frontend/Android工程实现方案.md) 完成了一期实现（P0），过程记录见 [`../../docs/planning/2026-09-27-安卓一期实现.md`](../../docs/planning/2026-09-27-安卓一期实现.md)。

**当前状态**：

- `assembleDebug --offline` **构建通过**，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 10.1 MB）
- **17 屏全部可导航**（原型 15 屏 + 实现期新增的家属端设置页 P-F9、登录页 P-L1）；老人端录音为**真实 MediaRecorder 链路**；家属端数据为 Mock；图标全部为项目自绘 SVG 矢量图
- **未经真机/模拟器评审**：构建通过 ≠ 验收，界面与交互仍需在设备上过一遍
- 一期已知缺口（本地加密、真实上传、TTS、PDF 导出等）见计划文件第 5 节

## 1. 界面规格与模块边界

| 想知道什么 | 去哪里 |
|---|---|
| 界面长什么样、有哪些组件 | [`../../docs/frontend/原型设计说明书.md`](../../docs/frontend/原型设计说明书.md) |
| 路由总表、跨模块约定 | [`../../docs/frontend/README.md`](../../docs/frontend/README.md) |
| 按模块怎么拆 | [`../../docs/frontend/modules/`](../../docs/frontend/modules/) 共 7 篇 |
| 构建环境与分期计划 | [`../../docs/frontend/Android工程实现方案.md`](../../docs/frontend/Android工程实现方案.md) |
| 接口契约 | [`../../docs/api/`](../../docs/api/) —— **唯一事实来源** |

## 2. 当前目录结构

```
frontend/
├── settings.gradle.kts        单模块 :app
├── build.gradle.kts           根构建脚本（插件版本集中声明）
├── gradle.properties          JVM 参数、AndroidX 开关
├── gradle/libs.versions.toml  版本目录（依赖版本唯一来源）
├── local.properties           sdk.dir —— 本机绝对路径，不进版本库
├── .gitignore
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/               文案 / 主题 / 自适应图标 / 备份规则
        └── java/com/cognidiary/app/
            ├── CognitiveDiaryApp.kt
            ├── MainActivity.kt
            ├── ui/{theme,nav,copy,components,onboarding,elder,family}
            ├── audio/         MediaRecorder 封装 + 落盘
            ├── data/{model,repo}
            └── util/
```

**注意**：入口与页面文件已补齐（`MainActivity.kt`、`ui/nav/AppNavHost.kt`、`ui/onboarding/`、`ui/elder/`、`ui/family/`），`AndroidManifest.xml` 引用的类均存在，工程可离线构建。

## 3. 构建环境的已知约束

本机到 `maven.google.com` **不通**，只能离线构建，依赖版本必须落在本机 Gradle 缓存里。由此产生两个已记录的缺口：

| 缺口 | 后果 |
|---|---|
| KSP 插件未缓存 | Room 用不了 → 记录只能放内存 |
| SQLCipher 未缓存 | `FR-6.2`「本地缓存加密」落不了地 |

完整实测结论（含已验证可用的版本组合）见 [`../../docs/frontend/Android工程实现方案.md`](../../docs/frontend/Android工程实现方案.md) 第 2、3 节。**在环境约束确认之前不要升级任何依赖版本。**

## 4. 必须遵守的产品硬约束

违反即产品可能无法上架，详见 `../../AGENTS.md` 第 6 节。客户端侧最容易被破坏的三条：

1. **老人端零指标、零趋势、零分数、零倒计时** —— 老人端导航树不得引用指标卡 / 趋势图 / 状态横幅
2. **全站不使用红色**，最高状态级别只用深暖色（`#B4531A`）
3. **任何"变化"提示必须并列非疾病解释**，缺失即渲染失败（不是警告）

另：用户可见文案**不得硬编码在界面里**，一律走 `ui/copy/`，否则禁用词扫描扫不到。
