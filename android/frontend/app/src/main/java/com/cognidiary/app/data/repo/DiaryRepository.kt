package com.cognidiary.app.data.repo

import com.cognidiary.app.data.model.DiaryRecord
import com.cognidiary.app.data.model.MetricView
import com.cognidiary.app.data.model.StatusView
import com.cognidiary.app.data.model.Subject

/**
 * 数据仓储接口。
 *
 * 方法签名对齐 docs/api/ 的契约。一期由 [MockDiaryRepository] 实现，
 * 后端就绪后换实现即可，UI 层不用改。
 *
 * ★ 注意：本接口【没有】任何「计算状态」的方法。
 *   状态只在服务端计算，客户端只渲染 —— 见 docs/frontend/modules/状态与提醒.md 3.3。
 *   同样也没有「计算指标」的方法：指标由服务端返回，客户端不做端侧 DSP。
 */
interface DiaryRepository {

    suspend fun subject(): Subject

    /** 状态机 S0–S6 的当前值，以及配套文案 key */
    suspend fun status(subjectId: String): StatusView

    suspend fun records(subjectId: String, limit: Int = 60): List<DiaryRecord>

    /** 家属端 4 个指标卡的数据 */
    suspend fun metrics(subjectId: String): List<MetricView>

    /** 连续未录天数，用于降频 */
    suspend fun consecutiveMissedDays(subjectId: String): Int

    suspend fun saveRecord(record: DiaryRecord)

    suspend fun deleteRecord(recordId: String)

    suspend fun setFamilyNote(recordId: String, note: String)

    suspend fun addEventTag(recordId: String, tag: String)

    suspend fun removeEventTag(recordId: String, tag: String)
}
