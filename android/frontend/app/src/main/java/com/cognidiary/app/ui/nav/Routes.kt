package com.cognidiary.app.ui.nav

/**
 * 路由常量。
 *
 * 与 docs/frontend/README.md 的路由表、以及 docs/frontend/原型设计说明书.md 的页面编号一一对应。
 *
 * ★ elder 前缀与 family 前缀是两棵【独立】的导航树。
 *   老人端导航树里不注册任何状态 / 趋势 / 指标组件 —— 这是硬约束，见 frontend/README.md。
 */
object Routes {

    // ---- 引导 ----
    const val START = "start"
    const val INTRO = "intro"
    const val CONSENT = "consent"

    /** 登录页：mode = elder | family（家属端每次进入都要登录） */
    private const val LOGIN_PATTERN = "login/{mode}"
    const val LOGIN = LOGIN_PATTERN
    const val ARG_MODE = "mode"

    fun login(mode: String): String = "login/$mode"

    /** 注册页 / 忘记密码页（原型图 2、图 3） */
    fun register(mode: String): String = "register/$mode"
    const val FORGOT = "forgot"

    // ---- 老人端 ----
    const val ELDER_RECORD = "elder/record"
    const val ELDER_CALENDAR = "elder/calendar"
    const val ELDER_SETTINGS = "elder/settings"

    private const val ELDER_DONE_PATTERN = "elder/done/{recordId}"
    const val ELDER_DONE = ELDER_DONE_PATTERN
    const val ARG_RECORD_ID = "recordId"

    fun elderDone(recordId: String): String = "elder/done/$recordId"

    // ---- 家属端 ----
    const val FAMILY_PIN = "family/pin"
    const val FAMILY_OVERVIEW = "family/overview"
    const val FAMILY_TRENDS = "family/trends"
    const val FAMILY_RECORDS = "family/records"
    const val FAMILY_TOPICS = "family/topics"
    const val FAMILY_VISIT_PREP = "family/visit_prep"
    const val FAMILY_PRIVACY = "family/privacy"
    const val FAMILY_RESEARCH = "family/research"
    const val FAMILY_SETTINGS = "family/settings"

    private const val FAMILY_RECORD_DETAIL_PATTERN = "family/record/{recordId}"
    const val FAMILY_RECORD_DETAIL = FAMILY_RECORD_DETAIL_PATTERN

    fun familyRecordDetail(recordId: String): String = "family/record/$recordId"
}
