package com.monkeybytes.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.concurrent.thread

class PushService : FirebaseMessagingService() {

    private data class AlertChannel(
        val id: String,
        val name: String,
        val accent: Int,
        val importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
        val priority: Int = NotificationCompat.PRIORITY_DEFAULT,
    )

    override fun onNewToken(token: String) {
        thread {
            if (PanelApi.registerPushToken(applicationContext, token)) {
                AppPrefs.setLastRegisteredFcmToken(applicationContext, token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "MonkeyBytes"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val event = message.data["event"] ?: message.data["type"].orEmpty()
        val serverUuid = message.data["server_uuid"]
        showNotification(title, body, event, serverUuid)
    }

    private fun showNotification(title: String, body: String, event: String, serverUuid: String?) {
        val channel = channelFor(event)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channel.id, channel.name, channel.importance)
            ch.lightColor = channel.accent
            ch.enableLights(true)
            nm.createNotificationChannel(ch)
        }

        val openIntent = Intent(this, DashboardActivity::class.java).apply {
            if (!serverUuid.isNullOrBlank()) putExtra("server_uuid", serverUuid)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            this,
            (event + serverUuid).hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(this, channel.id)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setColor(channel.accent)
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(channel.priority)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), n)
    }

    private fun channelFor(event: String): AlertChannel {
        val normalized = event.lowercase()
        return when {
            normalized.startsWith("power.start") -> AlertChannel("mb_power", "Power actions", Color.parseColor("#34A853"))
            normalized.startsWith("power.stop") -> AlertChannel("mb_power", "Power actions", Color.parseColor("#F4B400"))
            normalized.startsWith("power.restart") -> AlertChannel("mb_power", "Power actions", Color.parseColor("#1A73E8"))
            normalized.startsWith("power.kill") -> AlertChannel(
                "mb_power",
                "Power actions",
                Color.parseColor("#EA4335"),
                NotificationManager.IMPORTANCE_HIGH,
                NotificationCompat.PRIORITY_HIGH,
            )
            normalized == "installed" -> AlertChannel("mb_lifecycle", "Server lifecycle", Color.parseColor("#34A853"))
            normalized == "created" -> AlertChannel("mb_lifecycle", "Server lifecycle", Color.parseColor("#1A73E8"))
            normalized == "deleted" -> AlertChannel("mb_lifecycle", "Server lifecycle", Color.parseColor("#EA4335"))
            normalized == "reinstall" -> AlertChannel("mb_lifecycle", "Server lifecycle", Color.parseColor("#F4B400"))
            normalized in setOf("suspended", "unsuspended", "restored", "transferred") ->
                AlertChannel("mb_lifecycle", "Server lifecycle", Color.parseColor("#7F88FF"))
            normalized.startsWith("health.") || normalized in setOf("node_offline", "node_online", "node_degraded") ->
                AlertChannel(
                    "mb_health",
                    "Health alerts",
                    Color.parseColor("#EA4335"),
                    NotificationManager.IMPORTANCE_HIGH,
                    NotificationCompat.PRIORITY_HIGH,
                )
            normalized.startsWith("resource.") || normalized in setOf("cpu_high", "memory_high", "disk_high", "network_high") ->
                AlertChannel(
                    "mb_resources",
                    "Resource alerts",
                    Color.parseColor("#F97316"),
                    NotificationManager.IMPORTANCE_HIGH,
                    NotificationCompat.PRIORITY_HIGH,
                )
            normalized.startsWith("backup.") || normalized in setOf("backup_completed", "backup_failed") ->
                AlertChannel("mb_backups", "Backups", Color.parseColor("#34A853"))
            normalized.startsWith("schedule.") || normalized in setOf("schedule_started", "schedule_completed", "schedule_failed") ->
                AlertChannel("mb_schedules", "Schedules", Color.parseColor("#1A73E8"))
            normalized.startsWith("database.") || normalized in setOf("database_created", "database_deleted", "database_error") ->
                AlertChannel("mb_databases", "Databases", Color.parseColor("#7F88FF"))
            normalized.startsWith("user.") || normalized in setOf("user_joined", "user_left", "permission_changed") ->
                AlertChannel("mb_users", "Users and permissions", Color.parseColor("#14B8A6"))
            normalized.startsWith("security.") || normalized in setOf("login_failed", "password_changed", "token_revoked") ->
                AlertChannel(
                    "mb_security",
                    "Security alerts",
                    Color.parseColor("#EA4335"),
                    NotificationManager.IMPORTANCE_HIGH,
                    NotificationCompat.PRIORITY_HIGH,
                )
            normalized.startsWith("billing.") || normalized in setOf("invoice_due", "payment_failed", "credit_low") ->
                AlertChannel("mb_billing", "Billing alerts", Color.parseColor("#F4B400"))
            else -> AlertChannel("mb_general", "General", Color.parseColor("#1A73E8"))
        }
    }
}
