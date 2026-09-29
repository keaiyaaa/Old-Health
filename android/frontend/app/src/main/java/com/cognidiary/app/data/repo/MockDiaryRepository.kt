package com.cognidiary.app.data.repo

import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.data.model.MetricDirection
import com.cognidiary.app.data.model.MetricKind
import com.cognidiary.app.data.model.MetricView
import com.cognidiary.app.data.model.QualityTag
import com.cognidiary.app.data.model.RecorderRole
import com.cognidiary.app.data.model.StatusCode
import com.cognidiary.app.data.model.StatusView
import com.cognidiary.app.data.model.Subject
import com.cognidiary.app.data.model.SyncState
import com.cognidiary.app.data.model.TaskType
import com.cognidiary.app.ui.copy.Copy
import java.util.Calendar

/**
 * 一期的仓储实现：返回构造好的演示数据。
 *
 * 为什么不是 Room：
 *   本机 Gradle 缓存里没有 KSP 插件，Room 的注解处理跑不起来。
 *   详见 docs/frontend/Android工程实现方案.md 2.2 节 C2。
 *   网络恢复后换 Room + SQLCipher 实现本接口即可，UI 层不受影响。
 *
 * 为什么录音记录放在内存里：
 *   同上（无 Room）。音频文件本身是真实落盘的，见 audio/AudioFileStore.kt。
 */
class MockDiaryRepository : DiaryRepository {

    private val demoSubject = Subject(
        id = "SUBJ-DEMO-0001",
        nickname = "王阿姨",
        relation = "母亲",
        age = 78,
    )

    private val demoRecords: MutableList<DiaryRecord> = buildDemoRecords().toMutableList()

    /**
     * 演示状态。
     *
     * 默认给 S4，是为了让「非疾病解释必须并列」这条规则在界面上**看得见**。
     * 把这里改成 StatusCode.S2 就能看到稳定态的样式。
     */
    private var demoStatusCode = StatusCode.S4

    override suspend fun subject(): Subject = demoSubject

    override suspend fun status(subjectId: String): StatusView {
        val code = demoStatusCode
        return StatusView(
            statusCode = code,
            copyKey = "status.${code.name.lowercase()}",
            validRecordCount = demoRecords.size,
            // S4/S5 必填。若这里返回 null，StatusBanner 会抛异常 —— 这是刻意的机制。
            nonDiseaseExplanation = if (code.requiresNonDiseaseExplanation) {
                Copy.Status.NON_DISEASE_EXPLANATIONS.first()
            } else {
                null
            },
            changedMetricCount = if (code.requiresNonDiseaseExplanation) 1 else 0,
            changedMetricNames = if (code.requiresNonDiseaseExplanation) "说话速度" else "",
            actionRoute = if (code == StatusCode.S5) "family/visit_prep" else null,
        )
    }

    override suspend fun records(subjectId: String, limit: Int): List<DiaryRecord> =
        demoRecords.sortedByDescending { it.recordedAt }.take(limit)

    override suspend fun metrics(subjectId: String): List<MetricView> = listOf(
        MetricView(
            kind = MetricKind.SPEECH_RATE,
            direction = MetricDirection.LOWER,
            statusLabel = "有变化",
            hasEnoughData = true,
            miniTrend = listOf(0.9f, 0.85f, 0.88f, 0.72f, 0.6f, 0.55f, 0.5f),
        ),
        MetricView(
            kind = MetricKind.PAUSE,
            direction = MetricDirection.SAME,
            statusLabel = "稳定",
            hasEnoughData = true,
            miniTrend = listOf(0.5f, 0.52f, 0.48f, 0.51f, 0.49f, 0.5f, 0.51f),
        ),
        MetricView(
            kind = MetricKind.VOCAB,
            direction = MetricDirection.SAME,
            statusLabel = "稳定",
            hasEnoughData = true,
            miniTrend = listOf(0.6f, 0.58f, 0.62f, 0.6f, 0.59f, 0.61f, 0.6f),
        ),
        MetricView(
            kind = MetricKind.COHERENCE,
            direction = MetricDirection.UNKNOWN,
            statusLabel = "数据不足",
            hasEnoughData = false,
            miniTrend = emptyList(),
        ),
    )

    override suspend fun consecutiveMissedDays(subjectId: String): Int = 1

    override suspend fun saveRecord(record: DiaryRecord) {
        demoRecords.add(0, record)
    }

