package com.cognidiary.app.ui.family

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.components.SectionCard
import com.cognidiary.app.ui.components.SecondaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.util.TimeFormat

/**
 * P-F6 就医准备（全产品合规风险最高的功能）。
 *
 * 就诊摘要只包含：录音条数、总时长、时间范围、状态文案（服务端口径）。
 * ★ 不包含任何指标数值、分数、概率、解读 —— 与 docs/api/API-03 红线一致。
 *
 * 一期：「分享（文本）」可用；PDF / 长图导出不可用（禁用态），见计划文件 §5。
 */
@Composable
fun FamilyVisitPrepScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = ServiceLocator.repository

    val bundle by produceState<VisitData?>(initialValue = null) {
        value = runCatching {
            val subject = repo.subject()
            VisitData(
                subject = subject,
                status = repo.status(subject.id),
                records = repo.records(subject.id),
            )
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(title = Copy.Family.VISIT_TITLE, onBack = onBack)

        when (val d = bundle) {
            null -> Text(text = Copy.LOADING)
            else -> {
                if (d.records.size < 4) {
                    InfoBlock(text = Copy.Family.VISIT_INSUFFICIENT)
                } else {
                    SectionCard(title = Copy.Family.VISIT_PREVIEW) {
                        Text(
                            text = summaryText(d),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.x3))

                    PrimaryButton(
                        text = Copy.Family.VISIT_SHARE,
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_SUBJECT,
                                    String.format(
                                        Copy.Family.VISIT_SUMMARY_TITLE_FORMAT,
                                        d.subject.nickname,
                                    ),
                                )
                                putExtra(Intent.EXTRA_TEXT, summaryText(d))
                            }
                            context.startActivity(
                                Intent.createChooser(intent, Copy.Family.VISIT_SHARE),
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    SecondaryButton(text = Copy.Family.VISIT_EXPORT_PDF, onClick = {}, enabled = false)
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    SecondaryButton(text = Copy.Family.VISIT_EXPORT_IMAGE, onClick = {}, enabled = false)
                }

                Spacer(modifier = Modifier.height(Spacing.x6))
                Text(
                    text = Copy.Family.VISIT_CHECKLIST_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(Spacing.x2))
                InfoBlock(text = Copy.Family.VISIT_CHECKLIST_DEPT)
                Spacer(modifier = Modifier.height(Spacing.x1))
                InfoBlock(text = Copy.Family.VISIT_CHECKLIST_BRING)
                Spacer(modifier = Modifier.height(Spacing.x1))
                InfoBlock(text = Copy.Family.VISIT_CHECKLIST_QUESTIONS)

                Spacer(modifier = Modifier.height(Spacing.x4))
                Copy.Family.VISIT_QUESTIONS.forEachIndexed { index, question ->
                    Text(
                        text = (index + 1).toString() + ". " + question,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = Spacing.x1),
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.x8))
            }
        }
    }
}

private data class VisitData(
    val subject: com.cognidiary.app.data.model.Subject,
    val status: com.cognidiary.app.data.model.StatusView,
    val records: List<DiaryRecord>,
)

private fun summaryText(d: VisitData): String {
    val totalMillis = d.records.sumOf { it.durationMillis }
    val first = d.records.minOf { it.recordedAt }
    return buildString {
        appendLine(
            String.format(
                Copy.Family.SUMMARY_SUBJECT,
                d.subject.nickname,
                d.subject.relation,
                d.subject.age,
            ),
        )
        appendLine(String.format(Copy.Family.SUMMARY_RANGE, TimeFormat.date(first)))
        appendLine(String.format(Copy.Family.SUMMARY_COUNT, d.records.size))
        appendLine(String.format(Copy.Family.SUMMARY_DURATION, TimeFormat.durationHuman(totalMillis)))
        appendLine(String.format(Copy.Family.SUMMARY_STATUS, Copy.Status.title(d.status.statusCode)))
        appendLine()
        appendLine(
            Copy.Status.body(
                d.status.statusCode,
                d.status.changedMetricCount,
                d.status.changedMetricNames,
            ),
        )
        appendLine()
        appendLine(Copy.Status.NON_DISEASE_EXPLANATIONS.first())
    }
}
