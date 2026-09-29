package com.cognidiary.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.ui.elder.ElderCalendarScreen
import com.cognidiary.app.ui.elder.ElderDoneScreen
import com.cognidiary.app.ui.elder.ElderRecordScreen
import com.cognidiary.app.ui.elder.ElderSettingsScreen
import com.cognidiary.app.ui.family.FamilyOverviewScreen
import com.cognidiary.app.ui.family.FamilyPinScreen
import com.cognidiary.app.ui.family.FamilyPrivacyScreen
import com.cognidiary.app.ui.family.FamilyRecordDetailScreen
import com.cognidiary.app.ui.family.FamilyRecordsScreen
import com.cognidiary.app.ui.family.FamilyResearchScreen
import com.cognidiary.app.ui.family.FamilySettingsScreen
import com.cognidiary.app.ui.family.FamilyTopicsScreen
import com.cognidiary.app.ui.family.FamilyTrendsScreen
import com.cognidiary.app.ui.family.FamilyVisitPrepScreen
import com.cognidiary.app.ui.onboarding.ConsentScreen
import com.cognidiary.app.ui.onboarding.ForgotPasswordScreen
import com.cognidiary.app.ui.onboarding.IntroScreen
import com.cognidiary.app.ui.onboarding.LoginScreen
import com.cognidiary.app.ui.onboarding.RegisterScreen
import com.cognidiary.app.ui.onboarding.StartScreen

/**
 * 引导流程里「用户在 P-C1 选了哪种模式」的临时中转。
 *
 * 为什么不放进 AppState：模式本身要等引导（含敏感信息同意）完成后才生效，
 * 未完成引导前改 prefs.userMode 会造成「没同意却记住了模式」的脏状态。
 * 这只是引导期的一次性内存值，进程结束即消失，不涉及任何个人信息。
 */
object PendingMode {
    @Volatile
    var value: UserMode = UserMode.ELDER
}

/**
 * 两棵【独立】的导航树（docs/frontend/README.md 第 4 节）：
 *
 *   引导：start → intro → consent
 *   老人端：elder/record / elder/done / elder/calendar / elder/settings
 *   家属端：family/overview / family/trends / ... / family/research
 *
 * ★ 老人端导航树里不注册任何状态 / 趋势 / 指标组件（硬约束）。
 * ★ 模式切换与注销走 popUpTo(0) 清空返回栈（FR-5.6）。
 */
