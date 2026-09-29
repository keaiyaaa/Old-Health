package com.cognidiary.app.util

/*
 * 提醒降频规则 —— FR-4.4 / docs/frontend/modules/状态与提醒.md 6.1
 *
 *   连续未录 0–2 天 → 按设定时间每天一次
 *   连续未录 3–6 天 → 隔天一次
 *   连续未录 ≥7 天 → 每周一次
 *
 * ★ 降频是自动的，【不可由用户关闭】。
 *   原因：连续未录说明提醒已经造成压力，这时候加频只会更糟。
 */

enum class ReminderFrequency(val displayName: String) {
    DAILY("每天一次"),
    EVERY_OTHER_DAY("隔天一次"),
    WEEKLY("每周一次"),
}

object FrequencyReducer {

    fun frequencyFor(consecutiveMissedDays: Int): ReminderFrequency = when {
        consecutiveMissedDays <= 2 -> ReminderFrequency.DAILY
        consecutiveMissedDays <= 6 -> ReminderFrequency.EVERY_OTHER_DAY
        else -> ReminderFrequency.WEEKLY
    }

    /**
     * 是否该给家属端提示「最近 N 天没有记录，可以打个电话问问他」。
     * 连续 3 天是阈值 —— 与降频起点一致。
     */
    fun shouldNudgeFamily(consecutiveMissedDays: Int): Boolean = consecutiveMissedDays >= 3
}
