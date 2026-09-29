package com.cognidiary.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * 色彩 Token —— 按 cognitive-health-diary/ 原型 HTML 提取（2026-09-29）。
 *
 * 基调：整体白色 + 浅绿 + 米白（暖），安静克制。
 *
 * 两条铁律：
 *
 * 1. 本文件【不定义任何红色常量】。全站无红色是产品红线（原型设计说明书 1.2）。
 *    最高级别只到 StatusReferral（深暖棕橙）。
 *
 * 2. StatusWatch / StatusReferral 必须保留暖色。
 *    如果整站只有绿色，"有变化"和"稳定"在视觉上无法区分。
 *    暖色只出现在 4dp 竖条、状态标签、米白横幅三处，面积极小（原型同款）。
 */

// ---- 底色与卡片 ----
val BgPage = Color(0xFFF5F8F5)
val BgCard = Color(0xFFFFFFFF)
val BgSubtle = Color(0xFFEAF4EE)

// ---- 品牌绿（恢复 2026-09-29 之前的绿）----
val BrandPrimary = Color(0xFF17845A)
val BrandDeep = Color(0xFF0B5138)
val BrandLight = Color(0xFFD9EDE3)

// ---- 文字 ----
val TextPrimary = Color(0xFF1A2B22)
val TextSecondary = Color(0xFF5F7A6D)
val TextTertiary = Color(0xFF93A79C)
val TextFaint = Color(0xFFA0A6AE)

// ---- 描边 / 磁贴 ----
val BorderLine = Color(0xFFDCE7E0)
val TileBg = Color(0xFFFAFBFC)
val TrackBg = Color(0xFFEEF0F3)

// ---- 状态色 S0–S6 ----
val StatusStable = BrandPrimary
val StatusWatch = Color(0xFFD98E1F)
val StatusReferral = Color(0xFFB4531A)
val StatusNeutral = TextTertiary
val StatusResolved = BrandDeep

// ---- 米白暖色横幅（S4/S3 提示卡用，保持米白一族）----
val WarmBg = Color(0xFFFDF0E0)
val WarmBar = Color(0xFFE07A1F)
val WarmText = Color(0xFF8A4209)
val WarmTextSoft = Color(0xFF9A5A1E)
val WarmChip = Color(0xFFF6D6AE)

// ---- 图表 ----
val ChartLine = BrandPrimary
val ChartBand = Color(0x1F17845A)
val ChartMean = Color(0xB317845A)
val ChartLatest = StatusWatch
val ChartAxis = Color(0xFFC9CDD4)

// ---- 半透明遮罩（录音中话题卡降透明度等）----
val ScrimLight = Color(0x14000000)
