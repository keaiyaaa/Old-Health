package com.cognidiary.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.ChartBand
import com.cognidiary.app.ui.theme.ChartLine
import com.cognidiary.app.ui.theme.ChartMean
import com.cognidiary.app.ui.theme.Radius
import com.cognidiary.app.ui.theme.StatusNeutral
import com.cognidiary.app.ui.theme.TextTertiary

/** 图上的事件标注（感冒 / 住院 / 旅行…） */
data class EventMarker(
    /** 0f–1f，在横轴上的相对位置 */
    val positionRatio: Float,
    val label: String,
)

/**
 * 趋势图 —— 折线 + 基线带 + 均值虚线 + 当前点，可叠加事件标注竖线。
 *
 * ★ 只画走势，**不标任何纵轴数值**。
 *   数值由调用方保证不传进来（[points] 是已归一化的相对值）。
 */
@Composable
fun TrendChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    bandLow: Float? = null,
    bandHigh: Float? = null,
    mean: Float? = null,
    eventMarkers: List<EventMarker> = emptyList(),
    dimmed: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(156.dp)) {
            if (points.size < 2) return@Canvas

            val allValues = buildList {
                addAll(points)
                bandLow?.let { add(it) }
                bandHigh?.let { add(it) }
                mean?.let { add(it) }
            }
            val minV = allValues.min()
            val maxV = allValues.max()
            val span = (maxV - minV).takeIf { it > 0.0001f } ?: 1f

            val padY = 10.dp.toPx()
            val usableH = size.height - padY * 2

            fun yOf(v: Float): Float = padY + (1f - (v - minV) / span) * usableH

            val stepX = size.width / (points.size - 1).coerceAtLeast(1)
            val lineColor = if (dimmed) StatusNeutral else ChartLine

            // 1. 基线带
            if (bandLow != null && bandHigh != null) {
                val yTop = yOf(bandHigh)
                val yBottom = yOf(bandLow)
                drawRect(
                    color = ChartBand,
                    topLeft = Offset(0f, yTop),
                    size = Size(size.width, (yBottom - yTop).coerceAtLeast(1f)),
                )
            }

            // 2. 均值虚线
            if (mean != null) {
                val y = yOf(mean)
                drawLine(
                    color = ChartMean,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
                )
            }

            // 3. 事件标注竖线
            eventMarkers.forEach { marker ->
                val x = size.width * marker.positionRatio.coerceIn(0f, 1f)
                drawLine(
                    color = TextTertiary,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                )
            }

            // 4. 折线
            var prev: Offset? = null
            points.forEachIndexed { index, value ->
                val current = Offset(stepX * index, yOf(value))
                prev?.let {
                    drawLine(
                        color = lineColor,
                        start = it,
                        end = current,
                        strokeWidth = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                prev = current
            }

            // 5. 当前点
            val last = Offset(stepX * (points.size - 1), yOf(points.last()))
            drawCircle(color = lineColor, radius = 3.5.dp.toPx(), center = last)
            drawCircle(
                color = BgCard,
                radius = 1.5.dp.toPx(),
                center = last,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
