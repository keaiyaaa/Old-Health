package com.cognidiary.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cognidiary.app.data.model.UserMode
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BrandLight
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.Radius
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextSecondary

/**
 * P-C1 启动页 / 模式选择。
 *
 * 只有两个入口，产品定位的一部分：让老人第一眼就知道该点哪个。
 */
@Composable
fun StartScreen(onChooseMode: (UserMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 品牌 logo（绿圆 + 声波柱，矢量图）
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.cognidiary.app.R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.padding(bottom = Spacing.x6).size(96.dp),
        )
        Text(
            text = Copy.Start.TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = BrandPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.x2))
        Text(
            text = Copy.Start.SUBTITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(Spacing.x8))

        ModeButton(
            title = Copy.Start.ELDER_BUTTON,
            filled = true,
            onClick = { onChooseMode(UserMode.ELDER) },
        )
        Spacer(modifier = Modifier.height(Spacing.x4))
        ModeButton(
            title = Copy.Start.FAMILY_BUTTON,
            filled = false,
            onClick = { onChooseMode(UserMode.FAMILY) },
        )
    }
}

@Composable
private fun ModeButton(title: String, filled: Boolean, onClick: () -> Unit) {
    val bg = if (filled) BrandPrimary else BgCard
    val fg = if (filled) BgCard else BrandPrimary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(Radius.button))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = fg,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}
