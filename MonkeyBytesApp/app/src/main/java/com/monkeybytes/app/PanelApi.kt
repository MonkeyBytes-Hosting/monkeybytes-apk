package com.monkeybytes.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object PanelApi {
    const val BASE = "https://dash.monkey-network.xyz"

    data class ApiResult<T>(val value: T? = null, val error: String? = null) {
        val isOk: Boolean get() = error == null
    }

    data class Account(
        val username: String,
        val email: String,
        val name: String,
        val isAdmin: Boolean,
    )

    data class Server(
        val identifier: String,
        val uuid: String,
        val name: String,
        val node: String,
        val description: String,
        val isSuspended: Boolean,
        val isInstalling: Boolean,
        val memoryMb: Int,
        val diskMb: Int,
        val cpuLimit: Int,
    )

    data class ServerResources(
        val state: String,
        val memoryBytes: Long,
        val diskBytes: Long,
        val cpuAbsolute: Double,
        val networkRxBytes: Long,
        val networkTxBytes: Long,
        val uptimeMillis: Long,
    )

    data class Dashboard(
        val account: Account?,
        val servers: List<Server>,
    )

    fun login(user: String, password: String): ApiResult<String> {
        val response = requestJson(
            "POST",
            "$BASE/api/mobile/login",
            null,
            JSONObject()
                .put("user", user)
                .put("password", password),
        )
        if (!response.isOk) return ApiResult(error = response.error)
        val token = response.value?.optString("token").orEmpty()
        return if (token.isBlank()) ApiResult(error = "Login did not return an API token.")
        else ApiResult(token)
    }

    fun dashboard(ctx: Context): ApiResult<Dashboard> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        val account = getAccount(apiToken).value
        val servers = getServers(apiToken)
        if (!servers.isOk) return ApiResult(error = servers.error)
        return ApiResult(Dashboard(account, servers.value.orEmpty()))
    }

    fun serverResources(ctx: Context, serverId: String): ApiResult<ServerResources> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        val response = requestJson("GET", "$BASE/api/client/servers/$serverId/resources", apiToken)
        if (!response.isOk) return ApiResult(error = response.error)
        val attrs = response.value?.optJSONObject("attributes")
            ?: return ApiResult(error = "Server resources were not returned.")
        val resources = attrs.optJSONObject("resources") ?: JSONObject()
        return ApiResult(
            ServerResources(
                state = attrs.optString("current_state", "unknown"),
                memoryBytes = resources.optLong("memory_bytes"),
                diskBytes = resources.optLong("disk_bytes"),
                cpuAbsolute = resources.optDouble("cpu_absolute"),
                networkRxBytes = resources.optLong("network_rx_bytes"),
                networkTxBytes = resources.optLong("network_tx_bytes"),
                uptimeMillis = resources.optLong("uptime"),
            )
        )
    }

    fun powerAction(ctx: Context, serverId: String, signal: String): ApiResult<Unit> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        return requestUnit(
            "POST",
            "$BASE/api/client/servers/$serverId/power",
            apiToken,
            JSONObject().put("signal", signal),
        )
    }

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

    private fun getAccount(apiToken: String): ApiResult<Account> {
        val response = requestJson("GET", "$BASE/api/client/account", apiToken)
        if (!response.isOk) return ApiResult(error = response.error)
        val attrs = response.value?.optJSONObject("attributes")
            ?: return ApiResult(error = "Account details were not returned.")
        return ApiResult(
            Account(
                username = attrs.optString("username"),
                email = attrs.optString("email"),
                name = listOf(attrs.optString("first_name"), attrs.optString("last_name"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { attrs.optString("username", "Account") },
                isAdmin = attrs.optBoolean("admin"),
            )
        )
    }

    private fun getServers(apiToken: String): ApiResult<List<Server>> {
        val response = requestJson("GET", "$BASE/api/client", apiToken)
        if (!response.isOk) return ApiResult(error = response.error)
        val data = response.value?.optJSONArray("data") ?: JSONArray()
        val servers = buildList {
            for (i in 0 until data.length()) {
                val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val limits = attrs.optJSONObject("limits") ?: JSONObject()
                add(
                    Server(
                        identifier = attrs.optString("identifier"),
                        uuid = attrs.optString("uuid"),
                        name = attrs.optString("name", "Unnamed server"),
                        node = attrs.optString("node"),
                        description = attrs.optString("description"),
                        isSuspended = attrs.optBoolean("is_suspended"),
                        isInstalling = attrs.optBoolean("is_installing"),
                        memoryMb = limits.optInt("memory"),
                        diskMb = limits.optInt("disk"),
                        cpuLimit = limits.optInt("cpu"),
                    )
                )
            }
        }
        return ApiResult(servers)
    }

    private fun requestJson(method: String, urlStr: String, bearer: String): ApiResult<JSONObject> {
        return requestJson(method, urlStr, bearer, null)
    }

    private fun requestUnit(method: String, urlStr: String, bearer: String, body: JSONObject): ApiResult<Unit> {
        val result = requestJson(method, urlStr, bearer, body, allowEmpty = true)
        return if (result.isOk) ApiResult(Unit) else ApiResult(error = result.error)
    }

    private fun requestJson(
        method: String,
        urlStr: String,
        bearer: String?,
        body: JSONObject?,
        allowEmpty: Boolean = false,
    ): ApiResult<JSONObject> {
        return try {
            val conn = openConnection(method, urlStr, bearer, body != null)
            if (body != null) {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            }
            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (responseCode !in 200..299) {
                ApiResult(error = apiError(text).ifBlank { "Request failed with HTTP $responseCode." })
            } else if (text.isBlank() && allowEmpty) {
                ApiResult(JSONObject())
            } else {
                ApiResult(JSONObject(text))
            }
        } catch (e: Exception) {
            Log.e("PanelApi", "$method $urlStr failed", e)
            ApiResult(error = e.message ?: "Network request failed.")
        }
    }

    private fun openConnection(
        method: String,
        urlStr: String,
        bearer: String?,
        hasBody: Boolean,
    ): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = hasBody
            connectTimeout = 10_000
            readTimeout = 10_000
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/vnd.pterodactyl.v1+json")
            setRequestProperty("Content-Type", "application/json")
        }
    }

    private fun apiError(text: String): String {
        if (text.isBlank()) return ""
        return runCatching {
            val json = JSONObject(text)
            json.optString("error").ifBlank {
                json.optJSONArray("errors")?.optJSONObject(0)?.optString("detail").orEmpty()
            }
        }.getOrNull().orEmpty()
    }
}
