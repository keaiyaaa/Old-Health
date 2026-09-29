package com.cognidiary.app.ui.family

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.cognidiary.app.ui.AppState
import com.cognidiary.app.ui.LocalAppState
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary

/**
 * P-F5 话题库与提醒。
 *
 * 一期说明：
 *   - 话题库展示预置 30 条（Copy.TOPIC_CARDS）+ 屏内新增（内存态，不持久化）
 *   - 提醒开关与时间为界面态；真实系统通知属 WorkManager 阶段（见计划文件 §5）
 *   - 降频说明常驻：连续 3 天未录自动隔天一次，不可关闭（FR-4.4）
 */
@Composable
fun FamilyTopicsScreen(onBack: () -> Unit) {
    val appState: AppState = LocalAppState.current
    val extraTopics = remember { mutableStateOf(listOf<String>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newTopic by remember { mutableStateOf("") }

    var remindElder by remember { mutableStateOf(true) }
    var remindMe by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(title = Copy.Family.TOPICS_TITLE, onBack = onBack)

        // ---- 提醒 ----
        SectionCard(title = Copy.Family.REMINDER_TITLE) {
            ReminderRow(
                title = Copy.Family.REMINDER_ELDER,
                time = formatMinute(appState.elderReminderMinute),
                checked = remindElder,
                onChange = { remindElder = it },
            )
            Spacer(modifier = Modifier.height(Spacing.x2))
            ReminderRow(
                title = Copy.Family.REMINDER_ME,
                time = formatMinute(appState.familyReminderMinute),
                checked = remindMe,
                onChange = { remindMe = it },
            )
            Spacer(modifier = Modifier.height(Spacing.x3))
            Text(
                text = Copy.Family.REMINDER_FREQ_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x4))

        // ---- 话题库 ----
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.x4)) {
            Text(
                text = Copy.Family.TOPICS_DAILY,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showAddDialog = true }) {
                Text(text = Copy.Family.TOPICS_ADD)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(extraTopics.value) { topic ->
                TopicRow(topic)
            }
            items(Copy.TOPIC_CARDS) { topic ->
                TopicRow(topic)
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(text = Copy.Family.TOPICS_ADD) },
            text = {
                OutlinedTextField(
                    value = newTopic,
                    onValueChange = { newTopic = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTopic.isNotBlank(),
                    onClick = {
                        extraTopics.value = extraTopics.value + newTopic.trim()
                        newTopic = ""
                        showAddDialog = false
                    },
                ) { Text(text = Copy.SAVE) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(text = Copy.DIALOG_CANCEL)
                }
            },
        )
    }
}

@Composable
private fun ReminderRow(
    title: String,
    time: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = time,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(end = Spacing.x3),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TopicRow(topic: String) {
    Text(
        text = topic,
        style = MaterialTheme.typography.bodyLarge,
        color = TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x6, vertical = Spacing.x2),
    )
}

private fun formatMinute(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