@Composable
fun AppNavHost(startDestination: String) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = startDestination) {

        // ---------- 引导（启动页是唯一首页；登录规则见 LoginScreen 注释） ----------
        composable(Routes.START) {
            StartScreen(
                onChooseMode = { mode ->
                    PendingMode.value = mode
                    val sl = com.cognidiary.app.data.ServiceLocator
                    // 先切到所选端的会话命名空间，登录态检查才有意义
                    sl.setApiMode(
                        if (mode == UserMode.FAMILY) "FAMILY_APP" else "ELDER_APP")
                    val prefs = sl.prefs
                    // 远程模式下以服务端令牌为准：本地「引导完成」标记不再等于已登录
                    val hasSession = !prefs.useRemoteBackend ||
                        sl.auth.isLoggedIn()
                    when {
                        // ★ 凡是要登录/注册的，一律先过两页免责声明
                        //   （P-O1 产品说明 → P-O2 知情同意，负责人指示：登录前必须展示）
                        //   首次使用、退出后、换设备、令牌失效，全部走这条路
                        prefs.loggedOut || !prefs.onboardingDone || !hasSession ->
                            nav.navigate(Routes.INTRO)
                        // 已有有效会话：家属端仍要验证查看密码（防止老人随手查看）
                        mode == UserMode.FAMILY ->
                            nav.navigate(Routes.FAMILY_PIN)
                        else ->
                            nav.navigate(Routes.ELDER_RECORD)
                    }
                },
            )
        }
        composable(
            route = Routes.LOGIN,
            arguments = listOf(navArgument(Routes.ARG_MODE) { type = NavType.StringType }),
        ) { entry ->
            val mode = if (entry.arguments?.getString(Routes.ARG_MODE) == UserMode.FAMILY.name) {
                UserMode.FAMILY
            } else {
                UserMode.ELDER
            }
            LoginScreen(
                mode = mode,
                onBack = { nav.popBackStack() },
                onLoggedIn = {
                    // 说明与同意已在登录前完成；家属端仍需查看密码（负责人 2026-09-28 指示）
                    val target = if (mode == UserMode.FAMILY) {
                        Routes.FAMILY_PIN
                    } else {
                        Routes.ELDER_RECORD
                    }
                    nav.navigate(target) { popUpTo(0) { inclusive = true } }
                },
                onGoRegister = { nav.navigate(Routes.register(mode.name)) },
                onGoForgot = { nav.navigate(Routes.FORGOT) },
            )
        }
        composable(
            route = Routes.register("{mode}"),
            arguments = listOf(navArgument("mode") { type = NavType.StringType }),
        ) { entry ->
            val mode = if (entry.arguments?.getString("mode") == UserMode.FAMILY.name) {
                UserMode.FAMILY
            } else {
                UserMode.ELDER
            }
            RegisterScreen(
                mode = mode,
                onBack = { nav.popBackStack() },
                onRegistered = {
                    val target = if (mode == UserMode.FAMILY) {
                        Routes.FAMILY_PIN
                    } else {
                        Routes.ELDER_RECORD
                    }
                    nav.navigate(target) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.FORGOT) {
            ForgotPasswordScreen(
                onBack = { nav.popBackStack() },
                onDone = {
                    // 密码已改、全部会话被踢：回登录页用新密码登录
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.INTRO) {
            IntroScreen(
                onBack = { nav.popBackStack() },
                onNext = { nav.navigate(Routes.CONSENT) },
            )
        }
        composable(Routes.CONSENT) {
            ConsentScreen(
                onBack = { nav.popBackStack() },
                onConfirmed = { mode ->
                    // 同意完成 → 登录（登录在说明与同意之后，负责人 2026-09-28 指示）
                    nav.navigate(Routes.login(mode.name)) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // ---------- 老人端（不含任何指标 / 趋势 / 状态组件） ----------
        composable(Routes.ELDER_RECORD) {
            // 进入老人端：切回老人端会话（从家属端切回来时令牌要换）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.cognidiary.app.data.ServiceLocator.setApiMode("ELDER_APP")
            }
            // 老人端排版作用域：行高随字号缩放，特大字号换行不重叠
            com.cognidiary.app.ui.theme.ElderTypographyScope {
                ElderRecordScreen(
                    onDone = { recordId -> nav.navigate(Routes.elderDone(recordId)) },
                    onOpenCalendar = { nav.navigate(Routes.ELDER_CALENDAR) },
                    onOpenSettings = { nav.navigate(Routes.ELDER_SETTINGS) },
                )
            }
        }
        composable(
            route = Routes.ELDER_DONE,
            arguments = listOf(navArgument(Routes.ARG_RECORD_ID) { type = NavType.StringType }),
        ) { entry ->
            val recordId = entry.arguments?.getString(Routes.ARG_RECORD_ID).orEmpty()
            com.cognidiary.app.ui.theme.ElderTypographyScope {
                ElderDoneScreen(
                    recordId = recordId,
                    onFinished = { nav.popBackStack(Routes.ELDER_RECORD, inclusive = false) },
                    onRerecord = { nav.popBackStack(Routes.ELDER_RECORD, inclusive = false) },
                )
            }
        }
        composable(Routes.ELDER_CALENDAR) {
            com.cognidiary.app.ui.theme.ElderTypographyScope {
                ElderCalendarScreen(onBack = { nav.popBackStack() })
            }
        }
        composable(Routes.ELDER_SETTINGS) {
            com.cognidiary.app.ui.theme.ElderTypographyScope {
                ElderSettingsScreen(
                    onBack = { nav.popBackStack() },
                    onSwitchToFamily = {
                        // 已登录会话内切换：只验证查看密码（防止老人随手查看）
                        nav.navigate(Routes.FAMILY_PIN) { popUpTo(0) { inclusive = true } }
                    },
                    onConsentWithdrawn = {
                        nav.navigate(Routes.CONSENT) { popUpTo(0) { inclusive = true } }
                    },
                    onLogout = {
                        nav.navigate(Routes.START) { popUpTo(0) { inclusive = true } }
                    },
                )
            }
        }

        // ---------- 家属端 ----------
        composable(Routes.FAMILY_PIN) {
            // 进入家属端：切到家属端会话（老人端登录过也不会串）
            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.cognidiary.app.data.ServiceLocator.setApiMode("FAMILY_APP")
            }
            FamilyPinScreen(
                onBack = { nav.popBackStack() },
                onUnlocked = {
                    nav.navigate(Routes.FAMILY_OVERVIEW) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.FAMILY_OVERVIEW) {
            FamilyOverviewScreen(
                onOpenTrends = { nav.navigate(Routes.FAMILY_TRENDS) },
                onOpenRecords = { nav.navigate(Routes.FAMILY_RECORDS) },
                onOpenTopics = { nav.navigate(Routes.FAMILY_TOPICS) },
                onOpenVisitPrep = { nav.navigate(Routes.FAMILY_VISIT_PREP) },
                onOpenPrivacy = { nav.navigate(Routes.FAMILY_PRIVACY) },
                onOpenResearch = { nav.navigate(Routes.FAMILY_RESEARCH) },
                onOpenSettings = { nav.navigate(Routes.FAMILY_SETTINGS) },
            )
        }
        composable(Routes.FAMILY_SETTINGS) {
            FamilySettingsScreen(
                onBack = { nav.popBackStack() },
                onSwitchToElder = {
                    nav.navigate(Routes.ELDER_RECORD) { popUpTo(0) { inclusive = true } }
                },
                onLogout = {
                    nav.navigate(Routes.START) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.FAMILY_TRENDS) {
            FamilyTrendsScreen(
                onBack = { nav.popBackStack() },
                onOpenRecords = { nav.navigate(Routes.FAMILY_RECORDS) },
            )
        }
        composable(Routes.FAMILY_RECORDS) {
            FamilyRecordsScreen(
                onBack = { nav.popBackStack() },
                onOpenRecord = { recordId -> nav.navigate(Routes.familyRecordDetail(recordId)) },
            )
        }
        composable(
            route = Routes.FAMILY_RECORD_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_RECORD_ID) { type = NavType.StringType }),
        ) { entry ->
            val recordId = entry.arguments?.getString(Routes.ARG_RECORD_ID).orEmpty()
            FamilyRecordDetailScreen(
                recordId = recordId,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.FAMILY_TOPICS) {
            FamilyTopicsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FAMILY_VISIT_PREP) {
            FamilyVisitPrepScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FAMILY_PRIVACY) {
            FamilyPrivacyScreen(
                onBack = { nav.popBackStack() },
                onConsentWithdrawn = {
                    nav.navigate(Routes.CONSENT) { popUpTo(0) { inclusive = true } }
                },
                onAccountDeleted = {
                    nav.navigate(Routes.START) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.FAMILY_RESEARCH) {
            FamilyResearchScreen(onBack = { nav.popBackStack() })
        }
    }
}
