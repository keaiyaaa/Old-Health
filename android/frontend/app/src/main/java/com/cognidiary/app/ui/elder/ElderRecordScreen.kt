package com.cognidiary.app.ui.elder

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cognidiary.app.ui.components.BigRecordButton
import com.cognidiary.app.ui.components.InfoBlock
import com.cognidiary.app.ui.components.RecordButtonState
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BgCard
import com.cognidiary.app.ui.theme.BrandDeep
import com.cognidiary.app.ui.theme.LocalElderText
import com.cognidiary.app.ui.theme.Radius
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.ui.theme.TextSecondary

/**
 * P-E1 录音页 —— 老人端唯一首页，状态机页面（非多路由）。
 *
 * ★ 老人端硬约束：只有一个大按钮 + 话题卡，无指标、无趋势、无错误提示、无倒计时。
 *   录音按钮在屏幕下半区（单手可及）。
 */
@Composable
fun ElderRecordScreen(
    onDone: (String) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: ElderRecordViewModel = viewModel()
    val ui by vm.state.collectAsState()
    val doneId by vm.doneEvent.collectAsState()
    val text = LocalElderText.current
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) vm.startRecording()
    }

    // 录音中切走 / 来电：保住已录部分（§7 异常降级表）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                vm.onSystemInterruption()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 保存成功 → P-E2
    androidx.compose.runtime.LaunchedEffect(doneId) {
        doneId?.let {
            onDone(it)
            vm.consumeDoneEvent()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenPadding.elder),
        ) {
            // 顶部工具行：日历 + 设置（≥48dp 点击区）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                IconButton(onClick = onOpenCalendar) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.cognidiary.app.R.drawable.ic_calendar),
                        contentDescription = Copy.Elder.CALENDAR_TITLE,
                        tint = TextSecondary,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.cognidiary.app.R.drawable.ic_settings),
                        contentDescription = Copy.Elder.SETTINGS_TITLE,
                        tint = TextSecondary,
                    )
                }
            }

            if (ui.savedHint != null) {
                InfoBlock(text = ui.savedHint.orEmpty())
                Spacer(modifier = Modifier.height(Spacing.x3))
            }

            Spacer(modifier = Modifier.height(text.gapX6))
            Text(
                text = ui.greeting,
                fontSize = text.pageTitle,
                fontWeight = FontWeight.Medium,
                color = BrandDeep,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(text.gapX8))

            // 话题卡：点击换一条（FR-1.2）
            TopicCard(
                topic = Copy.TOPIC_CARDS.getOrElse(ui.topicIndex) { Copy.TOPIC_CARDS.first() },
                enabled = ui.phase == ElderRecordViewModel.Phase.IDLE,
                onClick = { vm.nextTopic() },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = if (ui.phase == ElderRecordViewModel.Phase.RECORDING) {
                    Copy.Elder.RECORDING_HINT
                } else {
                    Copy.Elder.RECORD_HINT
                },
                fontSize = text.secondary,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(text.gapX6))

            BigRecordButton(
                state = when {
                    !hasPermission -> RecordButtonState.PERMISSION_MISSING
                    ui.phase == ElderRecordViewModel.Phase.RECORDING -> RecordButtonState.RECORDING
                    else -> RecordButtonState.IDLE
                },
                elapsedMillis = ui.elapsedMillis,
                onClick = {
                    when {
                        !hasPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        ui.phase == ElderRecordViewModel.Phase.RECORDING -> vm.stopRecording()
                        else -> vm.startRecording()
                    }
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            // 录音中：随时可以停（FR-1.3 / 模块文档 §7）
            if (ui.phase == ElderRecordViewModel.Phase.RECORDING) {
                Spacer(modifier = Modifier.height(text.gapX6))
                com.cognidiary.app.ui.components.PrimaryButton(
                    text = Copy.Elder.FINISH_SPEAK,
                    onClick = { vm.stopRecording() },
                )
            }
            Spacer(modifier = Modifier.height(text.gapX8))
        }
    }
}

@Composable
private fun TopicCard(
    topic: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = LocalElderText.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.topicCard))
            .background(BgCard)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.x6, vertical = Spacing.x6),
    ) {
        Text(
            text = topic,
            fontSize = text.topicCard,
            lineHeight = text.topicCard * 1.4f,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
