package com.cognidiary.app.ui.elder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.ui.AppState
import com.cognidiary.app.ui.LocalAppState
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.LocalElderText
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * P-E4 老人端设置。
 *
 * 验收相关：
 *   - 字号三档默认"大"，改动即时生效（AppState 用 Compose 状态）
 *   - 「同意状态与撤回」常驻、一眼可见；撤回只能由老人本人操作（§4 权限表）
 *   - 模式切换需二次确认（FR-5.6）
 */
@Composable
fun ElderSettingsScreen(
    onBack: () -> Unit,
    onSwitchToFamily: () -> Unit,
    onConsentWithdrawn: () -> Unit,
    onLogout: () -> Unit,
) {
    val text = LocalElderText.current
    val appState: AppState = LocalAppState.current

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding.elder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.cognidiary.app.ui.components.BackButton(onBack = onBack)
            Text(
                text = Copy.Elder.SETTINGS_TITLE,
                fontSize = text.pageTitle,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(text.gapX4))

        // ---- 字号 ----
        SectionCard(title = Copy.Elder.FONT_SIZE) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                com.cognidiary.app.ui.theme.ElderFontScale.entries.forEach { scale ->
                    FilterChip(
                        selected = appState.elderFontScale == scale,
                        onClick = { appState.applyElderFontScale(scale) },
                        label = { Text(text = scale.label, fontSize = text.secondary) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(text.gapX4))

        // ---- 语音提示 ----
        SectionCard(title = Copy.Elder.VOICE_HINT) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = Copy.Elder.Settings.VOICE_HINT_DESC, fontSize = text.secondary)
                Switch(
                    checked = appState.voiceHintEnabled,
                    onCheckedChange = { appState.applyVoiceHintEnabled(it) },
                )
            }
        }
        Spacer(modifier = Modifier.height(text.gapX4))

        // ---- 同意状态与撤回（常驻） ----
        SectionCard(title = Copy.Elder.Settings.CONSENT_TITLE) {
            Text(
                text = Copy.Elder.Settings.CONSENT_BODY,
                fontSize = text.secondary,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(Spacing.x3))
            TextButton(onClick = { showWithdrawDialog = true }) {
                Text(text = Copy.Elder.Settings.CONSENT_WITHDRAW, fontSize = text.secondary)
            }
        }
        Spacer(modifier = Modifier.height(text.gapX4))

        // ---- 家人绑定 ----
        SectionCard(title = Copy.Elder.FAMILY_BINDING) {
            InfoBlock(text = "女儿")
        }
        Spacer(modifier = Modifier.height(text.gapX4))

        // ---- 模式切换 ----
        SecondaryButton(text = Copy.Elder.SWITCH_TO_FAMILY, onClick = { showSwitchDialog = true })
        Spacer(modifier = Modifier.height(Spacing.x3))

        TextButton(onClick = { showLogoutDialog = true }) {
            Text(text = Copy.Elder.LOGOUT, fontSize = text.secondary)
        }
        Spacer(modifier = Modifier.height(text.gapX8))
    }

    if (showSwitchDialog) {
        ConfirmDialog(
            title = Copy.Elder.SWITCH_TO_FAMILY,
            body = Copy.Elder.Settings.SWITCH_BODY,
            confirmText = Copy.Elder.Settings.SWITCH_CONFIRM,
            onConfirm = {
                showSwitchDialog = false
                appState.rememberMode(UserMode.FAMILY)
                onSwitchToFamily()
            },
            onDismiss = { showSwitchDialog = false },
        )
    }

    if (showWithdrawDialog) {
        ConfirmDialog(
            title = Copy.Elder.Settings.WITHDRAW_TITLE,
            body = Copy.Elder.Settings.WITHDRAW_BODY,
            confirmText = Copy.Elder.Settings.WITHDRAW_CONFIRM,
            onConfirm = {
                showWithdrawDialog = false
                appState.setSensitiveConsent(false)
                onConsentWithdrawn()
            },
            onDismiss = { showWithdrawDialog = false },
        )
    }

    if (showLogoutDialog) {
        ConfirmDialog(
            title = Copy.Elder.LOGOUT,
            body = Copy.Elder.Settings.LOGOUT_BODY,
            confirmText = Copy.Elder.LOGOUT,
            onConfirm = {
                showLogoutDialog = false
                // 实际退出：清除本地会话标记 + 服务端令牌，下次进入需重新登录
                com.cognidiary.app.data.ServiceLocator.prefs.loggedOut = true
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { com.cognidiary.app.data.ServiceLocator.auth.logout() }
                }
                onLogout()
            },
            onDismiss = { showLogoutDialog = false },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = Copy.Elder.Settings.DIALOG_CANCEL) }
        },
    )
}
