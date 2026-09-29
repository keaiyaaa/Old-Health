# Android 工程实现方案

**版本** v1.0 ｜ 2026-09-27 ｜ 状态：**待你确认后开工**
**配套**：`原型设计说明书.md`（界面规格唯一依据）、`../PROJECT_REQUIREMENTS.md`（需求与验收基线）、`../api/`（接口契约）

> 本文档只描述**怎么把原型变成可运行的 Android 工程**，不含源码。
> 落点：`E:\xiangmu\old\android\`（与 `docs\`、`design\` 平级，不动现有文件）

---

## 1. 目标与范围

**一期交付**：一个可安装、可录音、可看趋势的 Android 原生 App，老人端 + 家属端双模式（`P-C1` 切换）。

| 在范围内 | 不在范围内 |
|---|---|
| 15 个页面全部可导航、可交互 | 真实后端（一期用仓储层 Mock，接口按 `../api/` 契约预留） |
| 老人端**真实录音**（MediaRecorder + 权限 + 四态状态机） | 云端 ASR（接口预留，一期返回 Mock 文本） |
| 合规机制**用代码保证**（状态横幅守卫、无红色、禁用词校验） | 支付、推送、账号体系 |
| 离线可构建、可出 APK | 应用商店上架材料 |

---

## 2. 本机构建环境：实测结论

> 这一节是**实测**，不是推测。它直接决定了技术选型，所以放在最前面。

### 2.1 已具备

| 组件 | 实测结果 |
|---|---|
| Android SDK | `E:\Zulu\Android\Sdk` — platforms `android-34/35/36`，build-tools `34.0.0/35.0.0/36.1.0/37.0.0`，`licenses` 已接受 |
| JDK | `E:\Zulu\jdk 17.0.18.8`（另有 `zulu-8`）；PATH 上另有 Temurin `17.0.18` |
| Gradle | 发行版已缓存：`8.6 / 8.12.1 / 8.13 / 9.0.0`（`~/.gradle/wrapper/dists`） |
| 依赖缓存 | `~/.gradle/caches/modules-2` 约 **740MB**，androidx / compose 已在其中 |
| 模拟器 | MuMu（`E:\mumu`），可用于装 APK 验证 |

**没有 Android Studio，也不影响**——命令行 Gradle 足够出 APK。

### 2.2 网络约束（2026-09-28 更新：D2 已解决，缺口闭合）

| # | 实测结论 | 影响 |
|---|---|---|
| **C1** | `maven.google.com` 直连**仍不可达**（curl 返回 `000`） | 不能直连 Google 仓库 |
| **C2** | **阿里云镜像完全可用**（2026-09-28 实测：`maven.aliyun.com/repository/google` 与 `/central` 均能拉取真实构件，返回 200） | **KSP、Room、SQLCipher、material-icons-extended、DataStore 全部可通过镜像拉取**——原 C2/C3 缺口闭合 |

> **原"三条硬约束"改写**：C2（KSP 未缓存）与 C3（SQLCipher 未缓存）两缺口在 2026-09-27 判定时基于"只能离线构建"的前提；2026-09-28 实测阿里云镜像可用后，**在工程里配置镜像仓库即可拉取全部缺失依赖**：
>
> ```kotlin
> // settings.gradle.kts
> pluginManagement { repositories { maven("https://maven.aliyun.com/repository/gradle-plugin") ; maven("https://maven.aliyun.com/repository/google") ; maven("https://maven.aliyun.com/repository/central") ; google() ; mavenCentral() } }
> dependencyResolutionManagement { repositories { maven("https://maven.aliyun.com/repository/google") ; maven("https://maven.aliyun.com/repository/central") ; google() ; mavenCentral() } }
> ```
>
> **结论**：数据层可以**一次做对**——Room（KSP）+ SQLCipher（FR-6.2 本地加密）+ DataStore 全部落地，不再需要 §7 的取舍与分期。

### 2.3 实测结果：离线构建**已跑通**

为了确认上面三条约束不会让工程起不来，我在临时目录建了一个最小探针工程（与 §3 完全相同的版本组合），执行 `assembleDebug --offline`：

```
BUILD SUCCESSFUL in 52s
35 actionable tasks: 35 executed
```

产物：`app-debug.apk`，**9.06 MB**，`aapt2 dump badging` 读出的实际信息：

```
package: name='com.probe' versionCode='1' versionName='0.0.1'
compileSdkVersion='35'   minSdkVersion:'26'   targetSdkVersion:'35'
```

**结论：`Gradle 8.13 + AGP 8.12.0 + Kotlin 2.0.21 + Compose BOM 2024.12.01 + compileSdk 35 + JDK 17` 这套组合在无网环境下能完整走完编译、打包、出 APK。** §3 的选型可以放心锁定。

唯一无害告警：`Unable to strip the following libraries: libandroidx.graphics.path.so`（debug 构建不裁剪 so，正常现象）。

---

## 3. 技术选型（锁定到本机缓存可用版本）

| 项 | 版本 | 为什么是这个版本 |
|---|---|---|
| Gradle | **8.13** | 本机已缓存；AGP 8.12 要求 Gradle ≥8.13 |
| Android Gradle Plugin | **8.12.0** | 本机已缓存（另有 8.5.2）；支持 compileSdk 36 |
| Kotlin | **2.0.21** | 本机已缓存；对应 Compose 编译器插件同版本 |
| Compose 编译器插件 | **2.0.21** | 与 Kotlin 严格同版本（Kotlin 2.x 起必须显式声明） |
| Compose BOM | **2024.12.01** | 本机已缓存（对应 compose-ui 1.7.6） |
| compileSdk / targetSdk | **35** | 本机有 android-35 |
| minSdk | **26** | 与 `../PROJECT_REQUIREMENTS.md` 14.2 A1 一致（Android 8.0） |
| JVM target | **17** | 与 JDK 17 一致 |
| 架构 | 单 Activity + Compose Navigation | 与 `../frontend/README.md` 路由表一致 |
| 状态管理 | ViewModel + `StateFlow` | 无第三方 DI，一期不引入 Hilt（未缓存） |

**明确不用**：Hilt / Dagger、Retrofit（一期无真实后端）、Room（C2）、DataStore（C3）、任何未缓存依赖。

---

## 4. 工程结构

```
E:\xiangmu\old\android\
├── settings.gradle.kts              单模块 :app
├── build.gradle.kts                 根构建脚本（插件版本集中声明）
├── gradle.properties                JVM 参数、AndroidX 开关
├── local.properties                 sdk.dir → E:\Zulu\Android\Sdk（不进版本库）
├── gradle\libs.versions.toml        版本目录（依赖版本唯一来源）
├── gradlew / gradlew.bat            由 `gradle wrapper` 生成
└── app\
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src\main\
        ├── AndroidManifest.xml
        ├── res\
        │   ├── values\strings.xml            全部用户可见文案（含禁用词校验对象）
        │   ├── values\themes.xml             启动主题（浅色，与白+绿一致）
        │   ├── values\ic_launcher_background.xml
        │   ├── drawable\ic_launcher_foreground.xml
        │   ├── mipmap-anydpi-v26\ic_launcher.xml / ic_launcher_round.xml
        │   └── xml\data_extraction_rules.xml 敏感数据禁止云备份
        └── java\com\cognidiary\app\
            ├── CognitiveDiaryApp.kt
            ├── MainActivity.kt
            ├── ui\theme\
            │   ├── Color.kt          色彩 Token（白+绿，**无红色**）
            │   ├── Type.kt           字号（老人端/家属端两套）
            │   ├── Dimens.kt         间距 4/8/12/16/24/32、圆角
            │   └── Theme.kt          MaterialTheme 装配
            ├── ui\nav\
            │   ├── Routes.kt         路由常量（与原型页面编号一一对应）
            │   └── AppNavHost.kt     老人端/家属端两棵独立导航树
            ├── ui\copy\
            │   └── Copy.kt           附录 A 文案表落地（唯一文案来源）
            ├── ui\components\
            │   ├── BigRecordButton.kt    4 态（未录/录音中/待上传/已完成）
            │   ├── StatusBanner.kt       ★ 含守卫：S4/S5 无非疾病解释则抛异常
            │   ├── MetricCard.kt         迷你折线 + 状态标签
            │   ├── TrendChart.kt         折线 + 基线带 + 均值虚线 + 事件竖线
            │   ├── SufficiencyBar.kt     常驻，不可关闭
            │   ├── EventChip.kt
            │   ├── DisclaimerBar.kt
            │   └── SyncBadge.kt
            ├── ui\onboarding\
            │   ├── StartScreen.kt        P-C1
            │   ├── IntroScreen.kt        P-O1
            │   └── ConsentScreen.kt      P-O2
            ├── ui\elder\
            │   ├── ElderRecordScreen.kt  P-E1（状态机页面，非多路由）
            │   ├── ElderRecordViewModel.kt
            │   ├── ElderDoneScreen.kt    P-E2
            │   ├── ElderCalendarScreen.kt P-E3
            │   └── ElderSettingsScreen.kt P-E4
            ├── ui\family\
            │   ├── FamilyOverviewScreen.kt      P-F1
            │   ├── FamilyTrendsScreen.kt        P-F2
            │   ├── FamilyRecordsScreen.kt       P-F3
            │   ├── FamilyRecordDetailScreen.kt  P-F4
            │   ├── FamilyTopicsScreen.kt        P-F5
            │   ├── FamilyVisitPrepScreen.kt     P-F6
            │   ├── FamilyPrivacyScreen.kt       P-F7
            │   └── FamilyResearchScreen.kt      P-F8
            ├── audio\
            │   ├── AudioRecorder.kt      MediaRecorder 封装 + 中断处理
            │   └── AudioFileStore.kt     录音落盘（app 私有目录）
            ├── data\
            │   ├── model\Models.kt       Record / MetricView / StatusCode / EventTag
            │   ├── repo\DiaryRepository.kt      接口（对齐 ../api/ 契约）
            │   ├── repo\MockDiaryRepository.kt  一期实现（返回构造好的演示数据）
            │   └── repo\LocalPrefs.kt    SharedPreferences（字号、模式、同意状态）
            └── util\
                ├── TimeFormat.kt        相对时间（"2 分钟前"）
                └── FrequencyReducer.kt  降频规则（2/3/6/7 天边界）