    override suspend fun deleteRecord(recordId: String) {
        demoRecords.removeAll { it.id == recordId }
    }

    override suspend fun setFamilyNote(recordId: String, note: String) {
        val idx = demoRecords.indexOfFirst { it.id == recordId }
        if (idx >= 0) demoRecords[idx] = demoRecords[idx].copy(familyNote = note)
    }

    override suspend fun addEventTag(recordId: String, tag: String) {
        val idx = demoRecords.indexOfFirst { it.id == recordId }
        if (idx >= 0 && tag !in demoRecords[idx].eventTags) {
            demoRecords[idx] = demoRecords[idx].copy(eventTags = demoRecords[idx].eventTags + tag)
        }
    }

    override suspend fun removeEventTag(recordId: String, tag: String) {
        val idx = demoRecords.indexOfFirst { it.id == recordId }
        if (idx >= 0) {
            demoRecords[idx] = demoRecords[idx].copy(eventTags = demoRecords[idx].eventTags - tag)
        }
    }

    // ---------------- 演示数据构造 ----------------

    private fun buildDemoRecords(): List<DiaryRecord> {
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        fun at(daysAgo: Int, hour: Int, minute: Int): Long {
            calendar.timeInMillis = now - daysAgo * day
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }

        val transcriptA = "今天天气挺好的，我早上去公园走了走，碰见老张了，聊了一会儿。"
        val transcriptB = "今天在家收拾了一下屋子，把冬天的衣服找出来了，还有点潮。"
        val transcriptC = "昨天闺女打电话来了，说下周可能回来一趟，我挺高兴的。"

        return listOf(
            DiaryRecord(
                id = "R-001",
                subjectId = demoSubject.id,
                recordedAt = at(0, 19, 42),
                durationMillis = 48_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptA,
                eventTags = listOf("感冒"),
                familyNote = "这周感冒了，嗓子不舒服",
            ),
            DiaryRecord(
                id = "R-002",
                subjectId = demoSubject.id,
                recordedAt = at(1, 19, 30),
                durationMillis = 192_000,
                taskType = TaskType.WEEKLY_PICTURE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptB,
            ),
            DiaryRecord(
                id = "R-003",
                subjectId = demoSubject.id,
                recordedAt = at(2, 20, 5),
                durationMillis = 36_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.FAMILY_PROXY,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                qualityTag = QualityTag.NOISY,
                transcript = transcriptC,
            ),
            DiaryRecord(
                id = "R-004",
                subjectId = demoSubject.id,
                recordedAt = at(3, 19, 15),
                durationMillis = 52_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptA,
            ),
            DiaryRecord(
                id = "R-005",
                subjectId = demoSubject.id,
                recordedAt = at(4, 19, 55),
                durationMillis = 61_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptB,
            ),
            DiaryRecord(
                id = "R-006",
                subjectId = demoSubject.id,
                recordedAt = at(6, 20, 20),
                durationMillis = 44_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptC,
            ),
            DiaryRecord(
                id = "R-007",
                subjectId = demoSubject.id,
                recordedAt = at(8, 19, 40),
                durationMillis = 176_000,
                taskType = TaskType.WEEKLY_PICTURE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptA,
            ),
            DiaryRecord(
                id = "R-008",
                subjectId = demoSubject.id,
                recordedAt = at(10, 19, 25),
                durationMillis = 39_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                qualityTag = QualityTag.NO_TRANSCRIPT,
                transcript = null,
            ),
            DiaryRecord(
                id = "R-009",
                subjectId = demoSubject.id,
                recordedAt = at(12, 20, 10),
                durationMillis = 57_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptB,
            ),
            DiaryRecord(
                id = "R-010",
                subjectId = demoSubject.id,
                recordedAt = at(14, 19, 50),
                durationMillis = 63_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptC,
            ),
            DiaryRecord(
                id = "R-011",
                subjectId = demoSubject.id,
                recordedAt = at(16, 19, 35),
                durationMillis = 46_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptA,
            ),
            DiaryRecord(
                id = "R-012",
                subjectId = demoSubject.id,
                recordedAt = at(18, 20, 0),
                durationMillis = 51_000,
                taskType = TaskType.DAILY_FREE,
                recorderRole = RecorderRole.ELDER_SESSION,
                localAudioPath = null,
                syncState = SyncState.UPLOADED,
                transcript = transcriptB,
            ),
        )
    }
}
