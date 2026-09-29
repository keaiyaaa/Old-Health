package com.cognidiary.app.ui.family

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.EventChip
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.components.SyncBadge
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary
import com.cognidiary.app.util.TimeFormat

/**
 * P-F3 记录列表。
 *
 * 每行：日期时间 / 时长 / 代录标记 / 同步状态 / 质量标签 / 事件标注。
 * 空状态按 §5.1 要求给出"为什么空 + 下一步"。
 */
@Composable
fun FamilyRecordsScreen(
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    val repo = ServiceLocator.repository

    val records by produceState<List<DiaryRecord>?>(initialValue = null) {
        value = runCatching { repo.records(repo.subject().id) }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(title = Copy.Family.RECORDS_TITLE, onBack = onBack)

        when {
            records == null -> Text(text = Copy.LOADING, modifier = Modifier.padding(Spacing.x4))
            records!!.isEmpty() -> InfoBlock(text = Copy.Family.RECORDS_EMPTY)
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(records!!, key = { it.id }) { record ->
                    RecordRow(
                        record = record,
                        onClick = { onOpenRecord(record.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordRow(record: DiaryRecord, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.x2)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TimeFormat.dateWithWeek(record.recordedAt) +
                    " " + TimeFormat.time(record.recordedAt),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = TimeFormat.duration(record.durationMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = record.recorderRole.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(end = Spacing.x3),
            )
            if (record.qualityTag != com.cognidiary.app.data.model.QualityTag.NORMAL) {
                Text(
                    text = record.qualityTag.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(end = Spacing.x3),
                )
            }
            SyncBadge(
                text = when (record.syncState) {
                    com.cognidiary.app.data.model.SyncState.UPLOADED -> Copy.Elder.UPLOADED
                    else -> Copy.Elder.WAITING_UPLOAD
                },
                lagging = record.syncState != com.cognidiary.app.data.model.SyncState.UPLOADED,
            )
        }
        if (record.eventTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.x1))
            Row {
                record.eventTags.forEach { tag ->
                    EventChip(label = tag, modifier = Modifier.padding(end = Spacing.x1))
                }
            }
        }
    }
}
