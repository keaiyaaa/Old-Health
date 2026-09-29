package com.cognidiary.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * MediaRecorder 封装。
 *
 * 硬规则（docs/frontend/modules/录音采集与上传.md §5.2）：
 *   **任何一步失败都不影响「音频文件已经存在」这个事实。**
 *   所以 [stop] 一定返回 File 或明确返回 null，不抛异常让调用方去猜。
 *
 * 另一条规则：录音时长 <10 秒时**静默丢弃，不提示失败**。
 * 判断放在调用方（ViewModel），因为"丢弃"是产品决策，不是技术决策。
 */
class AudioRecorder(private val context: Context) {

    enum class State { IDLE, RECORDING, ERROR }

    var state: State = State.IDLE
        private set

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtElapsed = 0L

    /** 最长录音时长 5 分钟，超时自动结束（FR-1.3） */
    val maxDurationMillis: Long = 5 * 60 * 1000L

    /** 低于这个时长静默丢弃（FR-1.3 / 模块文档 §7） */
    val minDurationMillis: Long = 10 * 1000L

    /** 10–15 秒保留但标记存疑，不计入趋势 */
    val questionableDurationMillis: Long = 15 * 1000L

    fun start(): Boolean {
        releaseQuietly()
        val file = AudioFileStore.newFile(context)
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(96000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            currentFile = file
            startedAtElapsed = SystemClock.elapsedRealtime()
            state = State.RECORDING
            true
        } catch (t: Throwable) {
            runCatching { mr.release() }
            AudioFileStore.delete(file.absolutePath)
            recorder = null
            currentFile = null
            state = State.ERROR
            false
        }
    }

    fun elapsedMillis(): Long =
        if (state == State.RECORDING) SystemClock.elapsedRealtime() - startedAtElapsed else 0L

    fun isRecording(): Boolean = state == State.RECORDING

    /**
     * 停止并返回落盘文件。
     *
     * 返回 null 表示本次没有产生可用文件 —— 调用方按「静默丢弃」处理，
     * **不要**向老人展示为失败。
     */
    fun stop(): File? {
        val mr = recorder ?: return null
        val file = currentFile
        return try {
            mr.stop()
            mr.release()
            state = State.IDLE
            recorder = null
            currentFile = null
            if (file != null && file.exists() && file.length() > 0L) file else null
        } catch (t: Throwable) {
            // stop() 抛异常通常意味着录制时间过短、编码器没有写出有效数据。
            // 这属于「静默丢弃」路径，不向用户暴露为失败。
            runCatching { mr.release() }
            recorder = null
            currentFile = null
            state = State.IDLE
            file?.let { AudioFileStore.delete(it.absolutePath) }
            null
        }
    }

    /**
     * 录音被系统中断（来电、其他 App 占麦）时调用。
     * 已录部分尽量保住 —— 保不住也不算失败，只是这段没了。
     */
    fun onSystemInterruption(): File? = stop()

    fun releaseQuietly() {
        runCatching { recorder?.release() }
        recorder = null
        currentFile = null
        state = State.IDLE
    }
}
