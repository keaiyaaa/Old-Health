package com.cognidiary.app.ui.family

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cognidiary.app.R
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.data.model.MetricDirection
import com.cognidiary.app.data.model.MetricView
import com.cognidiary.app.data.model.StatusView
import com.cognidiary.app.data.model.Subject
import com.cognidiary.app.ui.components.DisclaimerBar
import com.cognidiary.app.ui.components.statusColor
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BgPage
import com.cognidiary.app.ui.theme.BorderLine
import com.cognidiary.app.ui.theme.BrandDeep
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.Radius
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusReferral
import com.cognidiary.app.ui.theme.StatusWatch
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary
import com.cognidiary.app.ui.theme.TextTertiary
import com.cognidiary.app.util.TimeFormat
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * P-F1 家属端总览 —— 视觉对齐 cognitive-health-diary/family-overview.html。
 *
 * 结构：头部（标题+日期 / 记录·设置·隐私·研究入口）→ 被记录者卡片 →
 *       状态横幅（原型同款暖色卡）→ 指标 2×2 → 数据充分度进度 →
 *       今日未录提醒卡 → 快捷入口 3 宫格 → 免责声明条。
 *
 * 合规：不给分数、不做跨人比较；S4/S5 必带非疾病解释（StatusView 保证）。
 */
