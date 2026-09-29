package com.cognidiary.app.ui.family

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.MetricDirection
import com.cognidiary.app.data.model.MetricKind
import com.cognidiary.app.data.model.MetricTrend
import com.cognidiary.app.data.model.TrendPoint
import com.cognidiary.app.data.repo.RemoteDiaryRepository
import com.cognidiary.app.ui.components.DisclaimerBar
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.components.EventChip
import com.cognidiary.app.ui.components.SufficiencyBar
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BgSubtle
import com.cognidiary.app.ui.theme.BrandDeep
import com.cognidiary.app.ui.theme.ChartAxis
import com.cognidiary.app.ui.theme.ChartBand
import com.cognidiary.app.ui.theme.ChartLatest
import com.cognidiary.app.ui.theme.ChartLine
import com.cognidiary.app.ui.theme.ChartMean
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextFaint
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary
import com.cognidiary.app.ui.theme.TextTertiary
import com.cognidiary.app.ui.theme.TileBg
import com.cognidiary.app.ui.theme.TrackBg
import com.cognidiary.app.ui.theme.WarmBg
import com.cognidiary.app.ui.theme.WarmText
import java.time.Instant
import java.time.ZoneId

/**
 * P-F2 趋势详情（原型 family-trends.html）：
 * 指标 Tab → 单张大图（横轴时间；浅绿「平时范围」带 + 虚线平均 + 橙色最近点 +
 * 事件竖线）→ 图例 → 米白解读卡（含「也还有别的可能」）→ 事件区。
 *
 * ★ 合规要点：纵轴无数值刻度；「平时范围/平均」是客户端由本序列归一化值
 *   推出的视觉参考带（四分位），服务端不返回 mean/stdDev/zScore。
 *   解读卡必须含非疾病解释（红线 6）。
 */
@Composable
fun FamilyTrendsScreen(onBack: () -> Unit, onOpenRecords: () -> Unit) {
    val repo = ServiceLocator.repository
    val remote = repo as? RemoteDiaryRepository

    val data by produceState<TrendsData?>(initialValue = null) {
        value = runCatching {
            val subject = repo.subject()
            TrendsData(
                nickname = subject.nickname,
                metrics = repo.metrics(subject.id),
                trends = remote?.trendSeries(subject.id),
                validCount = repo.status(subject.id).validRecordCount,
                events = runCatching {
                    repo.records(subject.id, limit = 100)
                        .filter { it.eventTags.isNotEmpty() }
                        .map { it.recordedAt to it.eventTags.first() }
                }.getOrDefault(emptyList()),
            )
        }.getOrNull()
    }

    var selectedMetric by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding.family),
    ) {
        Spacer(modifier = Modifier.height(Spacing.x4))
        DetailTopBar(title = Copy.Family.TREND_DETAIL, onBack = onBack)
        Spacer(modifier = Modifier.height(Spacing.x3))

        when (val d = data) {
            null -> Text(text = Copy.LOADING)
            else -> {
                MetricTabs(selected = selectedMetric, onSelect = { selectedMetric = it })
                Spacer(modifier = Modifier.height(Spacing.x3))

                val kind = MetricKind.entries[selectedMetric]
                val metric = d.metrics.firstOrNull { it.kind == kind }
                val trend = d.trends?.firstOrNull { it.kind == kind }
                val points = trend?.points
                    ?: metric?.miniTrend?.mapIndexed { i, v ->
                        TrendPoint(recordedAt = i * 86_400_000L, normalized = v)
                    }.orEmpty()
                val direction = trend?.direction ?: metric?.direction ?: MetricDirection.UNKNOWN
                val enough = trend?.hasEnoughData ?: (metric?.hasEnoughData == true)

                ChartCard(
                    nickname = d.nickname,
                    points = points,
                    enough = enough,
                    direction = direction,
                    events = d.events,
                )
                Spacer(modifier = Modifier.height(Spacing.x3))

                ToneCard(direction = direction, enough = enough, kind = kind,
                    onOpenRecords = onOpenRecords)
                Spacer(modifier = Modifier.height(Spacing.x3))

                EventSection(events = d.events)
                Spacer(modifier = Modifier.height(Spacing.x3))

                SufficiencyBar(validRecordCount = d.validCount)
                Spacer(modifier = Modifier.height(Spacing.x2))
                DisclaimerBar()
                Spacer(modifier = Modifier.height(Spacing.x2))
            }
        }
    }
}

