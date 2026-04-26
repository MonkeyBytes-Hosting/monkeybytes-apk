package com.monkeybytes.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object PanelApi {
    const val BASE = "https://dash.monkey-network.xyz"
    const val DISCORD_LOGIN_URL = "$BASE/auth/discord?mode=login&mobile=1"
    const val DISCORD_REGISTER_URL = "$BASE/auth/discord?mode=register&mobile=1"

    fun registerPushToken(ctx: Context, fcmToken: String): Boolean {
        val apiToken = AppPrefs.apiToken(ctx) ?: return false
        return request(
            "POST",
            "$BASE/api/client/account/push-tokens",
            apiToken,
            JSONObject().put("token", fcmToken).put("platform", "android"),
        )
    }

    fun unregisterPushToken(ctx: Context, fcmToken: String): Boolean {
        val apiToken = AppPrefs.apiToken(ctx) ?: return false
        return request(
            "DELETE",
            "$BASE/api/client/account/push-tokens",
            apiToken,
            JSONObject().put("token", fcmToken),
        )
    }

    private fun request(method: String, urlStr: String, bearer: String, body: JSONObject): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $bearer")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val ok = conn.responseCode in 200..299
            if (!ok) Log.w("PanelApi", "$method $urlStr -> ${conn.responseCode}")
            conn.disconnect()
            ok
        } catch (e: Exception) {
            Log.e("PanelApi", "request failed", e)
            false
        }
    }
}
