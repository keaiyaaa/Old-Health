package com.cognidiary.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.data.repo.LocalPrefs
import com.cognidiary.app.ui.theme.ElderFontScale
import kotlinx.coroutines.launch

/**
 * 跨页面的界面状态。
 *
 * 与 [LocalPrefs] 的分工：
 *   [LocalPrefs] 负责落盘（重启后还在），[AppState] 负责「改完立刻生效」。
 *   两边的写操作都收在 setter 里，避免出现「改了内存没落盘」或「落了盘界面没刷新」。
 *
 * ★ 老人端字号必须能实时生效 —— 老人在设置页调完字号，要马上看到结果，
 *   否则他不会相信这个开关起作用了。所以字号用 Compose 状态而不是只读 SharedPreferences。
 */
class AppState(private val prefs: LocalPrefs) {

    var elderFontScale by mutableStateOf(prefs.elderFontScale)
        private set

    var voiceHintEnabled by mutableStateOf(prefs.voiceHintEnabled)
        private set

    var researchModeEnabled by mutableStateOf(prefs.researchModeEnabled)
        private set

    var elderReminderMinute by mutableStateOf(prefs.reminderMinuteOfDay)
        private set

    var familyReminderMinute by mutableStateOf(prefs.familyReminderMinuteOfDay)
        private set

    // 命名说明：delegated property 已生成 setXxx 的 JVM 签名，公开函数用 applyXxx 避免平台声明冲突
    fun applyElderFontScale(value: ElderFontScale) {
        elderFontScale = value
        prefs.elderFontScale = value
    }

    fun applyVoiceHintEnabled(value: Boolean) {
        voiceHintEnabled = value
        prefs.voiceHintEnabled = value
    }

    fun applyResearchModeEnabled(value: Boolean) {
        researchModeEnabled = value
        prefs.researchModeEnabled = value
    }

    fun applyElderReminderMinute(value: Int) {
        elderReminderMinute = value
        prefs.reminderMinuteOfDay = value
    }

    fun applyFamilyReminderMinute(value: Int) {
        familyReminderMinute = value
        prefs.familyReminderMinuteOfDay = value
    }

    /** 记住上次选的模式，下次冷启动可以直接落到对应首页 */
    fun rememberMode(mode: UserMode) {
        prefs.userMode = mode
    }

    fun completeOnboarding() {
        prefs.onboardingDone = true
    }

    /**
     * 敏感个人信息单独同意。撤回时置 false，下次必须重新走 P-O2。
     *
     * 后端接入：同意的事实来源在服务端（合规：服务端校验，不只信客户端）。
     * 本地标记仅用于界面流转；授予/撤回立即同步 /v1/consents（API-01 1.15/1.16）。
     */
    fun setSensitiveConsent(granted: Boolean) {
        prefs.sensitiveConsentGranted = granted
        if (!prefs.useRemoteBackend) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                if (granted) {
                    ServiceLocator.auth.grantConsent("SENSITIVE")
                    ServiceLocator.auth.grantConsent("ELDER_INFORMED")
                } else {
                    ServiceLocator.auth.revokeConsent("SENSITIVE")
                    ServiceLocator.auth.revokeConsent("ELDER_INFORMED")
                }
            }
        }
    }

    fun sensitiveConsentGranted(): Boolean = prefs.sensitiveConsentGranted
}

val LocalAppState = staticCompositionLocalOf<AppState> {
    error("LocalAppState 未提供：必须在 MainActivity 里通过 CompositionLocalProvider 注入")
}
