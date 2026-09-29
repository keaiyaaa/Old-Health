package com.cognidiary.app.ui.elder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cognidiary.app.audio.AudioRecorder
import com.cognidiary.app.data.ServiceLocator
import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.data.model.QualityTag
import com.cognidiary.app.data.model.RecorderRole
import com.cognidiary.app.data.model.SyncState
import com.cognidiary.app.data.model.TaskType
import com.cognidiary.app.ui.copy.Copy
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 录音页状态机（docs/frontend/modules/录音采集与上传.md §2/§5）：
 *
 *   IDLE → RECORDING → SAVING → (跳 P-E2 | 静默回 IDLE)
 *
 * 硬规则：
 *   - 时长 <10 秒：静默丢弃，不提示失败，不跳转
 *   - 时长 10–15 秒：保留但标记 TOO_SHORT（不计入趋势，由服务端裁决）
 *   - ≥5 分钟：自动结束，正常走完成流程
 *   - 系统中断（来电/切走）：保住已录部分，回 IDLE 并提示"已经存好了"，不跳转
 *   - 任何失败都不产生"错误"文案 —— 老人端只呈现好消息或中性消息
 */
class ElderRecordViewModel(application: Application) : AndroidViewModel(application) {

    enum class Phase { IDLE, RECORDING, SAVING }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val elapsedMillis: Long = 0L,
        val topicIndex: Int = 0,
        val greeting: String = "",
        /** 中断后的一条中性提示，展示一次后可清除 */
        val savedHint: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 一次性导航事件：录音成功保存后跳 P-E2 */
    private val _doneEvent = MutableStateFlow<String?>(null)
    val doneEvent: StateFlow<String?> = _doneEvent

    private val recorder: AudioRecorder = ServiceLocator.newRecorder()
    private val repo = ServiceLocator.repository

    private var tickerJob: Job? = null
    private var lastTopicIndex: Int = -1

    init {
        viewModelScope.launch {
            // 后端不可达时不打扰老人：问候语降级为通用文案（§7 异常降级表）
            val subject = runCatching { repo.subject() }.getOrNull() ?: return@launch
            val last = runCatching {
                repo.records(subject.id, limit = 1).firstOrNull()
            }.getOrNull()
            _state.update {
                it.copy(
                    greeting = subject.nickname + Copy.Elder.GREETING_SUFFIX,
                    savedHint = null,
                )
            }
            last?.let { r ->
                _state.update {
                    it.copy(
                        savedHint = Copy.Elder.LAST_RECORD_PREFIX +
                            com.cognidiary.app.util.TimeFormat.relative(r.recordedAt),
                    )
                }
            }
        }
    }

    /** 话题卡轮换：保证与上一次不同（验收：轮换不重复） */
    fun nextTopic() {
        val total = Copy.TOPIC_CARDS.size
        var next = (0 until total).random()
        if (total > 1 && next == lastTopicIndex) next = (next + 1) % total
        lastTopicIndex = next
        _state.update { it.copy(topicIndex = next) }
    }

    fun clearSavedHint() {
        _state.update { it.copy(savedHint = null) }
    }

    fun startRecording() {
        if (_state.value.phase != Phase.IDLE) return
        val started = recorder.start()
        if (!started) {
            // 麦克风被占用等原因。中性文案，不做错误提示（§7 异常降级表）。
            _state.update { it.copy(savedHint = Copy.Elder.RETRY_LATER) }
            return
        }
        _state.update { it.copy(phase = Phase.RECORDING, elapsedMillis = 0L) }
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                val elapsed = recorder.elapsedMillis()
                _state.update { it.copy(elapsedMillis = elapsed) }
                if (elapsed >= recorder.maxDurationMillis) {
                    stopRecording(auto = true)
                    break
                }
            }
        }
    }

    fun stopRecording(auto: Boolean = false) {
        if (_state.value.phase != Phase.RECORDING) return
        tickerJob?.cancel()
        _state.update { it.copy(phase = Phase.SAVING) }

        val duration = recorder.elapsedMillis()
        val file = recorder.stop()

        // 时长 <10 秒：静默丢弃（AudioRecorder.stop 对无效文件也返回 null）
        if (file == null || duration < recorder.minDurationMillis) {
            file?.let { com.cognidiary.app.audio.AudioFileStore.delete(it.absolutePath) }
            _state.update { it.copy(phase = Phase.IDLE, elapsedMillis = 0L) }
            return
        }

        val record = DiaryRecord(
            id = "R-" + UUID.randomUUID().toString().take(8),
            subjectId = "",
            recordedAt = System.currentTimeMillis(),
            durationMillis = duration,
            taskType = TaskType.DAILY_FREE,
            recorderRole = RecorderRole.ELDER_SESSION,
            localAudioPath = file.absolutePath,
            syncState = SyncState.LOCAL_ONLY,
            qualityTag = if (duration < recorder.questionableDurationMillis) {
                QualityTag.TOO_SHORT
            } else {
                QualityTag.NORMAL
            },
        )

        viewModelScope.launch {
            // 上传失败不阻塞老人：记录状态置 FAILED，本地文件保留待补传
            val saved = runCatching { repo.saveRecord(record) }.isSuccess
            _state.update {
                it.copy(
                    phase = Phase.IDLE,
                    elapsedMillis = 0L,
                    savedHint = if (auto) Copy.Elder.TIMEOUT_END else null,
                )
            }
            if (saved) _doneEvent.value = record.id
        }
    }

    /** 录音被系统中断（来电、切到后台）。已录部分尽量保住，回 IDLE 不跳转。 */
    fun onSystemInterruption() {
        if (_state.value.phase != Phase.RECORDING) return
        tickerJob?.cancel()
        val duration = recorder.elapsedMillis()
        val file = recorder.onSystemInterruption()

        if (file == null || duration < recorder.minDurationMillis) {
            file?.let { com.cognidiary.app.audio.AudioFileStore.delete(it.absolutePath) }
            _state.update { it.copy(phase = Phase.IDLE, elapsedMillis = 0L) }
            return
        }

        val record = DiaryRecord(
            id = "R-" + UUID.randomUUID().toString().take(8),
            subjectId = "",
            recordedAt = System.currentTimeMillis(),
            durationMillis = duration,
            taskType = TaskType.DAILY_FREE,
            recorderRole = RecorderRole.ELDER_SESSION,
            localAudioPath = file.absolutePath,
            syncState = SyncState.LOCAL_ONLY,
            qualityTag = if (duration < recorder.questionableDurationMillis) {
                QualityTag.TOO_SHORT
            } else {
                QualityTag.NORMAL
            },
        )

        viewModelScope.launch {
            runCatching { repo.saveRecord(record) }
            _state.update {
                it.copy(
                    phase = Phase.IDLE,
                    elapsedMillis = 0L,
                    savedHint = Copy.Elder.OFFLINE_SAVED,
                )
            }
        }
    }

    fun consumeDoneEvent() {
        _doneEvent.value = null
    }

    override fun onCleared() {
        tickerJob?.cancel()
        recorder.releaseQuietly()
        super.onCleared()
    }
}
