package com.cognidiary.app.ui.family

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.ui.AppState
import com.cognidiary.app.ui.LocalAppState
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusReferral
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * P-F9 家属端设置。
 *
 * 职责（FR-5.6 / FR-9）：
 *   - 模式切换：家属端 ⇄ 老人端，二次确认，切换清空返回栈
 *   - 研究模式开关：默认关闭；开启后家属端总览右上角出现研究模式入口，
 *     MMSE / MoCA 等量表内容只在研究模式内可见（C 端红线）
 *   - 退出登录：二次确认
 */
@Composable
fun FamilySettingsScreen(
    onBack: () -> Unit,
    onSwitchToElder: () -> Unit,
    onLogout: () -> Unit,
) {
    val appState: AppState = LocalAppState.current

    var showSwitchDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(title = Copy.Family.SETTINGS_TITLE, onBack = onBack)

        // ---- 模式切换 ----
        SectionCard(title = Copy.Family.SWITCH_TO_ELDER) {
            InfoBlock(text = Copy.Elder.Settings.SWITCH_BODY)
            Spacer(modifier = Modifier.height(Spacing.x3))
            SecondaryButton(
                text = Copy.Family.SWITCH_TO_ELDER,
                onClick = { showSwitchDialog = true },
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x4))

        // ---- 修改查看密码（家属自设，属账号体系的一部分） ----
        SectionCard(title = Copy.Family.PIN_CHANGE_TITLE) {
            Text(
                text = Copy.Family.PIN_CHANGE_BODY,
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(Spacing.x3))
            var newPin by remember { mutableStateOf("") }
            var confirmPin by remember { mutableStateOf("") }
            var pinMessage by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = newPin,
                onValueChange = { newPin = it },
                label = { Text(text = Copy.Family.PIN_NEW) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Spacing.x2))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { confirmPin = it },
                label = { Text(text = Copy.Family.PIN_CONFIRM) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (pinMessage != null) {
                Spacer(modifier = Modifier.height(Spacing.x2))
                Text(
                    text = pinMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pinMessage == Copy.Family.PIN_SAVED) {
                        BrandPrimary
                    } else {
                        StatusReferral
                    },
                )
            }
            Spacer(modifier = Modifier.height(Spacing.x3))
            TextButton(
                enabled = newPin.isNotEmpty() && confirmPin.isNotEmpty(),
                onClick = {
                    pinMessage = when {
                        newPin.length < 6 -> Copy.Login.REGISTER_SHORT
                        newPin != confirmPin -> Copy.Family.PIN_MISMATCH
                        com.cognidiary.app.data.ServiceLocator.prefs.setFamilyPin(newPin) ->
                            Copy.Family.PIN_SAVED
                        else -> Copy.Family.PIN_MISMATCH
                    }
                    if (pinMessage == Copy.Family.PIN_SAVED) {
                        newPin = ""
                        confirmPin = ""
                    }
                },
            ) { Text(text = Copy.Family.PIN_CHANGE_TITLE) }
        }
        Spacer(modifier = Modifier.height(Spacing.x4))

        // ---- 研究模式 ----
        SectionCard(title = Copy.Family.RESEARCH_MODE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "开启后总览右上角会出现研究模式入口",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = appState.researchModeEnabled,
                    onCheckedChange = { appState.applyResearchModeEnabled(it) },
                )
            }
            Spacer(modifier = Modifier.height(Spacing.x2))
            Text(
                text = Copy.Family.RESEARCH_CONSENT_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x4))

        // ---- 退出登录 ----
        TextButton(onClick = { showLogoutDialog = true }) {
            Text(text = Copy.Elder.LOGOUT)
        }
        Spacer(modifier = Modifier.height(Spacing.x8))
    }

    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchDialog = false },
            title = { Text(text = Copy.Family.SWITCH_TO_ELDER, fontWeight = FontWeight.Medium) },
            text = { Text(text = Copy.Family.SWITCH_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    showSwitchDialog = false
                    appState.rememberMode(UserMode.ELDER)
                    onSwitchToElder()
                }) { Text(text = Copy.Elder.Settings.SWITCH_CONFIRM) }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchDialog = false }) {
                    Text(text = Copy.DIALOG_CANCEL)
                }
            },
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(text = Copy.Elder.LOGOUT, fontWeight = FontWeight.Medium) },
            text = { Text(text = Copy.Elder.Settings.LOGOUT_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    // 实际退出：清除本地会话标记 + 服务端令牌，下次进入需重新登录
                    com.cognidiary.app.data.ServiceLocator.prefs.loggedOut = true
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        runCatching { com.cognidiary.app.data.ServiceLocator.auth.logout() }
                    }
                    onLogout()
                }) { Text(text = Copy.Elder.LOGOUT) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(text = Copy.DIALOG_CANCEL)
                }
            },
        )
    }
}