@Composable
fun FamilyOverviewScreen(
    onOpenTrends: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenTopics: () -> Unit,
    onOpenVisitPrep: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenResearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val repo = ServiceLocator.repository

    var refreshKey by remember { mutableStateOf(0) }

    val bundle by produceState<OverviewData?>(initialValue = null, key1 = refreshKey) {
        value = runCatching {
            val subject = repo.subject()
            OverviewData(
                subject = subject,
                status = repo.status(subject.id),
                metrics = repo.metrics(subject.id),
                records = repo.records(subject.id),
                missedDays = repo.consecutiveMissedDays(subject.id),
            )
        }.getOrNull()
    }

    var showInvite by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 头部 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding.family, vertical = Spacing.x4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Copy.Family.OVERVIEW_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
                Text(
                    text = String.format(
                        Copy.Family.HEADER_DATE,
                        TimeFormat.dateWithWeek(System.currentTimeMillis()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
            if (ServiceLocator.prefs.researchModeEnabled) {
                HeaderIcon(
                    icon = R.drawable.ic_info,
                    desc = Copy.Family.RESEARCH_MODE,
                    onClick = onOpenResearch,
                )
            }
            HeaderIcon(
                icon = R.drawable.ic_records,
                desc = Copy.Family.RECORDS_TITLE,
                onClick = onOpenRecords,
            )
            HeaderIcon(
                icon = R.drawable.ic_settings,
                desc = Copy.Family.SETTINGS_TITLE,
                onClick = onOpenSettings,
            )
            HeaderIcon(
                icon = R.drawable.ic_shield,
                desc = Copy.Family.PRIVACY_TITLE,
                onClick = onOpenPrivacy,
            )
        }

        // ---------- 内容 ----------
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding.family),
        ) {
            when (val d = bundle) {
                null -> Text(text = Copy.LOADING, color = TextSecondary)
                else -> {
                    SubjectCard(d.subject, d.records)
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    StatusBannerCard(
                        status = d.status,
                        onOpenTrends = onOpenTrends,
                        onOpenTopics = onOpenTopics,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    MetricsSection(metrics = d.metrics, onOpenTrends = onOpenTrends)
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    AnalysisProgressCard(
                        validCount = d.status.validRecordCount,
                        enough = d.status.validRecordCount >= 4,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    if (hasNoRecordToday(d.records)) {
                        RemindCard(reminderMinute = ServiceLocator.prefs.reminderMinuteOfDay)
                        Spacer(modifier = Modifier.height(Spacing.x3))
                    }
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    Text(
                        text = Copy.Family.NEXT_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = Spacing.x1),
                    )
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                        QuickEntry(
                            icon = R.drawable.ic_chat,
                            label = Copy.Family.DAILY_TOPICS,
                            onClick = onOpenTopics,
                            modifier = Modifier.weight(1f),
                        )
                        QuickEntry(
                            icon = R.drawable.ic_visit,
                            label = Copy.Family.QUICK_VISIT,
                            onClick = onOpenVisitPrep,
                            modifier = Modifier.weight(1f),
                        )
                        QuickEntry(
                            icon = R.drawable.ic_invite,
                            label = Copy.Family.ADD_ELDER_TITLE,
                            onClick = { showInvite = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.x2))
                    DisclaimerBar()
                }
            }
        }
    }

    if (showInvite) {
        if (ServiceLocator.prefs.useRemoteBackend) {
            AddElderDialog(
                onDismiss = { showInvite = false },
                onCreated = { refreshKey++ },
            )
        } else {
            val code = remember { "DEMO-" + (1000..9999).random() }
            AlertDialog(
                onDismissRequest = { showInvite = false },
                title = { Text(text = Copy.Family.INVITE_SHEET_TITLE, fontWeight = FontWeight.Medium) },
                text = {
                    Column {
                        Text(text = Copy.Family.INVITE_BODY)
                        Spacer(modifier = Modifier.height(Spacing.x3))
                        Text(
                            text = Copy.Family.INVITE_CODE_LABEL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Text(
                            text = code,
                            style = MaterialTheme.typography.titleLarge,
                            color = BrandPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInvite = false }) { Text(text = Copy.DIALOG_CANCEL) }
                },
            )
        }
    }
}

/**
 * 添加老人（真机联调）：调用 POST /v1/subjects 创建，展示一次性绑定码（API-01 1.9）。
 * 老人端凭「称呼 + 绑定码」绑定后才出现数据。
 */
@Composable
private fun AddElderDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var band by remember { mutableStateOf("70-74") }
    var bindCode by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(
            text = if (bindCode == null) Copy.Family.ADD_ELDER_TITLE
            else Copy.Family.ADD_ELDER_CODE_TITLE,
            fontWeight = FontWeight.Medium,
        ) },
        text = {
            Column {
                if (bindCode == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(text = Copy.Family.ADD_ELDER_NAME_LABEL) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    Text(
                        text = Copy.Family.ADD_ELDER_BAND_LABEL,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
                        listOf("60-64", "65-69", "70-74", "75-79", "80-84").forEach { b ->
                            FilterChip(
                                selected = band == b,
                                onClick = { band = b },
                                label = { Text(text = b) },
                            )
                        }
                    }
                    if (error != null) {
                        Spacer(modifier = Modifier.height(Spacing.x2))
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusReferral,
                        )
                    }
                } else {
                    Text(
                        text = bindCode.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        color = BrandPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x3))
                    Text(
                        text = Copy.Family.ADD_ELDER_CODE_BODY,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            if (bindCode == null) {
                TextButton(
                    onClick = {
                        if (name.isBlank()) {
                            error = Copy.Family.ADD_ELDER_EMPTY_NAME
                            return@TextButton
                        }
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val (_, code) = ServiceLocator.auth.createOrBindSubject(
                                    name.trim(), band, bindCode = null)
                                bindCode = code
                                onCreated()
                            } catch (e: Exception) {
                                error = (e as? com.cognidiary.app.data.remote.ApiException)
                                    ?.message ?: "网络不给力，请稍后再试"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text(text = Copy.Family.ADD_ELDER_SUBMIT) }
            } else {
                TextButton(onClick = onDismiss) { Text(text = Copy.DIALOG_OK) }
            }
        },
        dismissButton = {
            if (bindCode == null) {
                TextButton(onClick = onDismiss) { Text(text = Copy.DIALOG_CANCEL) }
            }
        },
    )
}

// ==================== 头部图标按钮 ====================

@Composable
private fun HeaderIcon(icon: Int, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(start = Spacing.x1)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = TextSecondary,
        )
    }
}

// ==================== 被记录者卡片 ====================

@Composable
private fun SubjectCard(subject: Subject, records: List<DiaryRecord>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .padding(Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(BrandPrimary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_user),
                contentDescription = null,
                tint = BrandPrimary,
                modifier = Modifier.size(25.dp),
            )
        }
        Spacer(modifier = Modifier.width(Spacing.x3))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subject.nickname,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.width(Spacing.x2))
                Text(
                    text = subject.relation + " · " + subject.age + "岁",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.x1))
            Text(
                text = String.format(
                    Copy.Family.STREAK_FORMAT,
                    streakDays(records),
                    records.maxOfOrNull { TimeFormat.relative(it.recordedAt) } ?: "—",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
    }
}

