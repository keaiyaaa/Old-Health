package com.cognidiary.app.ui.elder

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.cognidiary.app.audio.AudioFileStore
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.ui.components.PrimaryButton
import com.cognidiary.app.ui.copy.Copy
import com.cognidiary.app.ui.theme.BrandDeep
import com.cognidiary.app.ui.theme.LocalElderText
import com.cognidiary.app.ui.theme.ScreenPadding
import com.cognidiary.app.ui.theme.Spacing
import com.cognidiary.app.util.TimeFormat
import kotlinx.coroutines.launch

/** 完成页正向反馈文案「禁止连续两次相同」——用进程级变量记住上一条 */
private var lastDoneMessage: String? = null

private fun pickDoneMessage(): String {
    val candidates = Copy.Elder.DONE_MESSAGES.filter { it != lastDoneMessage }
    val picked = (candidates.ifEmpty { Copy.Elder.DONE_MESSAGES }).random()
    lastDoneMessage = picked
    return picked
}

/**
 * P-E2 完成页。只有好消息：随机正向文案（不连续重复）+ 时长 + 回放/重录/完成。
 * 无指标、无"录得怎么样"的任何评价。回放失败对老人表现为"没反应"，不提示错误。
 *
 * 重录限 1 次：删除这条记录（含音频文件）并回到录音态（模块文档 §2）。
 */
@Composable
fun ElderDoneScreen(
    recordId: String,
    onFinished: () -> Unit,
    onRerecord: () -> Unit,
) {
    val text = LocalElderText.current
    val context = LocalContext.current
    val repo = ServiceLocator.repository
    val scope = rememberCoroutineScope()

    val record by produceState<DiaryRecord?>(initialValue = null, key1 = recordId) {
        value = runCatching {
            val sid = repo.subject().id
            repo.records(sid, limit = 100).firstOrNull { it.id == recordId }
        }.getOrNull()
    }

    val message = remember(recordId) { pickDoneMessage() }
    var reRecordUsed by remember { mutableStateOf(false) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        com.cognidiary.app.ui.components.BackButton(
            onBack = onFinished,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = Spacing.x2, top = Spacing.x1),
        )

        Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding.elder),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            fontSize = text.pageTitle,
            fontWeight = FontWeight.Medium,
            color = BrandDeep,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        record?.let { r ->
            Spacer(modifier = Modifier.height(text.gapX4))
            Text(
                text = TimeFormat.durationHuman(r.durationMillis),
                fontSize = text.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(text.gapX8))

            if (r.localAudioPath != null) {
                OutlinedButton(
                    onClick = {
                        if (playing) {
                            runCatching { player?.stop() }
                            runCatching { player?.release() }
                            player = null
                            playing = false
                        } else {
                            runCatching {
                                val p = MediaPlayer()
                                p.setDataSource(r.localAudioPath)
                                p.prepare()
                                p.setOnCompletionListener {
                                    it.release()
                                    player = null
                                    playing = false
                                }
                                p.start()
                                player = p
                                playing = true
                            }.onFailure {
                                runCatching { player?.release() }
                                player = null
                                playing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (playing) Copy.Elder.STOP_PLAYBACK else Copy.Elder.REPLAY,
                        fontSize = text.button,
                    )
                }
                Spacer(modifier = Modifier.height(text.gapX3))
            }
        }

        OutlinedButton(
            onClick = {
                if (reRecordUsed) return@OutlinedButton
                reRecordUsed = true
                runCatching { player?.release() }
                player = null
                val current = record
                if (current != null) {
                    AudioFileStore.delete(current.localAudioPath)
                    scope.launch { repo.deleteRecord(current.id) }
                }
                onRerecord()
            },
            enabled = !reRecordUsed,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = Copy.Elder.RERECORD, fontSize = text.button)
        }

        Spacer(modifier = Modifier.height(text.gapX3))
        PrimaryButton(text = Copy.Elder.DONE, onClick = onFinished)
        }
    }
}