// ---------------- 指标 Tab（原型：白底圆角容器，选中绿底白字） ----------------

@Composable
private fun MetricTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MetricKind.entries.forEachIndexed { index, kind ->
            val isSel = index == selected
            Text(
                text = kind.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                color = if (isSel) BgCard else TextSecondary,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSel) com.cognidiary.app.ui.theme.BrandPrimary
                        else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ---------------- 图表卡（标题 + 状态标签 + 大图 + 图例 + 注） ----------------

@Composable
private fun ChartCard(
    nickname: String,
    points: List<TrendPoint>,
    enough: Boolean,
    direction: MetricDirection,
    events: List<Pair<Long, String>>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .padding(Spacing.x4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$nickname · ${Copy.Family.RANGE_4W}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            StatusPill(direction = direction, enough = enough)
        }
        Spacer(modifier = Modifier.height(Spacing.x3))

        if (points.size >= 2 && enough) {
            TrendCanvas(
                points = points,
                events = events,
                modifier = Modifier.fillMaxWidth().height(170.dp),
            )
            Spacer(modifier = Modifier.height(Spacing.x1))
            DateAxis(points = points)
            Spacer(modifier = Modifier.height(Spacing.x2))
            LegendRow()
            Spacer(modifier = Modifier.height(Spacing.x2))
            Text(
                text = "这张图只给方向和状态，不给具体数字，也不跟别人比。",
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint,
            )
        } else {
            Text(
                text = Copy.Family.TRANSCRIPT_MISSING,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

/** 折线 + 平时范围带（四分位）+ 平均虚线（中位）+ 最近一次橙点 + 事件竖线 */
@Composable
private fun TrendCanvas(
    points: List<TrendPoint>,
    events: List<Pair<Long, String>>,
    modifier: Modifier,
) {
    val dayOf: (Long) -> Long = { it / 86_400_000L }
    val eventDays = events.map { dayOf(it.first) }.toSet()

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padX = 8.dp.toPx()
        val padY = 14.dp.toPx()
        val n = points.size
        val xAt = { i: Int -> padX + (w - 2 * padX) * (i.toFloat() / (n - 1)) }
        val yAt = { v: Float -> padY + (h - 2 * padY) * (1f - v.coerceIn(0f, 1f)) }
        val ys = points.map { it.normalized }

        // 「她自己平时的范围」：序列内四分位带（视觉参考，无数值）
        val sorted = ys.sorted()
        val q1 = sorted[(sorted.size * 0.25f).toInt().coerceAtMost(sorted.size - 1)]
        val q3 = sorted[(sorted.size * 0.75f).toInt().coerceAtMost(sorted.size - 1)]
        val median = sorted[sorted.size / 2]
        drawRoundRect(
            color = ChartBand,
            topLeft = Offset(padX, yAt(q3)),
            size = Size(w - 2 * padX, (yAt(q1) - yAt(q3)).coerceAtLeast(6.dp.toPx())),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        // 平时的平均：虚线
        drawLine(
            color = ChartMean,
            start = Offset(padX, yAt(median)),
            end = Offset(w - padX, yAt(median)),
            strokeWidth = 1.2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
        )
        // 当天有事件：竖线
        points.forEachIndexed { i, p ->
            if (dayOf(p.recordedAt) in eventDays) {
                drawLine(
                    color = ChartAxis,
                    start = Offset(xAt(i), padY / 2),
                    end = Offset(xAt(i), h - padY),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        // 走势线
        val path = Path()
        points.forEachIndexed { i, p ->
            val pos = Offset(xAt(i), yAt(p.normalized))
            if (i == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
        }
        drawPath(path, ChartLine, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        // 逐点；最近一次 = 橙色大点
        points.forEachIndexed { i, p ->
            drawCircle(
                color = if (i == n - 1) ChartLatest else ChartLine,
                radius = (if (i == n - 1) 5f else 3f).dp.toPx(),
                center = Offset(xAt(i), yAt(p.normalized)),
            )
        }
    }
}

@Composable
private fun DateAxis(points: List<TrendPoint>) {
    val n = points.size
    val idx = buildList {
        add(0)
        if (n >= 4) {
            add(n / 3)
            add(2 * n / 3)
        }
        add(n - 1)
    }.distinct()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        idx.forEach { i -> Text(text = formatDate(points[i].recordedAt),
            style = MaterialTheme.typography.labelSmall, color = TextTertiary) }
    }
}

@Composable
private fun LegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(color = ChartBand, isRoundRect = true, label = "她自己平时的范围")
        LegendDash(label = "平时的平均")
        LegendSwatch(color = ChartLatest, isRoundRect = false, label = "最近一次")
        LegendSwatch(color = ChartAxis, isRoundRect = false, label = "当天有事件", isLine = true)
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color,
                         isRoundRect: Boolean, label: String, isLine: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
    }
}

@Composable
private fun LegendDash(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 16.dp, height = 0.dp)
                .background(androidx.compose.ui.graphics.Color.Transparent),
        )
        Text(text = "----", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
    }
}

// ---------------- 状态标签 / 解读卡 / 事件区 ----------------

@Composable
private fun StatusPill(direction: MetricDirection, enough: Boolean) {
    val label = when {
        !enough -> "数据不足"
        direction == MetricDirection.LOWER -> "比平时低一些"
        direction == MetricDirection.HIGHER -> "比平时高一些"
        direction == MetricDirection.SAME -> "跟平时差不多"
        else -> "数据不足"
    }
    val warm = direction == MetricDirection.LOWER || direction == MetricDirection.HIGHER
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = when {
            !enough -> TextTertiary
            warm -> WarmText
            else -> BrandDeep
        },
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(when {
                !enough -> TileBg
                warm -> WarmBg
                else -> BgSubtle
            })
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 解读卡：有变化 → 米白暖卡；持平 → 浅绿卡。必须含非疾病解释（红线 6）。 */
@Composable
private fun ToneCard(direction: MetricDirection, enough: Boolean, kind: MetricKind,
                     onOpenRecords: () -> Unit) {
    val warm = direction != MetricDirection.SAME || !enough
    var noted by remember { mutableStateOf(false) }
    val title = when {
        !enough -> "数据还不算够"
        direction == MetricDirection.LOWER -> "比平时低一些"
        direction == MetricDirection.HIGHER -> "比平时高一些"
        else -> "跟平时差不多"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (warm) WarmBg else BgSubtle)
            .padding(Spacing.x4),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (warm) WarmText else BrandDeep,
        )
        Spacer(modifier = Modifier.height(Spacing.x2))
        Text(
            text = explanationFor(kind),
            style = MaterialTheme.typography.bodyMedium,
            color = if (warm) WarmText else TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BgCard)
                .padding(Spacing.x3),
        ) {
            Text(
                text = "也还有别的可能",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (warm) WarmText else BrandDeep,
            )
            Spacer(modifier = Modifier.height(Spacing.x1))
            Text(
                text = Copy.Status.NON_DISEASE_EXPLANATIONS.first(),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.x3))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            SecondarySmallButton(text = "看原始记录", onClick = onOpenRecords)
            SecondarySmallButton(
                text = if (noted) "已记下" else "记一句观察",
                onClick = { noted = true },
            )
        }
    }
}

@Composable
private fun SecondarySmallButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(TileBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EventSection(events: List<Pair<Long, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .padding(Spacing.x4),
    ) {
        Text(
            text = Copy.Family.EVENT_LABEL,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x1))
        Text(
            text = "点一下就能标到图上，之后看趋势会更容易看懂",
            style = MaterialTheme.typography.labelSmall,
            color = TextFaint,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))
        if (events.isEmpty()) {
            Text(
                text = "这段时间还没有标过事件",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                events.take(4).forEach { (t, label) ->
                    EventChip(label = "${formatDate(t)} $label")
                }
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).let {
        "${it.monthValue}/${it.dayOfMonth}"
    }

private fun explanationFor(kind: MetricKind): String = when (kind) {
    MetricKind.SPEECH_RATE -> Copy.Family.EXPLAIN_SPEECH_RATE
    MetricKind.PAUSE -> Copy.Family.EXPLAIN_FLUENCY
    MetricKind.VOCAB -> Copy.Family.EXPLAIN_LEXICAL
    MetricKind.COHERENCE -> Copy.Family.EXPLAIN_COHERENCE
}

private data class TrendsData(
    val nickname: String,
    val metrics: List<com.cognidiary.app.data.model.MetricView>,
    val trends: List<MetricTrend>?,
    val validCount: Int,
    val events: List<Pair<Long, String>>,
)
