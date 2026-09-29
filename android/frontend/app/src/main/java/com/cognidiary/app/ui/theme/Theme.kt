package com.cognidiary.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/*
 * 产品只有浅色一套配色（白 + 绿），不跟随系统深色模式。
 * 这是刻意的：整体基调"安静、干净"是产品定位的一部分，
 * 见 docs/frontend/原型设计说明书.md 4.1。
 */
private val CognitiveColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BgCard,
    primaryContainer = BrandLight,
    onPrimaryContainer = BrandDeep,
    secondary = BrandDeep,
    onSecondary = BgCard,
    secondaryContainer = BgSubtle,
    onSecondaryContainer = TextPrimary,
    tertiary = BrandPrimary,
    onTertiary = BgCard,
    background = BgPage,
    onBackground = TextPrimary,
    surface = BgCard,
    onSurface = TextPrimary,
    surfaceVariant = BgSubtle,
    onSurfaceVariant = TextSecondary,
    outline = BorderLine,
    outlineVariant = BorderLine,
    error = StatusReferral,
    onError = BgCard,
)

@Composable
fun CognitiveDiaryTheme(
    elderFontScale: ElderFontScale = ElderFontScale.LARGE,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalElderText provides elderTextSizes(elderFontScale)) {
        MaterialTheme(
            colorScheme = CognitiveColorScheme,
            typography = FamilyTypography,
            content = content,
        )
    }
}
