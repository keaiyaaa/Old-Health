package com.cognidiary.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cognidiary.app.R
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BgSubtle
import com.cognidiary.app.ui.theme.BorderLine
import com.cognidiary.app.ui.theme.BrandLight
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.Radius
import com.cognidiary.app.ui.theme.Sizes
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.StatusWatch
import com.cognidiary.app.ui.theme.TextPrimary
import com.cognidiary.app.ui.theme.TextSecondary
import com.cognidiary.app.ui.theme.TextTertiary

/**
 * 数据充分度条。
 *
 * ★ 常驻，**不提供关闭入口**。
 *   有效记录 <4 条时强制显示 —— 这是防止"拿两三次记录就下结论"的关键机制。
 */
@Composable
fun SufficiencyBar(
    validRecordCount: Int,
    modifier: Modifier = Modifier,
    alwaysVisible: Boolean = false,
) {
    if (!alwaysVisible && validRecordCount >= 4) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.chip))
            .background(StatusWatch.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.x3, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "本周仅 $validRecordCount 次记录，趋势仅供参考",
            style = MaterialTheme.typography.labelSmall,
            color = StatusWatch,
        )
    }
}

/** 事件标注 Chip —— 产品的差异化点，也是论文的协变量来源 */
@Composable
fun EventChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.chip))
            .background(BgSubtle)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
        )
        if (onRemove != null) {
            Spacer(modifier = Modifier.width(Spacing.x1))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "移除 $label",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp).clickable(onClick = onRemove),
            )
        }
    }
}

/**
 * 免责声明条 —— 底部常驻一行小字。
 * 所有主要页面都必须带上它，这不是装饰，是合规要求。
 */
@Composable
fun DisclaimerBar(modifier: Modifier = Modifier) {
    Text(
        text = Copy.DISCLAIMER_BAR,
        style = MaterialTheme.typography.labelSmall,
        color = TextTertiary,
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.x3),
    )
}

/** 同步状态徽标 */
@Composable
fun SyncBadge(
    text: String,
    lagging: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (lagging) StatusWatch else TextSecondary,
        modifier = modifier,
    )
}

/** 通用白底卡片区块 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(BgCard)
            .padding(Spacing.x4),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(Spacing.x3))
        }
        content()
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // 高度用 minHeight 而非固定值：字号放大时按钮随之撑高，文字不重叠不裁切
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.primaryButton),
        shape = RoundedCornerShape(Radius.button),
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandPrimary,
            contentColor = BgCard,
            disabledContainerColor = BgSubtle,
            disabledContentColor = TextTertiary,
        ),
        contentPadding = PaddingValues(horizontal = Spacing.x6, vertical = Spacing.x3),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(Sizes.primaryButton),
        shape = RoundedCornerShape(Radius.button),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = BgCard,
            contentColor = BrandPrimary,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandLight),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 左上角返回按钮 —— 无标题页面的标准返回入口。
 * 与 DetailTopBar 的箭头样式一致，保证全站返回入口统一。
 */
@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = "返回",
            tint = TextPrimary,
        )
    }
}

/** 详情页顶部栏：返回 + 标题 + 可选右侧动作 */
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = "返回",
                tint = TextPrimary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** 带边框的浅底信息块 */
@Composable
fun InfoBlock(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = BrandPrimary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.chip))
            .background(BgSubtle)
            .border(0.5.dp, BorderLine, RoundedCornerShape(Radius.chip))
            .padding(Spacing.x3),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}
