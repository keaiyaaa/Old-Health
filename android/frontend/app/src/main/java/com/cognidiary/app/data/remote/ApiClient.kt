package com.cognidiary.app.data.remote

import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 接口错误。前端必须按 code 分支，不得匹配 message 文本（00-接口通则 6.2）。 */
class ApiException(
    val code: String,
    val httpStatus: Int,
    message: String,
    val requestId: String? = null,
) : Exception(message)

/**
 * 极简 API 客户端：HttpURLConnection + org.json，零第三方依赖（离线构建约束）。
 *
 * - Bearer 认证；401 + TOKEN_EXPIRED 时单飞刷新后重放（通则 3.2：并发只发一次刷新）
 * - 写操作自动带 Idempotency-Key（调用方可用 requestKey 复用）
 * - 未知错误码兜底：把 message 显示出来，不崩溃
 */
class ApiClient(
    private val baseUrl: String,
    private val tokens: TokenStore,
) {

    @Volatile
    private var refreshing: Thread? = null

    /** 当前生效的端（ELDER_APP / FAMILY_APP），决定用哪份令牌 */
    @Volatile
    var activeClient: String = "FAMILY_APP"

    var deviceTag: String = "andr-" + Build.MODEL.hashCode().toString(16)

    // ---------------- 公开方法 ----------------

    fun get(path: String, query: Map<String, String?> = emptyMap()): JSONObject? =
        request("GET", path, body = null, query = query, idempotencyKey = null)

    fun post(path: String, body: JSONObject? = null, requestKey: String? = null): JSONObject? =
        request("POST", path, body, emptyMap(), requestKey ?: newIdempotencyKey())

    fun patch(path: String, body: JSONObject, requestKey: String? = null): JSONObject? =
        request("PATCH", path, body, emptyMap(), requestKey ?: newIdempotencyKey())

    fun put(path: String, body: JSONObject): JSONObject? =
        request("PUT", path, body, emptyMap(), null)

    fun delete(path: String): JSONObject? =
        request("DELETE", path, null, emptyMap(), null)

    /** 未认证请求（登录 / 注册 / 刷新）。 */
    fun postPublic(path: String, body: JSONObject): JSONObject? =
        request("POST", path, body, emptyMap(), idempotencyKey = null, authorized = false)

    // ---------------- 实现 ----------------

    private fun newIdempotencyKey(): String = UUID.randomUUID().toString()

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        query: Map<String, String?>,
        idempotencyKey: String?,
        authorized: Boolean = true,
        retryOnAuthFailure: Boolean = true,
    ): JSONObject? {
        val url = buildUrl(path, query)
        val (code, text) = execute(url, method, body, idempotencyKey, authorized)
        if (code == 401 && retryOnAuthFailure && authorized) {
            val err = parseCode(text)
            if (err == "TOKEN_EXPIRED" && refreshTokens()) {
                return request(method, path, body, query, idempotencyKey,
                    authorized, retryOnAuthFailure = false)
            }
            if (err == "TOKEN_INVALID") tokens.clearClient(activeClient)
        }
        if (code in 200..299) {
            return if (text.isBlank() || code == 204) null else JSONObject(text)
        }
        throw toApiException(code, text)
    }

    private fun buildUrl(path: String, query: Map<String, String?>): URL {
        val sb = StringBuilder(baseUrl.trimEnd('/')).append(path)
        val params = query.filterValues { it != null }
        if (params.isNotEmpty()) {
            sb.append('?')
            sb.append(params.entries.joinToString("&") { (k, v) ->
                "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
            })
        }
        return URL(sb.toString())
    }

    private fun execute(
        url: URL,
        method: String,
        body: JSONObject?,
        idempotencyKey: String?,
        authorized: Boolean,
    ): Pair<Int, String> {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Client-Version", "1.0")
            setRequestProperty("X-Device-Id", deviceTag)
            if (authorized) tokens.accessTokenFor(activeClient)?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
            if (idempotencyKey != null) {
                setRequestProperty("Idempotency-Key", idempotencyKey)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    /** 刷新令牌。并发 401 时只发一次刷新（通则 3.2）。按当前端各自的命名空间刷新。 */
    private fun refreshTokens(): Boolean {
        val current = refreshing
        if (current != null && current !== Thread.currentThread()) {
            current.join(5_000)
            return tokens.accessTokenFor(activeClient) != null
        }
        synchronized(this) {
            refreshing = Thread.currentThread()
            try {
                val refresh = tokens.refreshTokenFor(activeClient) ?: return false
                val (code, text) = execute(
                    buildUrl("/v1/auth/refresh", emptyMap()), "POST",
                    JSONObject().put("refreshToken", refresh), null, authorized = false)
                if (code == 200) {
                    val json = JSONObject(text)
                    tokens.saveTokens(
                        activeClient,
                        json.optString("accessToken", ""),
                        json.optString("refreshToken", ""))
                    return true
                }
                tokens.clearClient(activeClient)
                return false
            } catch (e: IOException) {
                Log.w("ApiClient", "refresh failed", e)
                return false
            } finally {
                refreshing = null
            }
        }
    }

    private fun parseCode(text: String): String = runCatching {
        JSONObject(text).optString("code", "")
    }.getOrDefault("")

    private fun toApiException(code: Int, text: String): ApiException = runCatching {
        val json = JSONObject(text)
        ApiException(
            code = json.optString("code", "INTERNAL_ERROR"),
            httpStatus = code,
            message = json.optString("message", "请求未能完成"),
            requestId = json.optString("requestId", null),
        )
    }.getOrDefault(ApiException("NETWORK_ERROR", code, "网络不给力，请稍后再试"))
}