/** 连续记录天数：从今天/昨天往回数有记录的天数（演示口径） */
private fun streakDays(records: List<DiaryRecord>): Int {
    val days = records.mapTo(mutableSetOf()) { dayKey(it.recordedAt) }
    var streak = 0
    val cal = Calendar.getInstance()
    if (dayKey(cal.timeInMillis) !in days) cal.add(Calendar.DAY_OF_YEAR, -1)
    while (dayKey(cal.timeInMillis) in days) {
        streak++
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return streak
}

private fun dayKey(ts: Long): String = TimeFormat.isoDate(ts)

private fun hasNoRecordToday(records: List<DiaryRecord>): Boolean =
    records.none { dayKey(it.recordedAt) == dayKey(System.currentTimeMillis()) }

// ==================== 状态横幅（原型同款暖色卡） ====================

@Composable
private fun StatusBannerCard(
    status: StatusView,
    onOpenTrends: () -> Unit,
    onOpenTopics: () -> Unit,
) {
    val code = status.statusCode
    val accent = statusColor(code)
    val warm = code == com.cognidiary.app.data.model.StatusCode.S4 ||
        code == com.cognidiary.app.data.model.StatusCode.S5

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(Radius.card))
            .background(accent.copy(alpha = 0.12f)),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(modifier = Modifier.padding(Spacing.x4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_bell),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.x2))
                Text(
                    text = Copy.Status.title(code, status.validRecordCount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (warm) StatusReferral else TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = statusTag(code), warm = warm)
            }
            Spacer(modifier = Modifier.height(Spacing.x2))
            Text(
                text = Copy.Status.body(code, status.changedMetricCount, status.changedMetricNames),
                style = MaterialTheme.typography.bodyLarge,
                color = if (warm) StatusReferral else TextSecondary,
            )
            if (code.requiresNonDiseaseExplanation) {
                Spacer(modifier = Modifier.height(Spacing.x3))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.chip))
                        .background(BgCard)
                        .padding(Spacing.x3),
                ) {
                    Text(
                        text = "先别往坏处想",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (warm) StatusReferral else TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.x1))
                    Text(
                        text = status.nonDiseaseExplanation.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.x3))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.button))
                        .background(accent)
                        .clickable(onClick = onOpenTrends)
                        .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
                ) {
                    Text(
                        text = Copy.Family.LINK_TRENDS,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BgCard,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.button))
                        .background(BgCard)
                        .clickable(onClick = onOpenTopics)
                        .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
                ) {
                    Text(
                        text = Copy.Family.QUICK_TOPICS,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (warm) StatusReferral else TextSecondary,
                    )
                }
            }
        }
    }
}

private fun statusTag(code: com.cognidiary.app.data.model.StatusCode): String = when (code) {
    com.cognidiary.app.data.model.StatusCode.S2 -> Copy.Family.TAG_S2
    com.cognidiary.app.data.model.StatusCode.S3 -> Copy.Family.TAG_S3
    com.cognidiary.app.data.model.StatusCode.S4 -> Copy.Family.TAG_S4
    com.cognidiary.app.data.model.StatusCode.S5 -> Copy.Family.TAG_S5
    com.cognidiary.app.data.model.StatusCode.S6 -> Copy.Family.TAG_S6
    else -> Copy.Family.TAG_DEFAULT
}

@Composable
private fun StatusPill(text: String, warm: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (warm) StatusReferral else BrandDeep,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.chip))
            .background(if (warm) StatusWatch.copy(alpha = 0.25f) else BrandPrimary.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.x2, vertical = 2.dp),
    )
}

// ==================== 指标 2×2 ====================

@Composable
private fun MetricsSection(metrics: List<MetricView>, onOpenTrends: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .padding(Spacing.x4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Copy.Family.METRICS_TITLE,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Copy.Family.LINK_TRENDS,
                style = MaterialTheme.typography.labelSmall,
                color = BrandPrimary,
                modifier = Modifier.clickable(onClick = onOpenTrends),
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x1))
        Text(text = Copy.Family.METRICS_NOTE, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        Spacer(modifier = Modifier.height(Spacing.x3))
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                row.forEach { metric ->
                    MetricCell(metric = metric, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(Spacing.x3))
        }
    }
}

