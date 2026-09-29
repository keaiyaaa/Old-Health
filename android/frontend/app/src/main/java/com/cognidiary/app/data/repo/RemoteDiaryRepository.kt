package com.cognidiary.app.data.repo

import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.data.model.MetricDirection
import com.cognidiary.app.data.model.MetricKind
import com.cognidiary.app.data.model.MetricTrend
import com.cognidiary.app.data.model.MetricView
import com.cognidiary.app.data.model.QualityTag
import com.cognidiary.app.data.model.TrendPoint
import com.cognidiary.app.data.model.RecorderRole
import com.cognidiary.app.data.model.StatusView
import com.cognidiary.app.data.model.StatusCode
import com.cognidiary.app.data.model.Subject
import com.cognidiary.app.data.model.SyncState
import com.cognidiary.app.data.model.TaskType
import com.cognidiary.app.data.remote.ApiClient
import com.cognidiary.app.data.remote.TokenStore
import com.cognidiary.app.ui.copy.Copy
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 真实后端实现（docs/api/ 契约）。
 *
 * - 指标 / 状态 / 趋势全部来自服务端；客户端不计算、不解读（B2/C5）
 * - 音频走预签名 URL 直传对象存储（API-02 时序），不经业务服务器
 * - 老人端会话锁定 subjectId（API-01 1.13）
 */
