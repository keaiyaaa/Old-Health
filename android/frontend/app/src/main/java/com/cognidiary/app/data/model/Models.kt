package com.cognidiary.app.data.model

/*
 * 数据模型。
 *
 * 两条与合规直接相关的设计：
 *
 * 1. [MetricView] 【没有数值字段】。客户端拿不到原始数值，也就无法"顺手"把它显示出来。
 *    用类型层面杜绝"显示原始数值"这个风险，而不是靠开发者自觉。
 *
 * 2. [StatusCode] 只在服务端计算，客户端只渲染。
 *    客户端不存在任何"根据指标自行升级/降级状态"的代码。
 *    见 docs/frontend/modules/状态与提醒.md 3.3。
 */

/** 状态机 S0–S6 */
enum class StatusCode {
    /** 未开始 */
    S0,

    /** 基线建立中 */
    S1,

    /** 稳定 */
    S2,

    /** 数据不足 / 波动，先不下结论 */
    S3,

    /** 需要关注（必须并列非疾病解释） */
    S4,

    /** 建议就医评估（必须并列非疾病解释） */
    S5,

    /** 已就医 / 已处理 */
    S6;

    /** 这两档强制要求非疾病解释，缺失则 StatusBanner 抛异常 */
    val requiresNonDiseaseExplanation: Boolean
        get() = this == S4 || this == S5
}

/** 指标偏离方向。只有方向，没有幅度。 */
enum class MetricDirection {
    LOWER,
    SAME,
    HIGHER,
    UNKNOWN,
}

/** 四个指标（2026-09-28 对齐 docs/api/字段设计表.md：SPEECH_RATE / PAUSE / VOCAB / COHERENCE） */
enum class MetricKind(val displayName: String) {
    SPEECH_RATE("说话速度"),
    PAUSE("说话流畅度"),
    VOCAB("用词丰富程度"),
    COHERENCE("说话的连贯性"),
}

/**
 * 指标视图。
 *
 * 刻意不提供 zScore / mean / stdDev / 数值 等字段。
 * 服务端返回的是分档（zBand），不是分数 —— 见 docs/api/API-03-指标与趋势.md。
 */
data class MetricView(
    val kind: MetricKind,
    val direction: MetricDirection,
    /** "稳定" / "有变化" / "数据不足" */
    val statusLabel: String,
    val hasEnoughData: Boolean,
    /** 迷你折线的相对走势（已归一化，不含真实量纲） */
    val miniTrend: List<Float> = emptyList(),
)

/** 状态视图 */
data class StatusView(
    val statusCode: StatusCode,
    /** 服务端给的文案 key，客户端只按 key 取文案，不拼接、不加工 */
    val copyKey: String,
    /** 有效记录数，用于数据充分度条 */
    val validRecordCount: Int,
    /** S4/S5 必填 */
    val nonDiseaseExplanation: String?,
    /** S4/S5 文案里"有 N 项比平时低一些"的 N */
    val changedMetricCount: Int = 0,
    /** S4/S5 文案里列出的指标名 */
    val changedMetricNames: String = "",
    /** 行动入口：S5 → "family/visit_prep"，其余为 null */
    val actionRoute: String? = null,
)

/** 采集任务类型 */
enum class TaskType(val displayName: String) {
    /** 每日自由叙述 30–60 秒（主线） */
    DAILY_FREE("每日叙述"),

    /** 每周图片描述约 60 秒（效度锚点） */
    WEEKLY_PICTURE("图片描述"),

    /** 每周语义流畅性 60 秒 */
    WEEKLY_FLUENCY("语义流畅性"),

    /** 每周自由叙述（2026-09-28 补齐，对齐 API-02 一期 4 种任务） */
    WEEKLY_FREE("每周自由叙述"),
}

/**
 * 谁录的（2026-09-28 更名对齐字段设计表：ELDER_SESSION / FAMILY_PROXY）。
 * 代录的记录必须在界面上可识别，否则会污染论文数据 —— 见原型设计说明书附录 D。
 */
enum class RecorderRole(val displayName: String) {
    ELDER_SESSION("本人录制"),
    FAMILY_PROXY("家属代录"),
}

enum class SyncState {
    /** 只在本地，还没传上去 */
    LOCAL_ONLY,
    UPLOADING,
    UPLOADED,
    FAILED,
}

/** 录音质量标签。注意用词：不出现"合格/不合格"。 */
enum class QualityTag(val displayName: String) {
    NORMAL("正常"),
    NOISY("环境偏吵（不影响录音）"),
    TOO_SHORT("时长偏短"),
    TOO_MUCH_SILENCE("静音偏多"),
    NO_TRANSCRIPT("文本分析未完成"),
}

/** 一次录音记录 */
data class DiaryRecord(
    val id: String,
    val subjectId: String,
    val recordedAt: Long,
    val durationMillis: Long,
    val taskType: TaskType,
    val recorderRole: RecorderRole,
    val localAudioPath: String?,
    val syncState: SyncState,
    val qualityTag: QualityTag = QualityTag.NORMAL,
    val metrics: List<MetricView> = emptyList(),
    val transcript: String? = null,
    val familyNote: String? = null,
    val eventTags: List<String> = emptyList(),
)

/** 被记录者 */
data class Subject(
    val id: String,
    val nickname: String,
    val relation: String,
    val age: Int,
)

/**
 * 趋势图上的一个点（展示层投影）。
 * normalized 是本序列内的归一化位置（0–1），不含真实量纲 —— 纵轴不标数值（合规）。
 */
data class TrendPoint(
    val recordedAt: Long,
    val normalized: Float,
)

/** 单个指标的时间趋势（横轴=时间，纵轴=该指标） */
data class MetricTrend(
    val kind: MetricKind,
    val points: List<TrendPoint>,
    val hasEnoughData: Boolean,
    val direction: MetricDirection,
    val statusLabel: String,
)

/** 使用模式 */
enum class UserMode {
    ELDER,
    FAMILY,
}

/** 事件标注预设项 —— 产品的差异化点，也是论文的协变量来源 */
val PRESET_EVENT_TAGS = listOf(
    "感冒", "失眠", "情绪低落", "住院", "旅行", "家庭事件",
)
