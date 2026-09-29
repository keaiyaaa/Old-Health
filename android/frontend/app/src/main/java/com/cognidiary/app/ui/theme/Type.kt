package com.cognidiary.app.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 字号 —— 原型设计说明书 4.2
 *
 * 老人端：页面主标题 26 / 话题卡 24 / 正文 20 / 次要 18 / 极小 16，按钮 ≥20
 * 家属端：页面主标题 20 / 正文 15 / 次要 13 / 极小 12
 *
 * 老人端提供三档字号（标准 / 大 / 特大），默认「大」。
 * 家属端字号跟随系统，不做额外缩放。
 */

// ---------- 家属端 ----------

val FamilyTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    ),
)

// ---------- 老人端 ----------

/**
 * 老人端三档字号。默认 [LARGE]。
 *
 * 注意：只缩放文字，不缩放录音按钮（按钮尺寸已按 140dp 固定，
 * 再放大反而会挤掉垂直空间）。
 */
enum class ElderFontScale(val multiplier: Float, val label: String) {
    STANDARD(0.90f, "标准"),
    LARGE(1.00f, "大"),
    HUGE(1.15f, "特大"),
}

data class ElderTextSizes(
    val pageTitle: TextUnit,
    val topicCard: TextUnit,
    val body: TextUnit,
    val secondary: TextUnit,
    val tiny: TextUnit,
    val button: TextUnit,
    val timer: TextUnit,
    // ★ 垂直间距随字号缩放（负责人 2026-09-28 指示：字体变大时间距同步变大，不得重叠）
    val gapX2: Dp,
    val gapX3: Dp,
    val gapX4: Dp,
    val gapX6: Dp,
    val gapX8: Dp,
)

fun elderTextSizes(scale: ElderFontScale): ElderTextSizes {
    val m = scale.multiplier
    return ElderTextSizes(
        pageTitle = 26.sp * m,
        topicCard = 24.sp * m,
        body = 20.sp * m,
        secondary = 18.sp * m,
        tiny = 16.sp * m,
        button = 20.sp * m,
        timer = 32.sp * m,
        gapX2 = 8.dp * m,
        gapX3 = 12.dp * m,
        gapX4 = 16.dp * m,
        gapX6 = 24.dp * m,
        gapX8 = 32.dp * m,
    )
}

val LocalElderText = staticCompositionLocalOf { elderTextSizes(ElderFontScale.LARGE) }

/**
 * 老人端排版作用域。
 *
 * ★ 修复"特大字号换行重叠"：老人端大量 Text 用 `fontSize = text.xxx` 直接设字号，
 *   行高却继承家属端主题的固定值（如 24sp）。特大档字号 30sp > 24sp → 换行重叠。
 *   本作用域把 LocalTextStyle 的行高交还给字体默认度量（Unspecified = 按字号自动），
 *   覆盖范围内所有文字的换行行高都随字号走，一处包装全部生效。
 */
@Composable
fun ElderTypographyScope(content: @Composable () -> Unit) {
    val current = LocalTextStyle.current
    CompositionLocalProvider(
        LocalTextStyle provides current.copy(lineHeight = TextUnit.Unspecified),
        content = content,
    )
}