@Composable
private fun MetricCell(metric: MetricView, modifier: Modifier = Modifier) {
    val accent = when {
        !metric.hasEnoughData -> TextTertiary
        metric.direction == MetricDirection.LOWER -> StatusWatch
        else -> BrandPrimary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(BgPage)
            .border(0.5.dp, BorderLine, RoundedCornerShape(Radius.card))
            .padding(Spacing.x3),
    ) {
        Text(
            text = metric.kind.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.x2))
        if (metric.hasEnoughData && metric.miniTrend.size >= 2) {
            MiniSpark(points = metric.miniTrend, color = accent)
        } else {
            MiniSpark(points = emptyList(), color = accent)
        }
        Spacer(modifier = Modifier.height(Spacing.x2))
        Text(
            text = metricLabel(metric),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.chip))
                .background(accent.copy(alpha = 0.12f))
                .padding(horizontal = Spacing.x2, vertical = 2.dp),
        )
    }
}

private fun metricLabel(metric: MetricView): String = when {
    !metric.hasEnoughData -> Copy.Family.METRIC_INSUFFICIENT
    MetricDirection.LOWER == metric.direction -> Copy.Family.DIR_LOWER
    MetricDirection.HIGHER == metric.direction -> Copy.Family.DIR_HIGHER
    else -> Copy.Family.DIR_SAME
}

/** 迷你走势线：归一化，无纵轴数值 */
@Composable
private fun MiniSpark(points: List<Float>, color: androidx.compose.ui.graphics.Color) {
    Canvas(modifier = Modifier.size(width = 78.dp, height = 22.dp)) {
        if (points.size < 2) {
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )
            return@Canvas
        }
        val minV = points.min()
        val maxV = points.max()
        val span = (maxV - minV).takeIf { it > 0.0001f } ?: 1f
        val stepX = size.width / (points.size - 1)
        var prev: Offset? = null
        points.forEachIndexed { i, v ->
            val p = Offset(stepX * i, (1f - (v - minV) / span) * size.height)
            prev?.let { drawLine(color, it, p, 2f, StrokeCap.Round) }
            prev = p
        }
    }
}

// ==================== 数据充分度进度 ====================

@Composable
private fun AnalysisProgressCard(validCount: Int, enough: Boolean) {
    val fraction = if (enough) 1f else (validCount / 4f).coerceIn(0.15f, 0.9f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_records),
            contentDescription = null,
            tint = if (enough) BrandPrimary else StatusWatch,
            modifier = Modifier.size(19.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.x3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (enough) {
                    String.format(Copy.Family.DATA_OK, validCount)
                } else {
                    String.format(Copy.Family.DATA_SHORT, validCount)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (enough) TextSecondary else StatusReferral,
            )
            Spacer(modifier = Modifier.height(Spacing.x1))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(BorderLine),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (enough) BrandPrimary else StatusWatch),
                )
            }
        }
    }
}

// ==================== 今日未录提醒卡 ====================

@Composable
private fun RemindCard(reminderMinute: Int) {
    var noted by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .padding(Spacing.x4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Radius.chip))
                    .background(StatusWatch.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bell),
                    contentDescription = null,
                    tint = StatusReferral,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(Spacing.x3))
            Column {
                Text(
                    text = Copy.Family.REMIND_TITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.x2))
        Text(
            text = String.format(
                Copy.Family.REMIND_BODY,
                "%02d:%02d".format(reminderMinute / 60, reminderMinute % 60),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.button))
                .background(BrandPrimary)
                .clickable { noted = true }
                .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
        ) {
            Text(
                text = Copy.Family.REMIND_BUTTON,
                style = MaterialTheme.typography.bodyMedium,
                color = BgCard,
            )
        }
        if (noted) {
            Spacer(modifier = Modifier.height(Spacing.x2))
            Text(
                text = Copy.Family.REMIND_DEMO,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
    }
}

// ==================== 快捷入口 ====================

@Composable
private fun QuickEntry(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.x4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = BrandPrimary,
            modifier = Modifier.size(23.dp),
        )
        Spacer(modifier = Modifier.height(Spacing.x2))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

private data class OverviewData(
    val subject: Subject,
    val status: StatusView,
    val metrics: List<MetricView>,
    val records: List<DiaryRecord>,
    val missedDays: Int,
)
