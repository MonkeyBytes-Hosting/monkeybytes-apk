package com.monkeybytes.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.concurrent.thread

class DashboardActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var loading: ProgressBar
    private var pendingServerUuid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingServerUuid = intent.getStringExtra("server_uuid")
        buildLayout()
        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) loadDashboard()
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
    }

    private fun loadDashboard() {
        if (AppPrefs.apiToken(this) == null) {
            showSignIn()
            return
        }

        root.removeAllViews()
        root.addView(header("Dashboard", "Loading your Pterodactyl account"))
        loading = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
        }
        root.addView(loading, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(32)
        })

        thread {
            val result = PanelApi.dashboard(applicationContext)
            runOnUiThread {
                if (result.isOk) showDashboard(result.value!!)
                else showError(result.error ?: "Unable to load dashboard.")
            }
        }
    }

    private fun showSignIn() {
        root.removeAllViews()
        root.addView(header("Dashboard", "Connect to Pterodactyl"))
        root.addView(infoCard("Login required", "Sign in here. The app will request an API token without opening the website."))

        val userInput = EditText(this).apply {
            hint = "Email or username"
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val passwordInput = EditText(this).apply {
            hint = "Password"
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(inputCard(userInput).withMargins(bottom = 10))
        root.addView(inputCard(passwordInput).withMargins(bottom = 14))

        root.addView(MaterialButton(this).apply {
            text = "Login"
            isAllCaps = false
            cornerRadius = dp(18)
            setBackgroundColor(COLOR_ACCENT)
            setOnClickListener {
                val user = userInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (user.isBlank() || password.isBlank()) {
                    Toast.makeText(this@DashboardActivity, "Enter your login details.", Toast.LENGTH_SHORT).show()
                } else {
                    login(user, password)
                }
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(8)
        })

        root.addView(warningCard(
            "Discord signups",
            "If you signed up with Discord, contact admins on the Discord server to get your password. Discord accounts are assigned a random password."
        ).withMargins(bottom = 12))
    }

    private fun login(user: String, password: String) {
        root.removeAllViews()
        root.addView(header("Dashboard", "Signing in"))
        root.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(32)
        })

        thread {
            val result = PanelApi.login(user, password)
            runOnUiThread {
                if (result.isOk && !result.value.isNullOrBlank()) {
                    AppPrefs.setApiToken(applicationContext, result.value)
                    loadDashboard()
                } else {
                    Toast.makeText(this, result.error ?: "Login failed.", Toast.LENGTH_LONG).show()
                    showSignIn()
                }
            }
        }
    }

    private fun showDashboard(dashboard: PanelApi.Dashboard) {
        root.removeAllViews()
        root.addView(header("Dashboard", "Manage your servers"))

        dashboard.account?.let { account ->
            root.addView(sectionTitle("Account"))
            root.addView(infoCard(account.name, "${account.username}  ${account.email}"))
        }

        root.addView(sectionTitle("Servers"))
        if (dashboard.servers.isEmpty()) {
            root.addView(infoCard("No servers", "There are no servers available for this API key."))
        } else {
            dashboard.servers.forEach { server ->
                root.addView(serverCard(server))
            }
        }

        pendingServerUuid?.let { uuid ->
            val match = dashboard.servers.firstOrNull { it.uuid == uuid || it.identifier == uuid }
            pendingServerUuid = null
            if (match != null) openServer(match)
        }
    }

    private fun showError(message: String) {
        root.removeAllViews()
        root.addView(header("Dashboard", "Pterodactyl API"))
        root.addView(infoCard("Could not load", message))
        root.addView(MaterialButton(this).apply {
            text = "Retry"
            isAllCaps = false
            cornerRadius = dp(18)
            setBackgroundColor(COLOR_ACCENT)
            setOnClickListener { loadDashboard() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(16)
        })
    }

    private fun serverCard(server: PanelApi.Server): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(COLOR_CARD)
            strokeColor = COLOR_STROKE
            strokeWidth = dp(1)
            radius = dp(22).toFloat()
            isClickable = true
            isFocusable = true
            rippleColor = android.content.res.ColorStateList.valueOf(COLOR_ACCENT)
            setOnClickListener { openServer(server) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        body.addView(text(server.name, 19f, COLOR_TEXT, true))
        val status = when {
            server.isSuspended -> "Suspended"
            server.isInstalling -> "Installing"
            else -> "Ready"
        }
        body.addView(text("$status | ${server.node}", 13f, COLOR_MUTED, false))
        body.addView(text("Memory ${server.memoryMb} MB | Disk ${server.diskMb} MB | CPU ${server.cpuLimit}%", 13f, COLOR_MUTED, false))
        card.addView(body)
        return card.withMargins(bottom = 12)
    }

    private fun openServer(server: PanelApi.Server) {
        startActivity(Intent(this, ServerDetailActivity::class.java).apply {
            putExtra("identifier", server.identifier)
            putExtra("uuid", server.uuid)
            putExtra("name", server.name)
            putExtra("node", server.node)
            putExtra("description", server.description)
            putExtra("memory_mb", server.memoryMb)
            putExtra("disk_mb", server.diskMb)
            putExtra("cpu_limit", server.cpuLimit)
        })
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

    private fun inputCard(input: EditText): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(COLOR_CARD)
            strokeColor = COLOR_STROKE
            strokeWidth = dp(1)
            radius = dp(18).toFloat()
            cardElevation = 0f
        }
        input.setPadding(dp(14), dp(6), dp(14), dp(6))
        card.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54),
        ))
        return card
    }

    private fun warningCard(title: String, subtitle: String): View {
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(0xFF2A1114.toInt())
            strokeColor = 0xFFE53935.toInt()
            strokeWidth = dp(1)
            radius = dp(22).toFloat()
            cardElevation = 0f
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text(title, 15f, 0xFFFFDAD6.toInt(), true))
            addView(text(subtitle, 13f, 0xFFFFB4AB.toInt(), false))
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
