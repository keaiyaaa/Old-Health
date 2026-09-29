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
import com.cognidiary.app.audio.AudioFileStore
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.data.model.MetricDirection
import com.cognidiary.app.data.model.PRESET_EVENT_TAGS
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.EventChip
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextSecondary
import com.cognidiary.app.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * P-F4 记录详情。
 *
 * 内容：当天指标方向（相对本人基线，无数值）→ 转写文本 → 家属备注 →
 *       事件标注（预设项增删）→ 删除（二次确认 + 输入确认词，不用红色）。
 *
 * 一期不做「修正转写」：仓储无对应方法，见 docs/planning/2026-09-27-安卓一期实现.md 第 5 节。
 */
@Composable
fun FamilyRecordDetailScreen(
    recordId: String,
    onBack: () -> Unit,
) {
    val repo = ServiceLocator.repository
    val scope = rememberCoroutineScope()

    val record by produceState<DiaryRecord?>(initialValue = null, key1 = recordId) {
        value = runCatching {
            val sid = repo.subject().id
            repo.records(sid, limit = 200).firstOrNull { it.id == recordId }
        }.getOrNull()
    }
    val metrics by produceState(initialValue = emptyList()) {
        value = runCatching {
            repo.metrics(repo.subject().id)
        }.getOrDefault(emptyList())
    }

    var noteDraft by remember(record?.id) { mutableStateOf(record?.familyNote.orEmpty()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(
            title = record?.let { TimeFormat.dateWithWeek(it.recordedAt) }.orEmpty(),
            onBack = onBack,
        )

        when (val r = record) {
            null -> Text(text = Copy.LOADING)
            else -> {
                Text(
                    text = TimeFormat.time(r.recordedAt) + " · " +
                        TimeFormat.duration(r.durationMillis) + " · " +
                        r.recorderRole.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(Spacing.x4))

                SectionCard(title = Copy.Family.DETAIL_METRIC_TITLE) {
                    metrics.forEach { metric ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.x1)) {
                            Text(
                                text = metric.kind.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = directionLabel(metric.direction),
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondary,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.x4))

                SectionCard(title = Copy.Family.DETAIL_TRANSCRIPT) {
                    Text(
                        text = r.transcript ?: Copy.Family.TRANSCRIPT_MISSING,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.x4))

                SectionCard(title = Copy.Family.DETAIL_NOTE_TITLE) {
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        placeholder = { Text(text = Copy.Family.DETAIL_NOTE_HINT) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    TextButton(
                        onClick = {
                            scope.launch { repo.setFamilyNote(r.id, noteDraft) }
                        },
                    ) {
                        Text(text = Copy.SAVE)
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.x4))

                SectionCard(title = Copy.Family.EVENT_LABEL) {
                    Row {
                        r.eventTags.forEach { tag ->
                            EventChip(
                                label = tag,
                                modifier = Modifier.padding(end = Spacing.x2),
                                onRemove = {
                                    scope.launch { repo.removeEventTag(r.id, tag) }
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    Row {
                        PRESET_EVENT_TAGS
                            .filter { it !in r.eventTags }
                            .take(4)
                            .forEach { tag ->
                                EventChip(
                                    label = tag,
                                    modifier = Modifier.padding(end = Spacing.x2),
                                    onClick = {
                                        scope.launch { repo.addEventTag(r.id, tag) }
                                    },
                                )
                            }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.x6))

                SecondaryButton(
                    text = Copy.Family.DETAIL_DELETE,
                    onClick = { showDeleteDialog = true },
                )
                Spacer(modifier = Modifier.height(Spacing.x8))
            }
        }
    }

    if (showDeleteDialog) {
        var confirmWord by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(text = Copy.Family.DETAIL_DELETE) },
            text = {
                Column {
                    Text(text = Copy.Family.DETAIL_DELETE_CONFIRM)
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
                    enabled = confirmWord == "删除",
                    onClick = {
                        showDeleteDialog = false
                        val current = record
                        if (current != null) {
                            AudioFileStore.delete(current.localAudioPath)
                            scope.launch {
                                repo.deleteRecord(current.id)
                                onBack()
                            }
                        }
                    },
                ) { Text(text = Copy.DELETE) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(text = Copy.DIALOG_CANCEL) }
            },
        )
    }
}

private fun directionLabel(direction: MetricDirection): String = when (direction) {
    MetricDirection.LOWER -> Copy.Family.DETAIL_DIRECTION_LOWER
    MetricDirection.SAME -> Copy.Family.DETAIL_DIRECTION_SAME
    else -> Copy.NOT_ENOUGH_DATA
}
