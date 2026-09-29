package com.cognidiary.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BrandDeep
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextPrimary

/**
 * P-O1 产品说明与免责。
 *
 * ★ 「能做什么」和「不能做什么」必须等重（验收标准）——两边各 3 条，样式一致。
 */
@Composable
fun IntroScreen(onBack: () -> Unit, onNext: () -> Unit) {
    var checked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.x6, vertical = Spacing.x4),
    ) {
        com.cognidiary.app.ui.components.BackButton(
            onBack = onBack,
            // IconButton 自带 12dp 内边距，抵消后箭头与正文左对齐（点击区仍为 48dp）
            modifier = Modifier.offset(x = (-12).dp),
        )
        Spacer(modifier = Modifier.height(Spacing.x2))
        Text(
            text = Copy.Intro.TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x6))

        listOf(Copy.Intro.CAN_1, Copy.Intro.CAN_2, Copy.Intro.CAN_3).forEach { line ->
            InfoBlock(text = line, modifier = Modifier.padding(vertical = Spacing.x1))
        }

        Spacer(modifier = Modifier.height(Spacing.x6))
        Text(
            text = Copy.Intro.CANNOT_TITLE,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = BrandDeep,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))

        listOf(Copy.Intro.CANNOT_1, Copy.Intro.CANNOT_2, Copy.Intro.CANNOT_3).forEach { line ->
            InfoBlock(text = line, modifier = Modifier.padding(vertical = Spacing.x1))
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(Spacing.x6))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text(text = Copy.Intro.CHECKBOX, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(modifier = Modifier.height(Spacing.x3))
        PrimaryButton(text = Copy.Intro.NEXT, enabled = checked, onClick = onNext)
    }
}
