package com.cognidiary.app.ui.elder

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.ui.components.DetailTopBar
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BrandPrimary
import com.cognidiary.app.ui.theme.LocalElderText
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextSecondary
import com.cognidiary.app.util.TimeFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * P-E3 日历页。
 *
 * ★ 验收标准：**不做颜色深浅编码** —— 有记录的日子只画一个同色的点，
 *   不用点的颜色/大小表达"录得好不好"。
 */
@Composable
fun ElderCalendarScreen(onBack: () -> Unit) {
    val text = LocalElderText.current
    val repo = ServiceLocator.repository

    var month by remember { mutableStateOf(YearMonth.now()) }

    // 有记录的日期集合
    val recordedDates by produceState<Set<LocalDate>>(initialValue = emptySet()) {
        value = runCatching {
            val sid = ServiceLocator.repository.subject().id
            val records = ServiceLocator.repository.records(sid, limit = 200)
            records.map {
                java.time.Instant.ofEpochMilli(it.recordedAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
            }.toSet()
        }.getOrDefault(emptySet())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding.elder),
    ) {
        DetailTopBar(title = Copy.Elder.CALENDAR_TITLE, onBack = onBack)
        Spacer(modifier = Modifier.height(Spacing.x4))

        // 月份切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(com.cognidiary.app.R.drawable.ic_chevron_left),
                    contentDescription = Copy.Elder.CALENDAR_PREV_MONTH,
                    tint = TextSecondary,
                )
            }
            Text(
                text = TimeFormat.yearMonth(
                    month.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                ),
                fontSize = text.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = Spacing.x4),
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(com.cognidiary.app.R.drawable.ic_chevron_right),
                    contentDescription = Copy.Elder.CALENDAR_NEXT_MONTH,
                    tint = TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.x4))

        // 星期表头（周一起始）
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                Text(
                    text = d,
                    fontSize = text.tiny,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.x2))

        val firstDay = month.atDay(1)
        val offset = firstDay.dayOfWeek.value - 1 // Monday = 0
        val daysInMonth = month.lengthOfMonth()
        val cells = offset + daysInMonth
        val rows = (cells + 6) / 7

        Column {
            repeat(rows) { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    repeat(7) { col ->
                        val cellIndex = row * 7 + col
                        val dayNumber = cellIndex - offset + 1
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (dayNumber in 1..daysInMonth) {
                                val date = month.atDay(dayNumber)
                                val recorded = date in recordedDates
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = dayNumber.toString(),
                                        fontSize = text.secondary,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (recorded) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(BrandPrimary),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = Copy.Elder.CALENDAR_EMPTY,
            fontSize = text.tiny,
            color = TextSecondary,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.x6),
            textAlign = TextAlign.Center,
        )
    }
}
