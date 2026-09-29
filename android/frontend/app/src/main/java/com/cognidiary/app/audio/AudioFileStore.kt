package com.cognidiary.app.audio

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 录音文件的落盘位置管理。
 *
 * 位置：app 私有目录 `files/recordings/`
 * 不放外部存储 → 不需要任何存储权限，其他 App 也读不到。
 *
 * ★ 一期【未加密】。
 *   docs/PROJECT_REQUIREMENTS.md 的 FR-6.2 要求本地缓存加密，
 *   但 SQLCipher / Jetpack Security 不在本机 Gradle 缓存里（离线约束）。
 *   这是**已知缺口**，不是遗漏；网络恢复后补齐。
 *   见 docs/frontend/Android工程实现方案.md 2.2 节 C3。
 */
object AudioFileStore {

    private const val DIR_NAME = "recordings"

    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun newFile(context: Context): File {
        val stamp = System.currentTimeMillis()
        val rand = UUID.randomUUID().toString().take(8)
        return File(dir(context), "rec_${stamp}_$rand.m4a")
    }

    fun delete(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
