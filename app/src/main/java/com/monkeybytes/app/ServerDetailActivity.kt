package com.monkeybytes.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ServerDetailActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var resourceText: TextView
    private lateinit var networkText: TextView
    private lateinit var startupCommandText: TextView
    private lateinit var startupVariablesText: TextView
    private lateinit var lifecycleText: TextView
    private lateinit var consoleStatusText: TextView
    private lateinit var consoleText: TextView
    private lateinit var serverId: String
    private val consoleLines = ArrayDeque<String>()
    private val lifecycleHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private var consoleSocket: WebSocket? = null
    private var lifecycleRemainingSeconds: Long? = null
    private var lifecycleStatus = "unknown"
    private var lifecycleDisabled = false
    private var lifecycleCanConfirm = false
    private var lifecycleTimeframeDays = 14
    private val lifecycleTick = object : Runnable {
        override fun run() {
            val remaining = lifecycleRemainingSeconds ?: return
            lifecycleRemainingSeconds = (remaining - 1).coerceAtLeast(0)
            showLifecycleCountdown()
            if ((lifecycleRemainingSeconds ?: 0) > 0) {
                lifecycleHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = intent.getStringExtra("identifier").orEmpty()
        buildLayout()
        refreshResources()
        refreshServerDetails()
        connectConsole()
    }

    override fun onDestroy() {
        lifecycleHandler.removeCallbacks(lifecycleTick)
        consoleSocket?.close(1000, "Activity closed")
        consoleSocket = null
        httpClient.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private fun buildLayout() {
        val scroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.bg_main_gradient)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        val name = intent.getStringExtra("name") ?: "Server"
        val node = intent.getStringExtra("node").orEmpty()
        val description = intent.getStringExtra("description").orEmpty()
        val memory = intent.getIntExtra("memory_mb", 0)
        val disk = intent.getIntExtra("disk_mb", 0)
        val cpu = intent.getIntExtra("cpu_limit", 0)

        root.addView(header(name, node.ifBlank { "Pterodactyl server" }))
        if (description.isNotBlank()) root.addView(infoCard("Description", description))
        root.addView(infoCard("Limits", "Memory $memory MB  Disk $disk MB  CPU $cpu%"))

        root.addView(sectionTitle("Startup"))
        val startupCard = MaterialCardView(this).apply {
            setCardBackgroundColor(COLOR_CARD)
            strokeColor = COLOR_STROKE
            strokeWidth = dp(1)
            radius = dp(22).toFloat()
            cardElevation = 0f
        }
        val startupBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        startupCommandText = text("Loading startup command...", 12f, 0xFFD6DEEB.toInt(), false).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        startupVariablesText = text("", 12f, COLOR_MUTED, false)
        startupBody.addView(text("Command", 16f, COLOR_TEXT, true))
        startupBody.addView(startupCommandText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        startupBody.addView(startupVariablesText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        startupCard.addView(startupBody)
        root.addView(startupCard.withMargins(bottom = 12))

        root.addView(sectionTitle("Lifecycle"))
        lifecycleText = text("Loading lifecycle timer...", 13f, COLOR_MUTED, false)
        root.addView(infoCard("Remaining Time", lifecycleText).withMargins(bottom = 16))

        root.addView(sectionTitle("Live Resources"))
        val liveCard = MaterialCardView(this).apply {
            setCardBackgroundColor(COLOR_CARD)
            strokeColor = COLOR_STROKE
            strokeWidth = dp(1)
            radius = dp(24).toFloat()
            cardElevation = 0f
        }
        val liveBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        statusText = text("State: loading", 16f, COLOR_TEXT, true)
        resourceText = text("CPU, memory and disk will appear here.", 13f, COLOR_MUTED, false)
        networkText = text("", 13f, COLOR_MUTED, false)
        liveBody.addView(statusText)
        liveBody.addView(resourceText)
        liveBody.addView(networkText)
        liveCard.addView(liveBody)
        root.addView(liveCard.withMargins(bottom = 16))

        root.addView(sectionTitle("Console"))
        val consoleCard = MaterialCardView(this).apply {
            setCardBackgroundColor(0xFF0D1320.toInt())
            strokeColor = COLOR_STROKE
            strokeWidth = dp(1)
            radius = dp(22).toFloat()
            cardElevation = 0f
        }
        val consoleBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        consoleStatusText = text("Connecting to console...", 12f, COLOR_MUTED, false)
        consoleText = text("Waiting for logs.", 12f, 0xFFD6DEEB.toInt(), false).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            minLines = 8
            maxLines = 14
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        consoleBody.addView(consoleStatusText)
        consoleBody.addView(consoleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        consoleBody.addView(MaterialButton(this).apply {
            text = "Reconnect Logs"
            isAllCaps = false
            cornerRadius = dp(16)
            setBackgroundColor(COLOR_ACCENT)
            setOnClickListener { connectConsole(clearExisting = true) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(12)
        })
        consoleCard.addView(consoleBody)
        root.addView(consoleCard.withMargins(bottom = 16))

        root.addView(sectionTitle("Power"))
        root.addView(buttonRow("Start", "Stop", "Restart"))
        root.addView(MaterialButton(this).apply {
            text = "Kill"
            isAllCaps = false
            cornerRadius = dp(18)
            setBackgroundColor(0xFFB3261E.toInt())
            setOnClickListener { sendPower("kill") }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(10)
            bottomMargin = dp(16)
        })

        root.addView(MaterialButton(this).apply {
            text = "Refresh"
            isAllCaps = false
            cornerRadius = dp(18)
            setBackgroundColor(COLOR_ACCENT)
            setOnClickListener {
                refreshResources()
                refreshServerDetails()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
    }

    private fun buttonRow(vararg labels: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labels.forEachIndexed { index, label ->
            row.addView(MaterialButton(this).apply {
                text = label
                isAllCaps = false
                cornerRadius = dp(18)
                setBackgroundColor(if (label == "Stop") 0xFF324158.toInt() else COLOR_ACCENT)
                setOnClickListener { sendPower(label.lowercase()) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
        return row
    }

    private fun refreshServerDetails() {
        if (serverId.isBlank()) return
        startupCommandText.text = "Loading startup command..."
        startupVariablesText.text = ""
        lifecycleText.text = "Loading lifecycle timer..."
        lifecycleHandler.removeCallbacks(lifecycleTick)
        thread {
            val startup = PanelApi.startup(applicationContext, serverId)
            val lifecycle = PanelApi.lifecycle(applicationContext, serverId)
            runOnUiThread {
                if (startup.isOk) showStartup(startup.value!!)
                else {
                    startupCommandText.text = startup.error ?: "Could not load startup command."
                    startupVariablesText.text = ""
                }

                if (lifecycle.isOk) showLifecycle(lifecycle.value!!)
                else lifecycleText.text = lifecycle.error ?: "Could not load lifecycle timer."
            }
        }
    }

    private fun showStartup(startup: PanelApi.StartupInfo) {
        startupCommandText.text = startup.startupCommand.ifBlank {
            startup.rawStartupCommand.ifBlank { "No startup command returned." }
        }
        startupVariablesText.text = if (startup.variables.isEmpty()) {
            "No startup variables visible."
        } else {
            startup.variables.joinToString("\n") { variable ->
                val label = variable.envVariable.ifBlank { variable.name.ifBlank { "Variable" } }
                "$label=${variable.value}"
            }
        }
    }

    private fun showLifecycle(lifecycle: PanelApi.LifecycleInfo) {
        lifecycleHandler.removeCallbacks(lifecycleTick)
        lifecycleDisabled = lifecycle.disabled
        lifecycleStatus = lifecycle.status
        lifecycleCanConfirm = lifecycle.canConfirm
        lifecycleTimeframeDays = lifecycle.timeframeDays
        lifecycleRemainingSeconds = lifecycle.secondsRemaining
            ?: listOfNotNull(
                lifecycle.days?.toLong()?.times(86_400),
                lifecycle.hours?.toLong()?.times(3_600),
                lifecycle.minutes?.toLong()?.times(60),
                lifecycle.seconds?.toLong(),
            ).takeIf { it.isNotEmpty() }?.sum()

        showLifecycleCountdown()
        if (!lifecycleDisabled && (lifecycleRemainingSeconds ?: 0) > 0) {
            lifecycleHandler.postDelayed(lifecycleTick, 1000)
        }
    }

    private fun showLifecycleCountdown() {
        if (lifecycleDisabled) {
            lifecycleText.text = "Lifecycle disabled"
            return
        }
        val remaining = lifecycleRemainingSeconds
        if (remaining == null) {
            lifecycleText.text = "Lifecycle timer unavailable"
            return
        }
        val days = remaining / 86_400
        val hours = (remaining % 86_400) / 3_600
        val minutes = (remaining % 3_600) / 60
        val seconds = remaining % 60
        val confirm = if (lifecycleCanConfirm) " | confirm available +${lifecycleTimeframeDays}d" else ""
        lifecycleText.text = "${formatLifecycleStatus(lifecycleStatus)}: ${days}d ${hours}h ${minutes}m ${seconds}s remaining$confirm"
    }

    private fun formatLifecycleStatus(status: String): String {
        return status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun connectConsole(clearExisting: Boolean = false) {
        if (serverId.isBlank()) return
        consoleSocket?.close(1000, "Reconnecting")
        consoleSocket = null
        if (clearExisting) {
            consoleLines.clear()
            consoleText.text = "Waiting for logs."
        }
        consoleStatusText.text = "Connecting to console..."
        thread {
            val result = PanelApi.consoleWebsocket(applicationContext, serverId)
            runOnUiThread {
                if (result.isOk) openConsoleSocket(result.value!!)
                else {
                    consoleStatusText.text = "Console unavailable"
                    consoleText.text = result.error ?: "Could not connect to console."
                }
            }
        }
    }

    private fun openConsoleSocket(details: PanelApi.ConsoleWebsocket) {
        val request = Request.Builder()
            .url(details.socket)
            .header("Origin", PanelApi.BASE)
            .build()
        consoleSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { consoleStatusText.text = "Console connected" }
                webSocket.send(JSONObject()
                    .put("event", "auth")
                    .put("args", JSONArray().put(details.token))
                    .toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleConsoleMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    if (consoleSocket != webSocket) return@runOnUiThread
                    consoleStatusText.text = "Console disconnected"
                    appendConsoleLine("[connection] ${t.message ?: "websocket failed"}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (consoleSocket == webSocket) consoleStatusText.text = "Console disconnected"
                }
            }
        })
    }

    private fun handleConsoleMessage(webSocket: WebSocket, message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (json.optString("event")) {
            "auth success" -> {
                webSocket.send(JSONObject()
                    .put("event", "send logs")
                    .put("args", JSONArray().put(JSONObject.NULL))
                    .toString())
            }
            "console output", "install output", "transfer logs", "daemon message", "daemon error" -> {
                val output = json.optJSONArray("args")?.optString(0).orEmpty()
                if (output.isNotBlank()) {
                    runOnUiThread { appendConsoleLine(stripAnsi(output).trimEnd()) }
                }
            }
            "token expiring", "token expired" -> runOnUiThread { connectConsole() }
        }
    }

    private fun appendConsoleLine(line: String) {
        if (line.isBlank()) return
        line.lineSequence().forEach { part ->
            consoleLines.addLast(part)
            while (consoleLines.size > MAX_CONSOLE_LINES) consoleLines.removeFirst()
        }
        consoleText.text = consoleLines.joinToString("\n")
    }

    private fun stripAnsi(value: String): String {
        return value.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
    }

    private fun refreshResources() {
        if (serverId.isBlank()) {
            Toast.makeText(this, "Missing server identifier.", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "State: loading"
        thread {
            val result = PanelApi.serverResources(applicationContext, serverId)
            runOnUiThread {
                if (result.isOk) showResources(result.value!!)
                else {
                    statusText.text = "State: unavailable"
                    resourceText.text = result.error ?: "Could not load resources."
                    networkText.text = ""
                }
            }
        }
    }

    private fun showResources(resources: PanelApi.ServerResources) {
        statusText.text = "State: ${resources.state}"
        resourceText.text = "CPU ${"%.1f".format(resources.cpuAbsolute)}% | Memory ${formatBytes(resources.memoryBytes)} | Disk ${formatBytes(resources.diskBytes)}"
        networkText.text = "Network ${formatBytes(resources.networkRxBytes)} down | ${formatBytes(resources.networkTxBytes)} up | Uptime ${formatUptime(resources.uptimeMillis)}"
    }

    private fun sendPower(signal: String) {
        Toast.makeText(this, "Sending $signal...", Toast.LENGTH_SHORT).show()
        thread {
            val result = PanelApi.powerAction(applicationContext, serverId, signal)
            runOnUiThread {
                if (result.isOk) {
                    Toast.makeText(this, "$signal sent", Toast.LENGTH_SHORT).show()
                    refreshResources()
                } else {
                    Toast.makeText(this, result.error ?: "Power action failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun header(title: String, subtitle: String): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(0xFF1B2436.toInt())
            strokeColor = 0xFF334058.toInt()
            strokeWidth = dp(1)
            radius = dp(28).toFloat()
            cardElevation = 0f
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(text(title, 28f, COLOR_TEXT, true))
            addView(text(subtitle, 14f, COLOR_MUTED, false))
        }
        card.addView(body)
        return card.withMargins(bottom = 24)
    }

    private fun sectionTitle(title: String): View {
        return text(title, 12f, COLOR_MUTED, true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(10), 0, dp(8))
        }
    }

    private fun infoCard(title: String, subtitle: String): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(COLOR_CARD)
            strokeColor = COLOR_STROKE
            strokeWidth = dp(1)
            radius = dp(22).toFloat()
            cardElevation = 0f
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(title, 16f, COLOR_TEXT, true))
            addView(text(subtitle, 13f, COLOR_MUTED, false))
        }
        card.addView(body)
        return card.withMargins(bottom = 12)
    }

    private fun infoCard(title: String, subtitleView: TextView): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(COLOR_CARD)
            strokeColor = COLOR_STROKE
            strokeWidth = dp(1)
            radius = dp(22).toFloat()
            cardElevation = 0f
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(title, 16f, COLOR_TEXT, true))
            addView(subtitleView)
        }
        card.addView(body)
        return card
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mib = bytes / 1024.0 / 1024.0
        return if (mib >= 1024) "%.1f GB".format(mib / 1024.0) else "%.0f MB".format(mib)
    }

    private fun formatUptime(millis: Long): String {
        if (millis <= 0) return "offline"
        val minutes = millis / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }

    private fun View.withMargins(bottom: Int = 0): View {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(bottom) }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_CARD = 0xFF1A2230.toInt()
        private const val COLOR_STROKE = 0xFF2F3B51.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_MUTED = 0xFF97A3BA.toInt()
        private const val COLOR_ACCENT = 0xFF3FA3FF.toInt()
        private const val MAX_CONSOLE_LINES = 120
    }
}
