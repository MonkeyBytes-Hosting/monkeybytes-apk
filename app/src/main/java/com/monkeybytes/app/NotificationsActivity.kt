package com.monkeybytes.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class NotificationsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var refreshButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        refreshButton.setOnClickListener { refreshNotifications() }
        refreshNotifications()
    }

    private fun buildView(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(COLOR_BG)
            addView(ScrollView(this@NotificationsActivity).apply {
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                setPadding(dp(20), dp(48), dp(20), dp(24))
                addView(LinearLayout(this@NotificationsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(this@NotificationsActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        orientation = LinearLayout.HORIZONTAL
                        addView(LinearLayout(this@NotificationsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(text("Notifications", 30f, COLOR_TEXT, true))
                            addView(text("Synced from the panel", 14f, COLOR_MUTED, false).apply {
                                setPadding(0, dp(4), 0, 0)
                            })
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        refreshButton = MaterialButton(this@NotificationsActivity).apply {
                            text = "Refresh"
                            textSize = 13f
                            isAllCaps = false
                            setTextColor(COLOR_TEXT)
                            backgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_CARD)
                            strokeColor = android.content.res.ColorStateList.valueOf(COLOR_STROKE)
                            strokeWidth = dp(1)
                        }
                        addView(refreshButton, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            dp(44)
                        ))
                    })
                    status = text("Loading...", 13f, COLOR_MUTED, false).apply {
                        setPadding(0, dp(18), 0, dp(10))
                    }
                    addView(status)
                    list = LinearLayout(this@NotificationsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    addView(list)
                })
            })
        }
    }

    private fun refreshNotifications() {
        refreshButton.isEnabled = false
        status.text = "Syncing..."
        list.removeAllViews()
        thread {
            val result = PanelApi.notifications(applicationContext)
            runOnUiThread {
                refreshButton.isEnabled = true
                if (!result.isOk) {
                    status.text = result.error ?: "Could not load notifications."
                    Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val items = result.value.orEmpty()
                status.text = when (items.size) {
                    0 -> "No notifications right now."
                    1 -> "1 current notification"
                    else -> "${items.size} current notifications"
                }
                if (items.isEmpty()) {
                    list.addView(emptyState())
                } else {
                    items.forEach { list.addView(notificationRow(it)) }
                }
            }
        }
    }

    private fun notificationRow(item: PanelApi.NotificationItem): View {
        val accent = colorFor(item.severity)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = rounded(COLOR_CARD, dp(16), COLOR_STROKE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(View(this@NotificationsActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                topMargin = dp(5)
                rightMargin = dp(12)
            })
            addView(LinearLayout(this@NotificationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(item.title, 17f, COLOR_TEXT, true))
                if (item.body.isNotBlank()) {
                    addView(text(htmlToText(item.body), 14f, COLOR_MUTED, false).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                }
                val meta = listOf(item.kind.replaceFirstChar { it.uppercase() }, item.displayTime)
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
                if (meta.isNotBlank()) {
                    addView(text(meta, 12f, COLOR_DIM, false).apply {
                        setPadding(0, dp(10), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun emptyState(): View {
        return TextView(this).apply {
            text = "Announcements and account activity will appear here."
            textSize = 14f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            background = rounded(COLOR_CARD, dp(16), COLOR_STROKE)
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun htmlToText(value: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(value).toString()
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun colorFor(severity: String): Int {
        return when (severity.lowercase()) {
            "success" -> 0xFF34A853.toInt()
            "warning" -> 0xFFF4B400.toInt()
            "danger" -> 0xFFEA4335.toInt()
            else -> 0xFF3FA3FF.toInt()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_BG = 0xFF07131A.toInt()
        private const val COLOR_CARD = 0xFF1A2230.toInt()
        private const val COLOR_STROKE = 0xFF2F3B51.toInt()
        private const val COLOR_TEXT = Color.WHITE
        private const val COLOR_MUTED = 0xFFB7C0D6.toInt()
        private const val COLOR_DIM = 0xFF8F9AB3.toInt()
    }
}
