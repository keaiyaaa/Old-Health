package com.cognidiary.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.cognidiary.app.data.model.MetricView
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.Radius
import com.cognidiary.app.ui.theme.Sizes
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusNeutral
import com.cognidiary.app.ui.theme.StatusWatch
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextTertiary

/**
 * 指标卡 —— 2×2 网格中的一格。
 *
 * ★ 只显示「指标名 + 迷你折线 + 状态标签」，**不显示任何原始数值**。
 *   [MetricView] 本身也没有数值字段，所以这里想显示也显示不出来。
 */
@Composable
fun MetricCard(
    metric: MetricView,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        !metric.hasEnoughData -> StatusNeutral
        metric.statusLabel == "有变化" -> StatusWatch
        else -> BrandPrimary
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .padding(Spacing.x4),
    ) {
        Text(
            text = metric.kind.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x3))

        if (metric.hasEnoughData && metric.miniTrend.size >= 2) {
            MiniSparkline(
                points = metric.miniTrend,
                color = accent,
                modifier = Modifier.size(Sizes.miniChartWidth, Sizes.miniChartHeight),
            )
        } else {
            // 数据不足：用虚线占位，不用"空图"制造"没有数据=有问题"的错觉
            DashedPlaceholder(modifier = Modifier.size(Sizes.miniChartWidth, Sizes.miniChartHeight))
        }

        Spacer(modifier = Modifier.height(Spacing.x2))
        Text(
            text = metric.statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
        if (!metric.hasEnoughData) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "还差 2 次记录",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun MiniSparkline(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val minV = points.min()
        val maxV = points.max()
        val span = (maxV - minV).takeIf { it > 0.0001f } ?: 1f
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)

        var prev: Offset? = null
        points.forEachIndexed { index, value ->
            val norm = (value - minV) / span
            val y = size.height - norm * size.height
            val current = Offset(stepX * index, y)
            prev?.let {
                drawLine(
                    color = color,
                    start = it,
                    end = current,
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            prev = current
        }
    }
}

@Composable
private fun DashedPlaceholder(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val y = size.height / 2f
        drawLine(
            color = StatusNeutral,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
        )
    }
}

/** 指标卡外层的 2×2 容器用的空白占位，避免出现奇数个指标时布局塌陷 */
@Composable
fun MetricCardPlaceholder(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.Transparent),
    )
}