class RemoteDiaryRepository(
    private val api: ApiClient,
    private val tokens: TokenStore,
) : DiaryRepository {

    private fun lockedSubjectId(): String =
        tokens.lockedSubjectId
            ?: throw IllegalStateException("SUBJECT_NOT_SELECTED")

    // ---------------- 账号侧 ----------------

    suspend fun familySubjects(): List<AuthRepository.SubjectBrief> = withContext(Dispatchers.IO) {
        val json = api.get("/v1/subjects") ?: return@withContext emptyList()
        val items = json.optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            AuthRepository.SubjectBrief(
                subjectId = o.optString("subjectId"),
                displayName = o.optString("displayName"),
                ageBand = o.optString("ageBand"),
                validRecordCount = o.optInt("validRecordCount"),
            )
        }
    }

    // ---------------- DiaryRepository ----------------

    override suspend fun subject(): Subject = withContext(Dispatchers.IO) {
        // 老人端：会话锁定的 subjectId；家属端：取绑定的第一位老人（API-01 1.8）
        val sid = tokens.lockedSubjectId ?: run {
            val list = api.get("/v1/subjects")
            list?.optJSONArray("items")?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)?.optString("subjectId")
        } ?: return@withContext Subject(id = "", nickname = "", relation = "", age = 0)
        val json = api.get("/v1/subjects")
        val items = json?.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                if (o.optString("subjectId") == sid) {
                    return@withContext Subject(
                        id = sid,
                        nickname = o.optString("displayName"),
                        relation = "",
                        age = ageBandMid(o.optString("ageBand")),
                    )
                }
            }
        }
        Subject(id = sid, nickname = "", relation = "", age = 0)
    }

    override suspend fun status(subjectId: String): StatusView = withContext(Dispatchers.IO) {
        val json = api.get("/v1/status/$subjectId")
            ?: throw IllegalStateException("empty status")
        val code = runCatching { StatusCode.valueOf(json.optString("statusCode")) }
            .getOrDefault(StatusCode.S0)
        // S4/S5 的非疾病解释：服务端只给 key（合规原则 1），客户端从 Copy 取文案
        val hasNonDisease = !json.isNull("nonDiseaseKey") &&
            json.optString("nonDiseaseKey").isNotBlank()
        val explanation = if (hasNonDisease) {
            Copy.Status.NON_DISEASE_EXPLANATIONS[
                (json.optString("nonDiseaseKey").hashCode()
                    .let { if (it < 0) -it else it }) %
                    Copy.Status.NON_DISEASE_EXPLANATIONS.size]
        } else null
        StatusView(
            statusCode = code,
            copyKey = json.optString("copyKey", "status." + code.name.lowercase()),
            validRecordCount = json.optInt("validRecordCount"),
            nonDiseaseExplanation = explanation,
            actionRoute = if (code == StatusCode.S5) "family/records" else null,
        )
    }

    override suspend fun records(subjectId: String, limit: Int): List<DiaryRecord> =
        withContext(Dispatchers.IO) {
        val json = api.get("/v1/records", query = mapOf(
            "subjectId" to subjectId, "limit" to limit.coerceIn(1, 100).toString()))
            ?: return@withContext emptyList()
        val items = json.optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            DiaryRecord(
                id = o.optString("recordId"),
                subjectId = subjectId,
                recordedAt = o.optLong("recordedAt"),
                durationMillis = o.optLong("durationSec") * 1000,
                taskType = runCatching { TaskType.valueOf(o.optString("taskType")) }
                    .getOrDefault(TaskType.DAILY_FREE),
                recorderRole = runCatching { RecorderRole.valueOf(o.optString("recorderRole")) }
                    .getOrDefault(RecorderRole.FAMILY_PROXY),
                localAudioPath = null, // 客户端专用字段，接口不传（字段设计表 4）
                syncState = SyncState.UPLOADED,
                qualityTag = qualityTagOf(o.optString("qualityFlag")),
                transcript = null,
                familyNote = if (o.optBoolean("hasNote")) "" else null,
                eventTags = o.optJSONArray("eventTags")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
            )
        }
    }

    override suspend fun metrics(subjectId: String): List<MetricView> =
        withContext(Dispatchers.IO) {
        val base = api.get("/v1/metrics/$subjectId/baseline") ?: return@withContext emptyList()
        val items = base.optJSONArray("items") ?: return@withContext emptyList()
        val established = mutableMapOf<String, Boolean>()
        val needed = mutableMapOf<String, Int>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            established[o.optString("metricKey")] = o.optBoolean("isEstablished")
            needed[o.optString("metricKey")] = o.optInt("progressNeeded")
        }
        MetricKind.entries.map { kind ->
            val isEstablished = established[kind.name] == true
            MetricView(
                kind = kind,
                direction = directionOf(kind, subjectId),
                statusLabel = when {
                    !isEstablished -> "数据不足"
                    directionOf(kind, subjectId) == MetricDirection.SAME -> "稳定"
                    else -> "有变化"
                },
                hasEnoughData = isEstablished,
                miniTrend = miniTrend(kind, subjectId),
            )
        }
    }

    override suspend fun consecutiveMissedDays(subjectId: String): Int =
        withContext(Dispatchers.IO) {
            val json = api.get("/v1/subjects/$subjectId/reminder") ?: return@withContext 0
            json.optInt("consecutiveMissedDays")
        }

    /**
     * 录音上云：upload-url → PUT 直传 → 登记（API-02 时序）。
     * 登记（POST /v1/records）是最需要幂等的接口：弱网重试靠 Idempotency-Key 兜底。
     */
    override suspend fun saveRecord(record: DiaryRecord): Unit =
        withContext<Unit>(Dispatchers.IO) {
        val audio = record.localAudioPath?.let { File(it) }
            ?: throw IllegalStateException("缺少音频文件")
        val contentType = "audio/mp4"
        val bytes = audio.readBytes()
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        val up = api.post("/v1/records/upload-url", JSONObject()
            .put("subjectId", record.subjectId)
            .put("contentType", contentType)
            .put("sizeBytes", bytes.size)
            .put("durationSec", (record.durationMillis / 1000).toInt())
            .put("taskType", record.taskType.name)
        ) ?: throw IOException("获取上传地址失败")

        presignPut(up.optString("uploadUrl"), contentType, bytes)

        api.post("/v1/records", JSONObject()
            .put("sessionId", up.optString("sessionId"))
            .put("objectKey", up.optString("objectKey"))
            .put("sha256", sha)
            .put("subjectId", record.subjectId)
            .put("recorderRole", record.recorderRole.name)
            .put("taskType", record.taskType.name)
            .put("durationSec", (record.durationMillis / 1000).toInt())
            .put("sampleRate", 16000)
            .put("actualStartedAt", record.recordedAt)
        )
    }

    override suspend fun deleteRecord(recordId: String): Unit =
        withContext<Unit>(Dispatchers.IO) {
            api.delete("/v1/records/$recordId")
        }

    override suspend fun setFamilyNote(recordId: String, note: String): Unit =
        withContext<Unit>(Dispatchers.IO) {
            api.put("/v1/records/family-note/$recordId", JSONObject().put("note", note))
        }

    override suspend fun addEventTag(recordId: String, tag: String): Unit =
        withContext<Unit>(Dispatchers.IO) {
            api.post("/v1/records/$recordId/events", JSONObject()
                .put("type", eventTagType(tag))
                .put("note", tag))
        }

    override suspend fun removeEventTag(recordId: String, tag: String): Unit =
        withContext<Unit>(Dispatchers.IO) {
            // 接口按 eventId 删除；先从详情里按类型找到对应事件
            val detail = api.get("/v1/records/$recordId")
                ?: return@withContext
            val events = detail.optJSONArray("eventTags")
                ?: return@withContext
            for (i in 0 until events.length()) {
                val o = events.getJSONObject(i)
                if (o.optString("type") == eventTagType(tag)) {
                    api.delete("/v1/records/$recordId/events/${o.optString("eventId")}")
                    return@withContext
                }
            }
        }

    /**
     * 四个指标的时间趋势（横轴=时间）。每指标一次序列请求，点内含真实时间戳；
     * 纵轴在本层做序列内归一化（0–1），不含真实量纲（字段设计表 3.3）。
     */
    suspend fun trendSeries(subjectId: String): List<MetricTrend> =
        withContext(Dispatchers.IO) {
            MetricKind.entries.map { kind ->
                val raw = rawSeries(subjectId, kind)
                val values = raw.mapNotNull { it.second }
                val min = values.minOrNull()
                val max = values.maxOrNull()
                val range = (max ?: 0f) - (min ?: 0f)
                val points = raw.mapNotNull { (t, v) ->
                    v?.let {
                        val n = if (range > 1e-9f) ((it - min!!) / range).toFloat() else 0.5f
                        TrendPoint(recordedAt = t, normalized = n)
                    }
                }
                val lastBand = raw.lastOrNull()?.third
                val direction = when (lastBand) {
                    "BELOW_MILD", "BELOW_STRONG" -> MetricDirection.LOWER
                    "ABOVE_MILD" -> MetricDirection.HIGHER
                    "WITHIN" -> MetricDirection.SAME
                    else -> MetricDirection.UNKNOWN
                }
                MetricTrend(
                    kind = kind,
                    points = points,
                    hasEnoughData = points.size >= 4,
                    direction = direction,
                    statusLabel = when {
                        points.size < 4 -> "数据不足"
                        direction == MetricDirection.SAME -> "跟平时差不多"
                        else -> "跟平时不太一样"
                    },
                )
            }
        }

    // ---------------- 私有工具 ----------------

    /** 原始序列：(时间戳, 值或 null(无效样本), zBand) */
    private fun rawSeries(
        subjectId: String,
        kind: MetricKind,
    ): List<Triple<Long, Float?, String?>> {
        val json = api.get("/v1/metrics/$subjectId/series", query = mapOf(
            "key" to kind.name, "range" to "30d")) ?: return emptyList()
        val points = json.optJSONArray("points") ?: return emptyList()
        return (0 until points.length()).mapNotNull { i ->
            val o = points.getJSONObject(i)
            val v = o.optDouble("value", Double.NaN).takeUnless { it.isNaN() }
            Triple(
                o.optLong("recordedAt"),
                v?.toFloat(),
                if (o.isNull("zBand")) null else o.optString("zBand"),
            )
        }
    }

    private fun presignPut(url: String, contentType: String, bytes: ByteArray) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(bytes.size)
            outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) throw IOException("音频上传失败: $code")
    }

    private fun series(subjectId: String, kind: MetricKind): org.json.JSONArray? {
        val json = api.get("/v1/metrics/$subjectId/series", query = mapOf(
            "key" to kind.name, "range" to "30d")) ?: return null
        return json.optJSONArray("points")
    }

    private fun directionOf(kind: MetricKind, subjectId: String): MetricDirection {
        val points = series(subjectId, kind) ?: return MetricDirection.UNKNOWN
        if (points.length() < 2) return MetricDirection.UNKNOWN
        val last = points.getJSONObject(points.length() - 1)
        return when (last.optString("zBand")) {
            "BELOW_MILD", "BELOW_STRONG" -> MetricDirection.LOWER
            "ABOVE_MILD" -> MetricDirection.HIGHER
            "WITHIN" -> MetricDirection.SAME
            else -> MetricDirection.UNKNOWN
        }
    }

    /** 归一化迷你走势（0–1），不含真实量纲（字段设计表 3.3）。 */
    private fun miniTrend(kind: MetricKind, subjectId: String): List<Float> {
        val points = series(subjectId, kind) ?: return emptyList()
        val values = (0 until points.length()).mapNotNull { i ->
            points.getJSONObject(i).optDouble("value", Double.NaN)
                .takeUnless { it.isNaN() }
        }
        if (values.size < 2) return emptyList()
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 1e-9 } ?: return values.map { 0.5f }
        return values.map { ((it - min) / range).toFloat() }
    }

    private fun qualityTagOf(flag: String): QualityTag = when (flag) {
        "SUSPECT" -> QualityTag.NOISY
        "INVALID" -> QualityTag.NO_TRANSCRIPT
        else -> QualityTag.NORMAL
    }

    /** 中文标注 → 枚举（API-04 4.5）。 */
    private fun eventTagType(tag: String): String = when (tag) {
        "感冒" -> "COLD"
        "失眠" -> "MOVE"
        "住院" -> "HOSPITAL"
        "旅行" -> "TRAVEL"
        else -> "OTHER"
    }

    private fun ageBandMid(band: String): Int = when (band) {
        "60-64" -> 62
        "65-69" -> 67
        "70-74" -> 72
        "75-79" -> 77
        "80-84" -> 82
        "85+" -> 87
        else -> 0
    }
}
