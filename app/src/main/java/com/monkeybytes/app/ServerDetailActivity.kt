package com.monkeybytes.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.concurrent.thread

class ServerDetailActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var resourceText: TextView
    private lateinit var networkText: TextView
    private lateinit var serverId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = intent.getStringExtra("identifier").orEmpty()
        buildLayout()
        refreshResources()
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
            setOnClickListener { refreshResources() }
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
    }
}
