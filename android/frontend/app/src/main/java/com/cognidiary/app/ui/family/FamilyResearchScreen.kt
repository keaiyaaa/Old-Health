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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneId

private data class ScaleEntry(
    val scale: String,
    val score: String,
    val date: String,
    val assessor: String,
)

/**
 * P-F8 研究模式（默认关闭；RESEARCHER 专属，C 端不可见）。
 *
 * ★ MMSE / MoCA 量表分数仅允许出现在本屏 —— 由研究模式开关门禁，
 *   关闭时本屏呈现"未开启"说明，不渲染任何量表内容。
 *
 * 一期说明：量表录入为屏内内存态；导出原始数据集待服务端（按钮禁用）。
 */
@Composable
fun FamilyResearchScreen(onBack: () -> Unit) {
    val appState = com.cognidiary.app.data.ServiceLocator.prefs
    val repo = ServiceLocator.repository

    val weeklyCount by produceState(initialValue = 0) {
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        value = runCatching {
            val sid = repo.subject().id
            repo.records(sid).count { it.recordedAt in weekAgo..now }
        }.getOrDefault(0)
    }
    val subject by produceState<com.cognidiary.app.data.model.Subject?>(initialValue = null) {
        value = runCatching { repo.subject() }.getOrNull()
    }

    if (!appState.researchModeEnabled) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenPadding.family),
        ) {
            Spacer(modifier = Modifier.height(Spacing.x4))
            DetailTopBar(title = Copy.Family.RESEARCH_TITLE, onBack = onBack)
            Spacer(modifier = Modifier.height(Spacing.x6))
            InfoBlock(text = "研究模式没有开启。开启后这里会出现研究用的记录工具。")
            Spacer(modifier = Modifier.height(Spacing.x3))
            InfoBlock(text = Copy.Family.RESEARCH_CONSENT_NOTE)
        }
        return
    }

    // ---- 研究模式已开启 ----
    val entries = remember { mutableStateListOf<ScaleEntry>() }
    var scaleKind by remember { mutableStateOf("MMSE") }
    var score by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var assessor by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(title = Copy.Family.RESEARCH_TITLE, onBack = onBack)

        SectionCard(title = Copy.Family.RESEARCH_CONSENT) {
            Text(
                text = Copy.Family.RESEARCH_SIGNED + " · " + Copy.Family.RESEARCH_CONSENT_NOTE,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x3))

        SectionCard(title = Copy.Family.RESEARCH_SUBJECT_ID) {
            Text(
                text = subject?.id ?: "",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(Spacing.x2))
            Text(
                text = String.format(Copy.Family.RECORD_COUNT_FORMAT, weeklyCount),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x3))

        SectionCard(title = Copy.Family.RESEARCH_SCALE_TITLE) {
            Row {
                listOf("MMSE", "MoCA").forEach { kind ->
                    FilterChip(
                        selected = scaleKind == kind,
                        onClick = { scaleKind = kind },
                        label = { Text(text = kind) },
                        modifier = Modifier.padding(end = Spacing.x2),
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.x2))
            OutlinedTextField(
                value = score,
                onValueChange = { score = it.filter { c -> c.isDigit() } },
                label = { Text(text = Copy.Family.RESEARCH_SCORE) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Spacing.x2))
            OutlinedTextField(
                value = date,
                onValueChange = { date = it },
                label = { Text(text = Copy.Family.RESEARCH_DATE) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Spacing.x2))
            OutlinedTextField(
                value = assessor,
                onValueChange = { assessor = it },
                label = { Text(text = Copy.Family.RESEARCH_ASSESSOR) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Spacing.x3))
            TextButton(
                enabled = score.isNotBlank() && date.isNotBlank() && assessor.isNotBlank(),
                onClick = {
                    entries.add(ScaleEntry(scaleKind, score, date, assessor))
                    score = ""
                    date = ""
                    assessor = ""
                },
            ) { Text(text = Copy.Family.RESEARCH_SAVE) }

            entries.forEach { e ->
                Spacer(modifier = Modifier.height(Spacing.x1))
                Text(
                    text = e.scale + " · " + e.score + " · " + e.date + " · " + e.assessor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.x3))

        SectionCard(title = Copy.Family.RESEARCH_TASK_TITLE) {
            Text(
                text = "每日叙述 · 每周图片描述 · 每周语义流畅性",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x3))

        SecondaryButton(text = Copy.Family.RESEARCH_EXPORT, onClick = {}, enabled = false)
        Spacer(modifier = Modifier.height(Spacing.x1))
        Text(
            text = Copy.Family.RESEARCH_EXPORT_NOTE,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.x8))
    }
}
