package com.cognidiary.app.ui.theme

import androidx.compose.ui.unit.dp

/** 间距序列 —— 原型设计说明书 4.3：4 / 8 / 12 / 16 / 24 / 32 */
object Spacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp
}

/** 圆角 —— 卡片 12、按钮 12、事件 Chip 8、话题卡 10 */
object Radius {
    val card = 12.dp
    val button = 12.dp
    val chip = 8.dp
    val topicCard = 10.dp
}

/** 页面左右安全边距：老人端 20，家属端 16 */
object ScreenPadding {
    val elder = 20.dp
    val family = 16.dp
}

/** 关键控件尺寸 —— 适老化硬指标见原型设计说明书 4.5 */
object Sizes {
    /** 大录音按钮直径，老人端 140dp */
    val bigRecordButton = 140.dp

    /** 主按钮高度 */
    val primaryButton = 60.dp

    /** 最小点击区 */
    val minTouchTarget = 48.dp

    /** 状态横幅左侧竖条宽度 */
    val statusBar = 4.dp

    /** 指标卡迷你折线 */
    val miniChartWidth = 60.dp
    val miniChartHeight = 18.dp

    /** 状态横幅文案换行最大行数（保证非疾病解释不被截断） */
    val statusExplanationMaxLines = 4
}
