package com.cognidiary.app.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 令牌存储：Android Keystore AES-GCM 加密后落 SharedPreferences。
 *
 * 合规（00-接口通则 3.1）：令牌禁止明文存储。
 * 不引入 security-crypto 依赖（离线构建约束），直接用框架 Keystore API。
 *
 * ★ 老人端 / 家属端是两个独立的会话（API-01：client = ELDER_APP / FAMILY_APP），
 *   令牌分开存。共用一份会导致「老人端登录后，家属端拿老人令牌调接口 → 越权 404」。
 */
class TokenStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("cogni_token_store", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = runCatching {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()

    private fun ns(client: String) = if (client == "ELDER_APP") "elder" else "family"

    fun accessTokenFor(client: String): String? =
        prefs.getString("${ns(client)}_access", null)?.let { decrypt(it) }

    fun refreshTokenFor(client: String): String? =
        prefs.getString("${ns(client)}_refresh", null)?.let { decrypt(it) }

    fun saveTokens(client: String, access: String, refresh: String) {
        prefs.edit()
            .putString("${ns(client)}_access", encrypt(access))
            .putString("${ns(client)}_refresh", encrypt(refresh))
            .apply()
    }

    fun clearClient(client: String) {
        prefs.edit()
            .remove("${ns(client)}_access")
            .remove("${ns(client)}_refresh")
            .apply()
    }

    /** 老人端选人锁定（仅 ELDER_APP 会话有这个状态） */
    var lockedSubjectId: String?
        get() = prefs.getString(KEY_LOCKED_SUBJECT, null)
        set(value) = prefs.edit().putString(KEY_LOCKED_SUBJECT, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ALIAS = "cogni_token_key"
        const val KEY_LOCKED_SUBJECT = "locked_subject_id"
    }
}
