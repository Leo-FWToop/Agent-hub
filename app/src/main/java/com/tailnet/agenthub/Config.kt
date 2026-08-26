package com.tailnet.agenthub

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import org.json.JSONObject

/** 连接配置 + 应用偏好 */
data class AppConfig(
    val openclawBaseUrl: String = "",
    val openclawToken: String = "",
    /** 0=跟随系统, 1=浅色, 2=深色 */
    val themeMode: Int = 0,
)

/**
 * 配置持久化：双层存储，卸载重装后仍能恢复连接配置。
 *
 * 1. SharedPreferences：应用内私有，作为本地缓存（卸载丢失）
 * 2. 外部存储 /sdcard/AgentHub/config.json：系统公共目录，卸载后保留
 *
 * 加载时优先外部文件（适用于卸载重装场景），保存时同时写入两处。
 * 外部存储需要 MANAGE_EXTERNAL_STORAGE（Android 11+）或 WRITE_EXTERNAL_STORAGE
 * （Android 10 及以下）权限，未授权时仅用 SharedPreferences，不报错。
 */
class ConfigStore(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("agenthub_config_v2", Context.MODE_PRIVATE)

    /** 外部存储配置文件：/sdcard/AgentHub/config.json */
    val externalDir: File = File(Environment.getExternalStorageDirectory(), "AgentHub")
    val externalFile: File = File(externalDir, "config.json")

    /** 是否有权限读写 /sdcard/AgentHub/ */
    fun hasExternalAccess(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 加载配置：
     * 1. 优先从外部存储读取（卸载重装后恢复）
     * 2. 外部不可用则用 SharedPreferences
     */
    fun load(): AppConfig {
        val external = loadFromExternal()
        if (external != null && external.openclawBaseUrl.isNotBlank()) {
            // 同步到 SharedPreferences 作为本地缓存
            saveToPrefs(external)
            return external
        }
        return AppConfig(
            openclawBaseUrl = prefs.getString(KEY_OC_URL, "").orEmpty(),
            openclawToken = prefs.getString(KEY_OC_TOKEN, "").orEmpty(),
            themeMode = prefs.getInt(KEY_THEME, 0).coerceIn(0, 2),
        )
    }

    private fun loadFromExternal(): AppConfig? {
        if (!hasExternalAccess()) return null
        return try {
            if (!externalFile.exists()) return null
            val obj = JSONObject(externalFile.readText())
            AppConfig(
                openclawBaseUrl = obj.optString(KEY_OC_URL, ""),
                openclawToken = obj.optString(KEY_OC_TOKEN, ""),
                themeMode = obj.optInt(KEY_THEME, 0).coerceIn(0, 2),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 保存配置：同时写入 SharedPreferences 和外部存储（如已授权）。
     * 返回外部存储是否写入成功。
     */
    fun save(config: AppConfig): Boolean {
        saveToPrefs(config)
        return saveToExternal(config)
    }

    private fun saveToPrefs(config: AppConfig) {
        prefs.edit()
            .putString(KEY_OC_URL, config.openclawBaseUrl.trim())
            .putString(KEY_OC_TOKEN, config.openclawToken.trim())
            .putInt(KEY_THEME, config.themeMode)
            .remove(KEY_DS_URL)
            .apply()
    }

    /** 写入外部存储；返回是否成功（用于 UI 提示） */
    fun saveToExternal(config: AppConfig): Boolean {
        if (!hasExternalAccess()) return false
        return try {
            externalDir.mkdirs()
            val obj = JSONObject().apply {
                put(KEY_OC_URL, config.openclawBaseUrl.trim())
                put(KEY_OC_TOKEN, config.openclawToken.trim())
                put(KEY_THEME, config.themeMode)
                put("savedAt", System.currentTimeMillis())
                put("appVersion", "1.14")
            }
            externalFile.writeText(obj.toString(2))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 从外部存储导入配置 */
    fun importFromExternal(): AppConfig? {
        if (!hasExternalAccess()) return null
        return try {
            if (!externalFile.exists()) return null
            val obj = JSONObject(externalFile.readText())
            AppConfig(
                openclawBaseUrl = obj.optString(KEY_OC_URL, ""),
                openclawToken = obj.optString(KEY_OC_TOKEN, ""),
                themeMode = obj.optInt(KEY_THEME, 0).coerceIn(0, 2),
            )
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val KEY_OC_URL = "openclaw_base_url"
        private const val KEY_OC_TOKEN = "openclaw_token"
        private const val KEY_DS_URL = "deepseek_base_url"
        private const val KEY_THEME = "theme_mode"
    }
}
