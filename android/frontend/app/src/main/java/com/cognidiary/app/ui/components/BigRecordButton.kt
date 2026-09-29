package com.cognidiary.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.LocalElderText
import com.cognidiary.app.ui.theme.Sizes
import com.cognidiary.app.util.TimeFormat
import kotlin.math.abs
import kotlin.math.sin

/**
 * 大录音按钮的状态 —— docs/frontend/原型设计说明书.md 附录 C。
 *
 * ★ 反向约束（一条都不能破）：
 *   - 只显示【已录时长】，不显示剩余时间
 *   - 不做音量"合格 / 不合格"提示
 *   - 不做评分、不做"录得好"评价
 *   - 失败态不用红色、不出现"错误 / 失败"字样
 */
enum class RecordButtonState {
    /** 未录 */
    IDLE,

    /** 录音中 */
    RECORDING,

    /** 待上传：回到空闲样式，只改底部标注，不变色、不告警 */
    PENDING_UPLOAD,

    /** 已完成 */
    DONE,

    /** 麦克风权限缺失 */
    PERMISSION_MISSING,
}

@Composable
fun BigRecordButton(
    state: RecordButtonState,
    elapsedMillis: Long = 0L,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = LocalElderText.current

    val filled = state != RecordButtonState.RECORDING &&
        state != RecordButtonState.PERMISSION_MISSING

    val label = when (state) {
        RecordButtonState.IDLE -> Copy.Elder.START_SPEAK
        RecordButtonState.PENDING_UPLOAD -> Copy.Elder.START_SPEAK
        RecordButtonState.DONE -> Copy.Elder.START_SPEAK
        RecordButtonState.PERMISSION_MISSING -> Copy.Elder.MIC_NEEDED
        RecordButtonState.RECORDING -> ""
    }

    Box(
        modifier = modifier
            .size(Sizes.bigRecordButton)
            .clip(CircleShape)
            .background(if (filled) BrandPrimary else BgCard)
            .clickable(enabled = state != RecordButtonState.RECORDING, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state == RecordButtonState.RECORDING) {
            Canvas(modifier = Modifier.size(Sizes.bigRecordButton)) {
                drawCircle(
                    color = BrandPrimary,
                    radius = size.minDimension / 2f - 3.dp.toPx(),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = TimeFormat.duration(elapsedMillis),
                    color = BrandPrimary,
                    fontSize = text.timer,
                    fontWeight = FontWeight.Medium,
                )
                SimpleWave(modifier = Modifier.size(width = 84.dp, height = 18.dp))
            }
        } else {
            Text(
                text = label,
                color = if (filled) BgCard else BrandPrimary,
                fontSize = text.button,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(112.dp),
            )
        }
    }
}

/**
 * 极简波形。
 * 只是给老人一个"它在听"的视觉安抚，**不表达任何音质好坏的信息**。
 */
@Composable
private fun SimpleWave(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val bars = 7
        val gap = size.width / bars
        val midY = size.height / 2f
        for (i in 0 until bars) {
            val phase = i.toFloat() / bars
            val amp = (0.35f + 0.65f * abs(sin(phase * Math.PI.toFloat() * 2f))) * midY
            val x = gap * i + gap / 2f
            drawLine(
                color = BrandPrimary,
                start = Offset(x, midY - amp),
                end = Offset(x, midY + amp),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}
