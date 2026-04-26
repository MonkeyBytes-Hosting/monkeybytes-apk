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
        val event = message.data["event"].orEmpty()
        val serverUuid = message.data["server_uuid"]
        showNotification(title, body, event, serverUuid)
    }

    private fun showNotification(title: String, body: String, event: String, serverUuid: String?) {
        val (channelId, channelName, accent) = channelFor(event)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            ch.lightColor = accent
            ch.enableLights(true)
            nm.createNotificationChannel(ch)
        }

        val targetUrl = if (!serverUuid.isNullOrBlank())
            "https://dash.monkey-network.xyz/server/$serverUuid"
        else
            "https://dash.monkey-network.xyz/"

        val openIntent = Intent(this, WebViewActivity::class.java).apply {
            putExtra("url", targetUrl)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            this,
            (event + serverUuid).hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setColor(accent)
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), n)
    }

    private fun channelFor(event: String): Triple<String, String, Int> {
        return when {
            event.startsWith("power.start") -> Triple("mb_power", "Power actions", Color.parseColor("#34A853"))
            event.startsWith("power.stop") -> Triple("mb_power", "Power actions", Color.parseColor("#F4B400"))
            event.startsWith("power.restart") -> Triple("mb_power", "Power actions", Color.parseColor("#1A73E8"))
            event.startsWith("power.kill") -> Triple("mb_power", "Power actions", Color.parseColor("#EA4335"))
            event == "installed" -> Triple("mb_lifecycle", "Server lifecycle", Color.parseColor("#34A853"))
            event == "created" -> Triple("mb_lifecycle", "Server lifecycle", Color.parseColor("#1A73E8"))
            event == "deleted" -> Triple("mb_lifecycle", "Server lifecycle", Color.parseColor("#EA4335"))
            event == "reinstall" -> Triple("mb_lifecycle", "Server lifecycle", Color.parseColor("#F4B400"))
            else -> Triple("mb_general", "General", Color.parseColor("#1A73E8"))
        }
    }
}
