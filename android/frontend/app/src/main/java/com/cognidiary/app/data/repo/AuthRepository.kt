package com.cognidiary.app.data.repo

import com.cognidiary.app.data.remote.ApiClient
import com.cognidiary.app.data.remote.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 认证与账号（API-01）。登录、刷新、老人列表、选人会话、同意管理。
 *
 * 网络一律切到 Dispatchers.IO：调用方（Compose 记住的作用域）在主线程，
 * 这里不切就会 NetworkOnMainThreadException 闪退。
 */
class AuthRepository(
    private val api: ApiClient,
    private val tokens: TokenStore,
) {

    data class AccountInfo(
        val accountId: String,
        val username: String,
        val emailMasked: String,
        val relationDisplay: String,
    )

    data class SubjectBrief(
        val subjectId: String,
        val displayName: String,
        val ageBand: String,
        val validRecordCount: Int,
    )

    fun isLoggedIn(): Boolean = tokens.accessTokenFor(api.activeClient) != null

    /**
     * 登录（负责人 2026-09-29 确认）：**一次登录，两端可用**。
     * 服务端权限模型要求老人端 / 家属端是不同类型的会话（老人端无指标权限），
     * 所以登录成功后用同一凭据在另一端也各建一个会话，切换端不再要求重新登录。
     */
    suspend fun login(account: String, password: String, client: String): AccountInfo? =
        withContext(Dispatchers.IO) {
            val info = loginClient(account, password, client) ?: return@withContext null
            runCatching { loginClient(account, password, otherClient(client)) }
            // ★ 恢复到用户实际所在的端：建另一端会话会把 activeClient 带过去，
            //   不恢复的话，随后的老人端专属接口（选人锁定等）会拿家属端令牌 → 404
            api.activeClient = client
            info
        }

    private suspend fun loginClient(account: String, password: String,
                                    client: String): AccountInfo? =
        withContext(Dispatchers.IO) {
            api.activeClient = client
            val body = JSONObject()
                .put("account", account.trim())
                .put("password", password)
                .put("client", client)
                .put("deviceId", api.deviceTag)
            val json = api.postPublic("/v1/auth/login", body) ?: return@withContext null
            tokens.saveTokens(
                client,
                json.optString("accessToken"),
                json.optString("refreshToken"))
            me()
        }

    private fun otherClient(client: String) =
        if (client == "ELDER_APP") "FAMILY_APP" else "ELDER_APP"

    suspend fun register(username: String, email: String, code: String,
                 password: String, gender: String): AccountInfo? =
        withContext(Dispatchers.IO) {
            val client = api.activeClient
            val json = api.postPublic("/v1/auth/register", body(
                username, email, code, password, gender)) ?: return@withContext null
            tokens.saveTokens(
                client,
                json.optString("accessToken"),
                json.optString("refreshToken"))
            // 注册即登录，另一端同样建会话（一次注册，两端可用）
            runCatching { loginClient(username.trim(), password, otherClient(client)) }
            api.activeClient = client
            me()
        }

    private fun body(username: String, email: String, code: String,
                     password: String, gender: String) = JSONObject()
        .put("username", username.trim())
        .put("email", email.trim())
        .put("code", code)
        .put("password", password)
        .put("gender", gender)

    suspend fun sendEmailCode(email: String, scene: String): JSONObject? =
        withContext(Dispatchers.IO) {
            api.postPublic("/v1/auth/email-code", JSONObject()
                .put("email", email.trim()).put("scene", scene))
        }

    /** 忘记密码（API-01 1.4）。204 静默成功（含邮箱不存在，防枚举）。 */
    suspend fun passwordReset(email: String, code: String, newPassword: String) =
        withContext(Dispatchers.IO) {
            api.postPublic("/v1/auth/password-reset", JSONObject()
                .put("email", email.trim())
                .put("code", code)
                .put("newPassword", newPassword))
        }

    /** 退出登录：两端一起退（会话本来就是一次登录两端共用的）。 */
    suspend fun logout() = withContext(Dispatchers.IO) {
        for (client in listOf("ELDER_APP", "FAMILY_APP")) {
            runCatching {
                tokens.refreshTokenFor(client)?.let {
                    api.post("/v1/auth/logout", JSONObject().put("refreshToken", it))
                }
            }
            tokens.clearClient(client)
        }
    }

    suspend fun me(): AccountInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val json = api.get("/v1/accounts/me") ?: return@runCatching null
            AccountInfo(
                accountId = json.optString("accountId"),
                username = json.optString("username"),
                emailMasked = json.optString("emailMasked"),
                relationDisplay = json.optString("relationDisplay"),
            )
        }.getOrNull()
    }

    /** 绑定老人列表（老人端选人 / 家属端列表共用，API-01 1.8）。 */
    suspend fun listSubjects(): List<SubjectBrief> = withContext(Dispatchers.IO) {
        runCatching {
            val json = api.get("/v1/subjects") ?: return@runCatching emptyList()
            val items = json.optJSONArray("items") ?: return@runCatching emptyList()
            (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                SubjectBrief(
                    subjectId = o.optString("subjectId"),
                    displayName = o.optString("displayName"),
                    ageBand = o.optString("ageBand"),
                    validRecordCount = o.optInt("validRecordCount"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 创建或绑定老人（API-01 1.9）。返回 (subjectId, 新建时的绑定码)。 */
    suspend fun createOrBindSubject(elderName: String, ageBand: String,
                                    bindCode: String?): Pair<String, String?> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("elderName", elderName.trim())
                .put("ageBand", ageBand)
            if (!bindCode.isNullOrBlank()) body.put("bindCode", bindCode.trim())
            val json = api.post("/v1/subjects", body)
                ?: throw IllegalStateException("empty response")
            json.optString("subjectId") to
                (if (json.isNull("bindCode")) null else json.optString("bindCode"))
        }

    /** 老人端选人锁定（API-01 1.13）。 */
    suspend fun lockSubject(subjectId: String) = withContext(Dispatchers.IO) {
        api.post("/v1/session/subject", JSONObject().put("subjectId", subjectId))
        tokens.lockedSubjectId = subjectId
    }

    fun lockedSubject(): String? = tokens.lockedSubjectId

    /** 授予同意（API-01 1.15）。仅 SENSITIVE / ELDER_INFORMED 允许 App 授予。 */
    suspend fun grantConsent(type: String, version: String = "1.0") =
        withContext(Dispatchers.IO) {
            api.post("/v1/consents", JSONObject().put("type", type).put("version", version))
        }

    suspend fun revokeConsent(type: String) = withContext(Dispatchers.IO) {
        api.delete("/v1/consents/$type")
    }
}
