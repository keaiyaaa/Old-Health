package com.cognidiary.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormat {

    private val monthDay = SimpleDateFormat("M月d日", Locale.CHINA)
    private val monthDayWeek = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val hhmm = SimpleDateFormat("HH:mm", Locale.CHINA)
    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val yearMonth = SimpleDateFormat("yyyy年M月", Locale.CHINA)

    fun date(ts: Long): String = monthDay.format(Date(ts))

    fun dateWithWeek(ts: Long): String = monthDayWeek.format(Date(ts))

    fun time(ts: Long): String = hhmm.format(Date(ts))

    fun isoDate(ts: Long): String = isoDate.format(Date(ts))

    fun yearMonth(ts: Long): String = yearMonth.format(Date(ts))

    /**
     * 相对时间。
     * 超过 7 天就直接给日期 —— "23 天前"这种表达对用户没有帮助，反而增加理解成本。
     */
    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - ts
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour
        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 2 * day -> "昨天"
            diff < 7 * day -> "${diff / day} 天前"
            else -> date(ts)
        }
    }

    /** 录音时长 mm:ss —— 只用于家属端播放器 */
    fun duration(millis: Long): String {
        val total = millis / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    /** 给老人看的时长，用"48 秒"而不是"00:48" */
    fun durationHuman(millis: Long): String {
        val total = millis / 1000
        return if (total < 60) "$total 秒" else "${total / 60} 分 ${total % 60} 秒"
    }
}
