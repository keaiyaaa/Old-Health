package com.cognidiary.app.ui.family

import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.cognidiary.app.audio.AudioFileStore
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.ui.AppState
import com.cognidiary.app.ui.LocalAppState
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * P-F7 数据与隐私。
 *
 * 结构：同步状态 → 同意状态（查看 + 撤回，撤回入口就在状态旁边，§3.3）→
 *       保存期限 → 全量导出 → 危险区（清空 / 注销：视觉隔离 + 确认词输入，不用红色）。
 *
 * 一期说明：无云端，"立即同步"按钮不展示（避免假动作）；同步区只呈现本地事实。
 */
@Composable
fun FamilyPrivacyScreen(
    onBack: () -> Unit,
    onConsentWithdrawn: () -> Unit,
    onAccountDeleted: () -> Unit,
) {
    val appState: AppState = LocalAppState.current
    val repo = ServiceLocator.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val data by produceState<PrivacyData?>(initialValue = null) {
        value = runCatching {
            val subject = repo.subject()
            val records = repo.records(subject.id)
            PrivacyData(
                records = records,
                cacheBytes = AudioFileStore.totalBytes(ServiceLocator.appContext),
            )
        }.getOrNull()
    }

    var showConsentDetail by remember { mutableStateOf(false) }
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(title = Copy.Family.PRIVACY_TITLE, onBack = onBack)

        when (val d = data) {
            null -> Text(text = Copy.LOADING)
            else -> {
                // ---- 同步状态 ----
                SectionCard(title = Copy.Family.PRIVACY_SYNC_TITLE) {
                    val pending = d.records.count {
                        it.syncState != com.cognidiary.app.data.model.SyncState.UPLOADED
                    }
                    Text(
                        text = String.format(Copy.Family.PRIVACY_PENDING_FORMAT, pending),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x1))
                    Text(
                        text = String.format(
                            Copy.Family.PRIVACY_CACHE_FORMAT,
                            AudioFileStore.humanSize(d.cacheBytes),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.x4))

                // ---- 同意状态 ----
                SectionCard(title = Copy.Family.PRIVACY_CONSENT_TITLE) {
                    Text(
                        text = Copy.Family.PRIVACY_CONSENT_ITEMS,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    Row {
                        TextButton(onClick = { showConsentDetail = true }) {
                            Text(text = Copy.Family.PRIVACY_VIEW)
                        }
                        TextButton(onClick = { showWithdrawDialog = true }) {
                            Text(text = Copy.Family.PRIVACY_WITHDRAW)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.x4))

                // ---- 保存期限 ----
                SectionCard(title = Copy.Family.PRIVACY_RETENTION_TITLE) {
                    Text(
                        text = Copy.Family.PRIVACY_RETENTION_VALUE,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.x4))

                // ---- 全量导出（文本摘要，一期可用） ----
                SecondaryButton(
                    text = Copy.Family.PRIVACY_EXPORT_ALL,
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                String.format(Copy.Family.SUMMARY_COUNT, d.records.size),
                            )
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                )
                Spacer(modifier = Modifier.height(Spacing.x4))

                // ---- 权限说明 ----
                InfoBlock(text = Copy.Family.PRIVACY_PERMISSION_NOTE)
                Spacer(modifier = Modifier.height(Spacing.x6))

                // ---- 危险区（视觉隔离区块） ----
                SectionCard(title = Copy.Family.PRIVACY_CLEAR_ALL) {
                    Text(
                        text = Copy.Family.PRIVACY_DELETE_CONFIRM,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    SecondaryButton(
                        text = Copy.Family.PRIVACY_CLEAR_ALL,
                        onClick = { showClearDialog = true },
                    )
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    SecondaryButton(
                        text = Copy.Family.PRIVACY_DELETE_ACCOUNT,
                        onClick = { showDeleteAccountDialog = true },
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.x8))
            }
        }
    }

    if (showConsentDetail) {
        AlertDialog(
            onDismissRequest = { showConsentDetail = false },
            title = { Text(text = Copy.Family.PRIVACY_CONSENT_TITLE) },
            text = { Text(text = Copy.DISCLAIMER_EXPANDED) },
            confirmButton = {
                TextButton(onClick = { showConsentDetail = false }) {
                    Text(text = Copy.DIALOG_CANCEL)
                }
            },
        )
    }

    if (showWithdrawDialog) {
        AlertDialog(
            onDismissRequest = { showWithdrawDialog = false },
            title = { Text(text = Copy.Family.PRIVACY_WITHDRAW) },
            text = { Text(text = Copy.Family.PRIVACY_WITHDRAW_CONFIRM) },
            confirmButton = {
                TextButton(onClick = {
                    showWithdrawDialog = false
                    appState.setSensitiveConsent(false)
                    onConsentWithdrawn()
                }) { Text(text = Copy.Family.PRIVACY_WITHDRAW) }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawDialog = false }) {
                    Text(text = Copy.DIALOG_CANCEL)
                }
            },
        )
    }

    if (showClearDialog || showDeleteAccountDialog) {
        var confirmWord by remember { mutableStateOf("") }
        val isAccount = showDeleteAccountDialog
        AlertDialog(
            onDismissRequest = {
                showClearDialog = false
                showDeleteAccountDialog = false
            },
            title = {
                Text(
                    text = if (isAccount) {
                        Copy.Family.PRIVACY_DELETE_ACCOUNT
                    } else {
                        Copy.Family.PRIVACY_CLEAR_ALL
                    },
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Column {
                    Text(text = Copy.Family.PRIVACY_DELETE_CONFIRM)
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    OutlinedTextField(
                        value = confirmWord,
                        onValueChange = { confirmWord = it },
                        placeholder = { Text(text = Copy.DELETE) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmWord == Copy.DELETE,
                    onClick = {
                        showClearDialog = false
                        showDeleteAccountDialog = false
                        scope.launch {
                            repo.records("demo", limit = 500).forEach {
                                AudioFileStore.delete(it.localAudioPath)
                                repo.deleteRecord(it.id)
                            }
                            if (isAccount) {
                                appState.setSensitiveConsent(false)
                                onAccountDeleted()
                            }
                        }
                    },
                ) { Text(text = Copy.DELETE) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    showDeleteAccountDialog = false
                }) { Text(text = Copy.DIALOG_CANCEL) }
            },
        )
    }
}

private data class PrivacyData(
    val records: List<DiaryRecord>,
    val cacheBytes: Long,
)
