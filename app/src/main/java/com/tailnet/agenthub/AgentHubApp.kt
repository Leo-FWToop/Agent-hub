package com.tailnet.agenthub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Application 入口：
 * - 初始化前台/后台状态跟踪（ProcessLifecycleOwner）
 * - 创建通知渠道
 * - 提供后台回复通知发送
 */
class AgentHubApp : Application(), DefaultLifecycleObserver {

    companion object {
        const val NOTIF_CHANNEL_REPLY = "agent_reply"
        const val NOTIF_ID_REPLY = 1001
        private const val NOTIF_GROUP_KEY = "com.tailnet.agenthub.REPLIES"

        /** 当前是否在前台（由 ProcessLifecycleOwner 维护） */
        @Volatile
        var isAppInForeground: Boolean = false
            private set

        /** 全局访问点（在 onCreate 中设置） */
        lateinit var instance: AgentHubApp
            private set
    }

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        createNotificationChannels()
        // 订阅应用前后台生命周期
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        isAppInForeground = true
        // 应用回到前台，清除所有回复通知
        try {
            NotificationManagerCompat.from(this).cancel(NOTIF_ID_REPLY)
        } catch (_: Throwable) {}
    }

    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        isAppInForeground = false
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val replyChannel = NotificationChannel(
                NOTIF_CHANNEL_REPLY,
                "Agent 回复",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "当应用在后台时，Agent 回复完成后弹出此通知"
                enableLights(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(replyChannel)
        }
    }

    /**
     * 发送一条「Agent 回复完成」通知（仅在后台且已授权通知时发送）。
     * @param title    通知标题，如对话标题
     * @param content  通知正文（回复预览，自动截断）
     * @param convId   对话 ID，通知点击时跳转回对应对话（保留作未来扩展）
     */
    fun notifyAgentReply(title: String, content: String, convId: String? = null) {
        if (isAppInForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val preview = content.trim()
            .replace(Regex("[\\s\\n\\r]+"), " ")
            .take(200)
            .let { if (it.length < content.trim().length) "$it…" else it }

        val notifyTitle = title.ifBlank { "Agent 新回复" }

        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            convId?.let { putExtra("conv_id", it) }
        }
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(
                this, 0, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                this, 0, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_REPLY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notifyTitle)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup(NOTIF_GROUP_KEY)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID_REPLY, notification)
        } catch (_: Throwable) {}
    }
}
