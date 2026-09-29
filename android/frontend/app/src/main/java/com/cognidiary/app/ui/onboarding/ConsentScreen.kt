package com.cognidiary.app.ui.onboarding

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
import androidx.compose.material3.HorizontalDivider
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
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.ui.AppState
import com.cognidiary.app.ui.LocalAppState
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.nav.PendingMode
import com.cognidiary.app.ui.theme.BrandDeep
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextPrimary

/**
 * P-O2 知情同意。
 *
 * ★ 合规硬要求（docs/frontend/modules/引导与账号设置.md §3）：
 *   - 敏感个人信息【单独同意】，与用户协议分离 —— 两个独立勾选框，无一键全选
 *   - 无默认勾选，同意是主动行为
 *   - 不同意敏感信息则录音功能不可用，必须明确说明原因
 *
 * 一期落地 L1（协议）+ L2（敏感信息）两层；L3 老人知情独立成页、L4 研究同意
 * 待后端与法务，见 docs/planning/2026-09-27-安卓一期实现.md 第 5 节。
 */
@Composable
fun ConsentScreen(onBack: () -> Unit, onConfirmed: (UserMode) -> Unit) {
    val appState: AppState = LocalAppState.current

    var agreedTerms by remember { mutableStateOf(false) }
    var agreedSensitive by remember { mutableStateOf(false) }

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
            text = Copy.Consent.TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x6))

        ConsentItem(
            title = Copy.Consent.ITEM_1_TITLE,
            detail = Copy.Consent.ITEM_1_DETAIL,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        ConsentItem(
            title = Copy.Consent.ITEM_2_TITLE,
            detail = Copy.Consent.ITEM_2_DETAIL,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        ConsentItem(
            title = Copy.Consent.ITEM_3_TITLE,
            detail = Copy.Consent.ITEM_3_DETAIL,
        )

        Spacer(modifier = Modifier.height(Spacing.x6))
        HorizontalDivider(thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(Spacing.x4))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = agreedTerms,
                onCheckedChange = { agreedTerms = it },
            )
            Text(text = Copy.Consent.CHECK_AGREEMENT, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(modifier = Modifier.height(Spacing.x2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = agreedSensitive,
                onCheckedChange = { agreedSensitive = it },
            )
            Text(text = Copy.Consent.CHECK_SENSITIVE, style = MaterialTheme.typography.bodyLarge)
        }

        if (!agreedSensitive) {
            Spacer(modifier = Modifier.height(Spacing.x3))
            InfoBlock(text = Copy.Consent.SENSITIVE_REFUSAL_NOTE)
        }

        Spacer(modifier = Modifier.height(Spacing.x4))
        Text(
            text = Copy.Consent.GUARDIAN_PATH,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandDeep,
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(Spacing.x4))

        PrimaryButton(
            text = Copy.Consent.CONFIRM,
            enabled = agreedTerms && agreedSensitive,
            onClick = {
                appState.setSensitiveConsent(true)
                appState.completeOnboarding()
                onConfirmed(PendingMode.value)
            },
        )
    }
}

@Composable
private fun ConsentItem(title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x1))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
