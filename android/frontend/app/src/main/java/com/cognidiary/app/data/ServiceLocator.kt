package com.cognidiary.app.data

import android.content.Context
import com.cognidiary.app.audio.AudioRecorder
import com.cognidiary.app.data.remote.ApiClient
import com.cognidiary.app.data.remote.TokenStore
import com.cognidiary.app.data.repo.AuthRepository
import com.cognidiary.app.data.repo.DiaryRepository
import com.cognidiary.app.data.repo.LocalPrefs
import com.cognidiary.app.data.repo.MockDiaryRepository
import com.cognidiary.app.data.repo.RemoteDiaryRepository

/**
 * 极简依赖装配。
 *
 * 为什么不用 Hilt / Koin：
 *   一期依赖只有三个（偏好、仓储、录音器），引入 DI 框架的收益小于它带来的
 *   构建风险（Hilt 需要 KSP/kapt，本机未缓存 —— 见 Android工程实现方案 2.2 C2）。
 *
 * ★ 后端接入点（C2 已就绪）：
 *   `useRemoteBackend = true` 时装配 [RemoteDiaryRepository]（走 docs/api/ 契约），
 *   关闭则回退本地演示数据。所有界面只依赖 [DiaryRepository] 接口，不受影响。
 */
object ServiceLocator {

    lateinit var appContext: Context
        private set

    lateinit var prefs: LocalPrefs
        private set

    lateinit var tokens: TokenStore
        private set

    lateinit var repository: DiaryRepository
        private set

    lateinit var auth: AuthRepository
        private set

    private lateinit var api: ApiClient

    /**
     * 切换当前生效的端（ELDER_APP / FAMILY_APP）。
     * 两端是两份独立会话；进哪棵导航树就切到哪份令牌，
     * 否则会出现「老人端登录后家属端拿老人令牌调接口 → 越权 404」。
     */
    fun setApiMode(client: String) {
        if (::api.isInitialized) api.activeClient = client
    }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = LocalPrefs(appContext)
        tokens = TokenStore(appContext)
        api = ApiClient(prefs.backendBaseUrl, tokens)
        auth = AuthRepository(api, tokens)
        repository = if (prefs.useRemoteBackend) {
            RemoteDiaryRepository(api, tokens)
        } else {
            MockDiaryRepository()
        }
    }

    /** 每次进录音页拿一个新的录音器；旧的由 ViewModel 在 onCleared 里释放 */
    fun newRecorder(): AudioRecorder = AudioRecorder(appContext)
}
