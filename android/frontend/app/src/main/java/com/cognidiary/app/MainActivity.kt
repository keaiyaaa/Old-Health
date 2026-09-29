package com.cognidiary.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.ui.AppState
import com.cognidiary.app.ui.LocalAppState
import com.cognidiary.app.ui.nav.AppNavHost
import com.cognidiary.app.ui.nav.Routes
import com.cognidiary.app.ui.theme.CognitiveDiaryTheme

/**
 * 单 Activity 入口。
 *
 * 启动路由规则（docs/frontend/modules/引导与账号设置.md）：
 *   未完成引导 → P-C1 启动页；
 *   已完成引导 → 直接落到上次选择的模式首页，老人端打开即录音页（FR-1.1：≤2 次点击）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 隐藏系统状态栏（时间 / 电量）。从屏幕顶部下滑可临时呼出，松手自动消失。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            val appState = remember { AppState(ServiceLocator.prefs) }

            // 启动页是唯一首页：每次冷启动都回到这里（负责人 2026-09-28 指示）
            val startDestination = Routes.START

            CognitiveDiaryTheme(elderFontScale = appState.elderFontScale) {
                CompositionLocalProvider(LocalAppState provides appState) {
                    // targetSdk 35 强制全面屏（edge-to-edge），内容默认画到系统栏底下。
                    // 统一在这里做系统栏避让，全部页面一次生效。
                    // ★ 只避让底部手势条（navigationBars），不避让 systemBars：
                    //   systemBars 含状态栏，下滑临时呼出状态栏时其高度 0↔状态栏高度
                    //   突变，会把整个界面顶下去又弹回来。状态栏是临时覆盖层，
                    //   覆盖在内容之上即可，不参与布局。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(top = 12.dp),
                    ) {
                        AppNavHost(startDestination = startDestination)
                    }
                }
            }
        }
    }
}
