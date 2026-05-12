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

    data class ConsoleWebsocket(
        val socket: String,
        val token: String,
    )

    data class StartupVariable(
        val name: String,
        val envVariable: String,
        val value: String,
    )

    data class StartupInfo(
        val startupCommand: String,
        val rawStartupCommand: String,
        val variables: List<StartupVariable>,
    )

    data class LifecycleInfo(
        val disabled: Boolean,
        val status: String,
        val days: Int?,
        val hours: Int?,
        val minutes: Int?,
        val seconds: Int?,
        val secondsRemaining: Long?,
        val canConfirm: Boolean,
        val timeframeDays: Int,
    )

    data class RunnerStart(
        val roundToken: String,
        val timeSessionsRemaining: Int,
        val hourlyUsed: Int,
        val hourlyMax: Int,
        val dailyUsed: Int,
        val dailyMax: Int,
    )

    data class RunnerFinish(
        val tokensWon: Int,
        val scoreTokens: Int,
        val timeReward: Int,
        val score: Int,
        val elapsed: Int,
        val balance: Int,
        val timeSessionsRemaining: Int,
    )

    data class NodeEndpoint(
        val name: String,
        val url: String,
    )

    data class NodeStatus(
        val name: String,
        val url: String,
        val isOnline: Boolean,
    )

    data class Dashboard(
        val account: Account?,
        val servers: List<Server>,
    )

    private val NODE_ENDPOINTS = listOf(
        NodeEndpoint("Great British Node", "https://node.monkey-network.xyz:8080/api/system"),
        NodeEndpoint("EU NODE", "https://eu.monkey-network.xyz:8080/api/system"),
        NodeEndpoint("USA Node", "https://usa.monkey-network.xyz:8080/api/system"),
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

    fun publicStatus(): ApiResult<List<NodeStatus>> {
        val statuses = NODE_ENDPOINTS.map { endpoint ->
            NodeStatus(
                name = endpoint.name,
                url = endpoint.url,
                isOnline = isHttpReachable(endpoint.url),
            )
        }
        return ApiResult(statuses)
    }

    fun panelOnline(): Boolean = isHttpReachable(BASE)

    fun powerAction(ctx: Context, serverId: String, signal: String): ApiResult<Unit> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        return requestUnit(
            "POST",
            "$BASE/api/client/servers/$serverId/power",
            apiToken,
            JSONObject().put("signal", signal),
        )
    }

    fun consoleWebsocket(ctx: Context, serverId: String): ApiResult<ConsoleWebsocket> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        val response = requestJson("GET", "$BASE/api/client/servers/$serverId/websocket", apiToken)
        if (!response.isOk) return ApiResult(error = response.error)
        val attrs = response.value?.optJSONObject("data")
            ?: return ApiResult(error = "Console connection details were not returned.")
        val socket = attrs.optString("socket")
        val token = attrs.optString("token")
        return if (socket.isBlank() || token.isBlank()) {
            ApiResult(error = "Console connection details were incomplete.")
        } else {
            ApiResult(ConsoleWebsocket(socket, token))
        }
    }

    fun startup(ctx: Context, serverId: String): ApiResult<StartupInfo> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        val response = requestJson("GET", "$BASE/api/client/servers/$serverId/startup", apiToken)
        if (!response.isOk) return ApiResult(error = response.error)
        val json = response.value ?: return ApiResult(error = "Startup details were not returned.")
        val meta = json.optJSONObject("meta") ?: JSONObject()
        val data = json.optJSONArray("data") ?: JSONArray()
        val variables = buildList {
            for (i in 0 until data.length()) {
                val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                add(
                    StartupVariable(
                        name = attrs.optString("name"),
                        envVariable = attrs.optString("env_variable"),
                        value = attrs.optString("server_value"),
                    )
                )
            }
        }
        return ApiResult(
            StartupInfo(
                startupCommand = meta.optString("startup_command"),
                rawStartupCommand = meta.optString("raw_startup_command"),
                variables = variables,
            )
        )
    }

    fun lifecycle(ctx: Context, serverId: String): ApiResult<LifecycleInfo> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        val response = requestJson("GET", "$BASE/api/client/servers/$serverId/lifecycle", apiToken)
        if (!response.isOk) return ApiResult(error = response.error)
        val json = response.value ?: return ApiResult(error = "Lifecycle details were not returned.")
        return ApiResult(
            LifecycleInfo(
                disabled = json.optBoolean("disabled"),
                status = json.optString("status", "unknown"),
                days = nullableInt(json, "days"),
                hours = nullableInt(json, "hours"),
                minutes = nullableInt(json, "minutes"),
                seconds = nullableInt(json, "seconds"),
                secondsRemaining = nullableLong(json, "seconds_remaining"),
                canConfirm = json.optBoolean("can_confirm"),
                timeframeDays = json.optInt("timeframe_days", 14),
            )
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

    fun runnerStart(ctx: Context): ApiResult<RunnerStart> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        val response = requestJson("POST", "$BASE/api/client/account/runner/start", apiToken, JSONObject())
        if (!response.isOk) return ApiResult(error = response.error)
        val json = response.value ?: return ApiResult(error = "No response from server.")
        val limits = json.optJSONObject("limits") ?: JSONObject()
        return ApiResult(
            RunnerStart(
                roundToken = json.optString("round_token"),
                timeSessionsRemaining = json.optInt("time_sessions_remaining"),
                hourlyUsed = limits.optInt("hourly_used"),
                hourlyMax = limits.optInt("hourly_max"),
                dailyUsed = limits.optInt("daily_used"),
                dailyMax = limits.optInt("daily_max"),
            )
        )
    }

    fun runnerFinish(ctx: Context, roundToken: String, score: Int, coins: Int): ApiResult<RunnerFinish> {
        val apiToken = AppPrefs.apiToken(ctx) ?: return ApiResult(error = "Sign in first.")
        val body = JSONObject()
            .put("round_token", roundToken)
            .put("score", score)
            .put("coins", coins)
        val response = requestJson("POST", "$BASE/api/client/account/runner/finish", apiToken, body)
        if (!response.isOk) return ApiResult(error = response.error)
        val json = response.value ?: return ApiResult(error = "No response from server.")
        return ApiResult(
            RunnerFinish(
                tokensWon = json.optInt("tokens_won"),
                scoreTokens = json.optInt("score_tokens"),
                timeReward = json.optInt("time_reward"),
                score = json.optInt("score"),
                elapsed = json.optInt("elapsed"),
                balance = json.optInt("balance"),
                timeSessionsRemaining = json.optInt("time_sessions_remaining"),
            )
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

    private fun isHttpReachable(urlStr: String): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            Log.w("PanelApi", "status check failed for $urlStr", e)
            false
        }
    }

    private fun nullableInt(json: JSONObject, key: String): Int? {
        return if (json.isNull(key) || !json.has(key)) null else json.optInt(key)
    }

    private fun nullableLong(json: JSONObject, key: String): Long? {
        return if (json.isNull(key) || !json.has(key)) null else json.optLong(key)
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