```

**约 45 个文件。** 其中 `ui/` 下 30 个，其余为工程配置与数据层。

---

## 5. 页面 → 文件映射

| 页面 | 编号 | 文件 | 阶段 |
|---|---|---|---|
| 启动页 / 模式选择 | P-C1 | `StartScreen.kt` | P0 |
| 产品说明与免责 | P-O1 | `IntroScreen.kt` | P0 |
| 知情同意 | P-O2 | `ConsentScreen.kt` | P0 |
| 录音页 | P-E1 | `ElderRecordScreen.kt` + `ElderRecordViewModel.kt` | P0 |
| 完成页 | P-E2 | `ElderDoneScreen.kt` | P0 |
| 日历页 | P-E3 | `ElderCalendarScreen.kt` | P0 |
| 老人端设置 | P-E4 | `ElderSettingsScreen.kt` | P0 |
| 家属端总览 | P-F1 | `FamilyOverviewScreen.kt` | P0 |
| 趋势详情 | P-F2 | `FamilyTrendsScreen.kt` | P0 |
| 记录列表 | P-F3 | `FamilyRecordsScreen.kt` | P1 |
| 记录详情 | P-F4 | `FamilyRecordDetailScreen.kt` | P1 |
| 话题库与提醒 | P-F5 | `FamilyTopicsScreen.kt` | P2 |
| 就医准备 | P-F6 | `FamilyVisitPrepScreen.kt` | P2 |
| 数据与隐私 | P-F7 | `FamilyPrivacyScreen.kt` | P2 |
| 研究模式 | P-F8 | `FamilyResearchScreen.kt` | P1 |

**路由规则**（沿用 `../frontend/README.md`）：`elder/*` 与 `family/*` 是两棵独立导航树，老人端导航树里**不注册**任何状态/趋势组件。

---

## 6. 关键实现要点

### 6.1 录音链路（一期唯一必须"真跑通"的技术点）

```
点按"开始说话"
  → 检查 RECORD_AUDIO 权限（首次点击时才请求，不用错误语气）
  → MediaRecorder 输出到 app 私有目录
  → 录音中每秒 tick（仅驱动时长环，不显示剩余时间）
点"说完了"
  → stop() → 落盘
  → 生成 Record(syncState = LOCAL_ONLY)
  → 提交给仓储层（一期 Mock：直接标记为"待上传"）
  → 跳 P-E2
```

必须处理的边界（来自 `../frontend/modules/录音采集与上传.md` §7）：

| 边界 | 处理 |
|---|---|
| 时长 <10 秒 | 静默丢弃，**不提示失败** |
| 时长 10–15 秒 | 保留但标记存疑，不计入趋势 |
| 超过 5 分钟 | 自动结束 + 正向提示 |
| 录音中切走/来电 | 监听中断，保留已录部分，回 P-E1 提示"上次那段已经存好了" |
| 权限被拒 | 按钮文案变"需要开一下麦克风"，再次点击重新请求 |

### 6.2 合规机制用代码保证（不靠人工检查）

| 机制 | 实现方式 |
|---|---|
| S4/S5 必带非疾病解释 | `StatusBanner` 在 `statusCode ∈ {S4,S5}` 且 `nonDiseaseExplanation` 为空时 **抛异常**（不是警告） |
| 全站无红色 | `Color.kt` 里**不定义任何红色常量**；最高级只到 `#B4531A` |
| 禁用词 | 文案集中在 `ui/copy/Copy.kt` + `res/values/strings.xml`，可对这两个文件做自动化扫描 |
| 老人端零指标 | 老人端导航树不引用 `MetricCard` / `TrendChart` / `StatusBanner` |
| 不给原始数值 | `MetricView` 数据结构只有 `direction` + `label`，**没有数值字段**（类型层面杜绝） |

### 6.3 数据层：一期做多深（2026-09-28 更新）

> **D2 解决后本节结论反转**：原"Room/SQLCipher/DataStore 落不了地"的约束已随阿里云镜像可用而消失（见 §2.2）。数据层**一次做对**：

| 层 | 做法 | 理由 |
|---|---|---|
| 接口定义 | `DiaryRepository` 接口，方法签名对齐 `../api/` | 后端接入时只换实现，不动 UI |
| 本地持久化 | **Room + KSP**（记录、上传队列、话题库） | 通过阿里云镜像拉取 KSP 插件 |
| 设置/模式/同意状态 | **DataStore** | 通过镜像拉取 `datastore-preferences` |
| 本地加密 | **SQLCipher**（Room）+ `EncryptedFile`（音频），**FR-6.2 一期落地** | 通过镜像拉取 SQLCipher |

> 原文"一期不满足 FR-6.2（本地缓存加密）"的缺口**已闭合**（2026-09-28，决策 #14/D2）。构建命令中去掉 `--offline`、配置镜像仓库后重新 sync 即可拉取新依赖。

---

## 7. 构建与验证

> 下面这条命令**已经实测跑通**（见 §2.3），照抄即可。

```bash
cd E:\xiangmu\old\android

# 日常构建（离线）。JDK 用 Zulu 17，Gradle 用本机已缓存的发行版
JAVA_HOME="E:\Zulu\jdk 17.0.18.8" \
  "C:\Users\Lenovo\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle" \
  assembleDebug --offline --console=plain

# 产物：app\build\outputs\apk\debug\app-debug.apk
```

生成 wrapper 之后（`gradle wrapper --gradle-version 8.13`），也可以直接 `./gradlew assembleDebug --offline`。

**环境变量**：`JAVA_HOME` 当前为空，构建时必须显式指向 `E:\Zulu\jdk 17.0.18.8`，否则会用到 PATH 上那个 Temurin 17（也能用，但不确定因素多）。也可以写进 `gradle.properties` 的 `org.gradle.java.home` 一劳永逸。

**装到模拟器**：MuMu 已在 `E:\mumu`；`adb` 在 `E:\Zulu\Android\Sdk\platform-tools`。

```bash
"E:\Zulu\Android\Sdk\platform-tools\adb.exe" install -r app-debug.apk
```

---

## 8. 分期计划

| 期 | 内容 | 产出 |
|---|---|---|
| **S1** | 工程骨架 + 主题 + 共享组件；跑通一次 `assembleDebug` | 能装上、能看到主题正确 |
| **S2** | 老人端 4 屏 + 真实录音链路 + 引导 3 屏 | **核心链路可用**，老人能真的录一段 |
| **S3** | 家属端 8 屏 + Mock 仓储 | 全部 15 屏可导航 |
| **S4** | 合规自检 + 全量验收 + 出 APK | 可交付 |

建议 **S1 结束就先装到 MuMu 上看一眼**，确认视觉方向和你的 HTML 原型一致，再往下做。

---

## 9. 需要你确认的事

本方案涉及 5 项待决策事项（工程目录位置、网络能否恢复、一期是否真录音、根目录过期原型文档、家属端 Mock 数据）。

**这些事项不写在本文件里** —— 决策项统一收在 [`../planning/待确认事项.md`](../planning/待确认事项.md) 的 **D 节（Android 工程落地层面）**，那里是全项目唯一的决策清单。这样做的原因：决策散落在多份文档里，会出现"改了一处忘了另一处"和"不知道以哪份为准"。

---

## 10. 变更记录

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.0 | 2026-09-27 | 首次编制：实测构建环境，锁定离线可用版本，输出工程结构与分期计划。**离线构建已用最小探针工程实测通过（BUILD SUCCESSFUL in 52s，APK 9.06 MB）** |
| v1.1 | 2026-09-27 | 原第 9 节「需要你确认的事」的 5 项决策移出到 `../planning/待确认事项.md` D 节，本文件只留指针，避免决策项在两处各写一份。技术内容未变。 |
