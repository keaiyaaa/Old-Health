package com.cognidiary.app.data.repo

import android.content.Context
import android.util.Base64
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.ui.theme.ElderFontScale
import java.security.MessageDigest

/**
 * 轻量本地设置。
 *
 * 为什么用 SharedPreferences 而不是 DataStore：
 *   DataStore 未在本机 Gradle 缓存里（见 Android工程实现方案 2.2 C3）。
 *   设置项数量很少，SharedPreferences 完全够用。
 *
 * ★ 这里只放【非敏感】的界面偏好。
 *   录音文件与记录不进这里 —— 那属于敏感个人信息，一期落盘在 app 私有目录。
 *
 * 账号（一期本地演示）：
 *   只存账号名与密码的 SHA-256 哈希，**不存明文密码**。
 *   出厂预置测试账号 aaa / 123456；用户也可自行注册覆盖。
 *   真实账号体系待服务端（C2），届时本块整体替换为接口调用。
 */
class LocalPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("cognitive_diary_prefs", Context.MODE_PRIVATE)

    init {
        // 预置测试账号（仅本地演示）：aaa / 123456
        if (!prefs.contains(KEY_ACCOUNT)) {
            saveAccount(DEFAULT_ACCOUNT, DEFAULT_PASSWORD)
        }
        // 预置家属端查看密码：000000（家属可自行修改）
        if (!prefs.contains(KEY_FAMILY_PIN_HASH)) {
            setFamilyPin(DEFAULT_FAMILY_PIN)
        }
    }

    /** 是否已有账号（决定老人端首次是否走登录） */
    val hasAccount: Boolean
        get() = prefs.contains(KEY_ACCOUNT)

    /** 当前账号名（仅用于界面展示，不含密码） */
    val accountName: String?
        get() = prefs.getString(KEY_ACCOUNT, null)

    /** 校验账号密码。哈希比较，不碰明文。 */
    fun verify(account: String, password: String): Boolean {
        val savedAccount = prefs.getString(KEY_ACCOUNT, null) ?: return false
        val savedHash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        return account.trim() == savedAccount && hash(password) == savedHash
    }

    /** 注册（覆盖式，一期单账号演示）。成功返回 true。 */
    fun register(account: String, password: String): Boolean {
        val name = account.trim()
        if (name.isEmpty() || password.length < 6) return false
        saveAccount(name, password)
        return true
    }

    private fun saveAccount(account: String, password: String) {
        prefs.edit()
            .putString(KEY_ACCOUNT, account.trim())
            .putString(KEY_PASSWORD_HASH, hash(password))
            .apply()
    }

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * 会话状态：退出登录后置 true，下次进入需重新登录（账号本身保留）。
     * 登录成功即清除。
     */
    var loggedOut: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_OUT, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGGED_OUT, value).apply()

    /**
     * 家属端查看密码（家属自行设置，属于账号体系的一部分）。
     * 进入家属端时只验证此密码，不要求账号——防止老人随手查看家属端。
     * 出厂预置测试密码 000000；家属可在家属端设置中修改。
     */
    fun verifyFamilyPin(pin: String): Boolean {
        val saved = prefs.getString(KEY_FAMILY_PIN_HASH, null) ?: return false
        return hash(pin) == saved
    }

    /** 设置/修改查看密码。至少 6 位，成功返回 true。 */
    fun setFamilyPin(pin: String): Boolean {
        if (pin.length < 6) return false
        prefs.edit().putString(KEY_FAMILY_PIN_HASH, hash(pin)).apply()
        return true
    }

    /** 上次选择的模式，用于非首次启动直接落到对应首页 */
    var userMode: UserMode
        get() = when (prefs.getString(KEY_MODE, UserMode.ELDER.name)) {
            UserMode.FAMILY.name -> UserMode.FAMILY
            else -> UserMode.ELDER
        }
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** 是否已完成引导（P-O1 → P-O2） */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** 敏感个人信息单独同意的状态。撤回后必须重新走一遍 P-O2。 */
    var sensitiveConsentGranted: Boolean
        get() = prefs.getBoolean(KEY_SENSITIVE_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_SENSITIVE_CONSENT, value).apply()

    /** 老人端字号档位，默认「大」 */
    var elderFontScale: ElderFontScale
        get() = runCatching {
            ElderFontScale.valueOf(prefs.getString(KEY_FONT_SCALE, ElderFontScale.LARGE.name)!!)
        }.getOrDefault(ElderFontScale.LARGE)
        set(value) = prefs.edit().putString(KEY_FONT_SCALE, value.name).apply()

    /** 老人端语音提示开关 */
    var voiceHintEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_HINT, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_HINT, value).apply()

    /** 老人端提醒时间（分钟数，从 0 点算起），默认 19:30 */
    var reminderMinuteOfDay: Int
        get() = prefs.getInt(KEY_REMINDER_MINUTE, 19 * 60 + 30)
        set(value) = prefs.edit().putInt(KEY_REMINDER_MINUTE, value).apply()

    /** 家属端提醒时间，默认 21:00 */
    var familyReminderMinuteOfDay: Int
        get() = prefs.getInt(KEY_FAMILY_REMINDER_MINUTE, 21 * 60)
        set(value) = prefs.edit().putInt(KEY_FAMILY_REMINDER_MINUTE, value).apply()

    /** 研究模式开关。默认关闭，C 端界面任何情况下都看不到 MMSE / MoCA。 */
    var researchModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_RESEARCH_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_RESEARCH_MODE, value).apply()

    /**
     * 是否接入真实后端（C2：FastAPI 服务端已就绪）。
     * 关闭时回退到本地演示数据（MockDiaryRepository），便于离线演示。
     */
    var useRemoteBackend: Boolean
        get() = prefs.getBoolean(KEY_USE_REMOTE, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_REMOTE, value).apply()

    /** 后端地址。模拟器访问宿主机用 10.0.2.2；真机填电脑在手机热点网络的 IP。 */
    var backendBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    private companion object {
        // 2026-09-29：电脑连接手机热点，热点网络内电脑地址（ipconfig 实测）
        const val DEFAULT_BASE_URL = "http://192.168.58.33:8000"
        const val KEY_MODE = "user_mode"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_SENSITIVE_CONSENT = "sensitive_consent_granted"
        const val KEY_FONT_SCALE = "elder_font_scale"
        const val KEY_VOICE_HINT = "voice_hint_enabled"
        const val KEY_REMINDER_MINUTE = "reminder_minute_of_day"
        const val KEY_FAMILY_REMINDER_MINUTE = "family_reminder_minute_of_day"
        const val KEY_RESEARCH_MODE = "research_mode_enabled"
        const val KEY_USE_REMOTE = "use_remote_backend"
        const val KEY_BASE_URL = "backend_base_url"
        const val KEY_ACCOUNT = "demo_account"
        const val KEY_PASSWORD_HASH = "demo_password_hash"
        const val KEY_LOGGED_OUT = "logged_out"
        const val KEY_FAMILY_PIN_HASH = "family_view_pin_hash"
        const val DEFAULT_ACCOUNT = "aaa"
        const val DEFAULT_PASSWORD = "123456"
        const val DEFAULT_FAMILY_PIN = "000000"
    }
}
