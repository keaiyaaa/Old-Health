package com.cognidiary.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cognidiary.app.data.model.StatusCode
import com.cognidiary.app.data.model.StatusView
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BgSubtle
import com.cognidiary.app.ui.theme.BorderLine
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.Radius
import com.cognidiary.app.ui.theme.Sizes
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusNeutral
import com.cognidiary.app.ui.theme.StatusReferral
import com.cognidiary.app.ui.theme.StatusResolved
import com.cognidiary.app.ui.theme.StatusStable
import com.cognidiary.app.ui.theme.StatusWatch
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary

/**
 * 状态横幅。家属端首页与就医准备页共用。
 *
 * ★★ 核心机制：S4 / S5 必须并列「非疾病解释」★★
 *
 * 如果服务端返回的 [StatusView.nonDiseaseExplanation] 为空，
 * 本组件**渲染失败并抛出异常** —— 不是警告，是失败。
 *
 * 为什么用抛异常而不是显示兜底文案：
 *   1. 兜底文案会绕过禁用词检查，等于给自己开后门
 *   2. 抛异常会在构建 / 测试阶段立刻暴露，人工评审则可能被漏掉
 *   3. 这条规则一旦漏掉一次，就可能给一个家庭造成不必要的恐慌
 *
 * 见 docs/frontend/modules/状态与提醒.md 3.4。
 */
@Composable
fun StatusBanner(
    status: StatusView,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val code = status.statusCode

    if (code.requiresNonDiseaseExplanation && status.nonDiseaseExplanation.isNullOrBlank()) {
        throw IllegalStateException(
            "StatusBanner 守卫触发：statusCode=${code.name} 必须并列非疾病解释，" +
                "但 nonDiseaseExplanation 为空。接口契约见 docs/api/API-03-指标与趋势.md —— " +
                "服务端在 S4/S5 且该字段为空时应当返回 500 并告警，不应把空值送到客户端。"
        )
    }

    val barColor = statusColor(code)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard),
    ) {
        // 左侧 4dp 状态色竖条 —— 暖色只出现在这里、状态标签文字、充分度条三处
        Spacer(modifier = Modifier.width(Sizes.statusBar).fillMaxHeight().background(barColor))

        Column(modifier = Modifier.padding(Spacing.x4)) {
            Text(
                text = Copy.Status.title(code, status.validRecordCount),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(Spacing.x2))
            Text(
                text = Copy.Status.body(
                    code = code,
                    changedCount = status.changedMetricCount,
                    metricNames = status.changedMetricNames,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            if (code.requiresNonDiseaseExplanation) {
                Spacer(modifier = Modifier.height(Spacing.x3))
                HorizontalDivider(color = BorderLine, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(Spacing.x3))
                // 非疾病解释块：独立底色 + 独立段落，保证视觉比重足够
                Text(
                    text = status.nonDiseaseExplanation.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.chip))
                        .background(BgSubtle)
                        .padding(Spacing.x3),
                )
            }

            if (status.actionRoute != null && onActionClick != null) {
                Spacer(modifier = Modifier.height(Spacing.x2))
                TextButton(onClick = onActionClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        text = actionLabel(code),
                        color = BrandPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** S0–S6 对应的表现色。S0/S1/S3 中性灰，S2 绿，S4 橙，S5 深橙（**不是红**），S6 灰绿。 */
fun statusColor(code: StatusCode): Color = when (code) {
    StatusCode.S0, StatusCode.S1, StatusCode.S3 -> StatusNeutral
    StatusCode.S2 -> StatusStable
    StatusCode.S4 -> StatusWatch
    StatusCode.S5 -> StatusReferral
    StatusCode.S6 -> StatusResolved
}

private fun actionLabel(code: StatusCode): String = when (code) {
    StatusCode.S5 -> Copy.Family.VISIT_PREP
    else -> Copy.Family.TREND_DETAIL
}
