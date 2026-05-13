package com.monkeybytes.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.monkeybytes.app.databinding.ActivityMainBinding
import java.util.Calendar
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executor
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo
    private var nodeRowsExpanded = true
    private var latestNodes: List<PanelApi.NodeStatus> = emptyList()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMotion()
        wireMenu()
        setupBiometric()

        if (canUseBiometric()) {
            binding.root.visibility = View.INVISIBLE
            biometricPrompt.authenticate(promptInfo)
        } else if (AppPrefs.hasLocalLock(this)) {
            binding.root.visibility = View.INVISIBLE
            showUnlockDialog()
        } else {
            onUnlocked()
        }
    }

    private fun wireMenu() {
        binding.btnDashboard.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        binding.btnDiscord.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/s5bEbHycZn")))
        }
        binding.btnStatus.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://status.mbint.dpdns.org/")))
        }
        binding.btnLogout.setOnClickListener {
            confirmLogout()
        }
        binding.btnRefreshStatus.setOnClickListener { refreshPublicStatus() }
        binding.nodeStatusToggle.setOnClickListener {
            nodeRowsExpanded = !nodeRowsExpanded
            applyNodeRowsVisibility()
        }
        binding.btnReminder.setOnClickListener { showReminders() }

        binding.btnGame.setOnClickListener {
            startActivity(Intent(this, RunnerGameActivity::class.java))
        }
        binding.btnBiometric.setOnClickListener { showAppLockOptions() }
    }

    private fun setupMotion() {
        val animatedViews = listOf(
            binding.heroPanel,
            binding.btnDashboard,
            binding.btnStatus,
            binding.btnBiometric,
            binding.btnReminder,
            binding.btnDiscord,
            binding.btnGame,
            binding.btnLogout
        )
        animatedViews.forEach { view ->
            view.alpha = 0f
            view.translationY = dp(18).toFloat()
        }
        listOf(
            binding.btnDashboard,
            binding.btnStatus,
            binding.btnBiometric,
            binding.btnReminder,
            binding.btnDiscord,
            binding.btnGame,
            binding.btnLogout,
            binding.btnRefreshStatus,
            binding.nodeStatusToggle
        ).forEach(::addPressMotion)
    }

    private fun animateHomeIn() {
        val animatedViews = listOf(
            binding.heroPanel,
            binding.btnDashboard,
            binding.btnStatus,
            binding.btnBiometric,
            binding.btnReminder,
            binding.btnDiscord,
            binding.btnGame,
            binding.btnLogout
        )
        animatedViews.forEachIndexed { index, view ->
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 55L)
                .setDuration(420L)
                .setInterpolator(if (index == 0) OvershootInterpolator(0.8f) else DecelerateInterpolator())
                .start()
        }
    }

    private fun addPressMotion(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.animate()
                    .scaleX(0.975f)
                    .scaleY(0.975f)
                    .setDuration(90L)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            }
            false
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout?")
            .setMessage("This signs you out on this device.")
            .setPositiveButton("Logout") { _, _ ->
                AppPrefs.setApiToken(this, null)
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshMenuContext() {
        updateReminderSubtitle()
        refreshDashboardSubtitle()
    }

    private fun refreshDashboardSubtitle() {
        if (AppPrefs.apiToken(this) == null) {
            binding.dashboardSubtitle.text = "Sign in to manage servers"
            return
        }
        binding.dashboardSubtitle.text = "Loading servers..."
        thread {
            val result = PanelApi.dashboard(applicationContext)
            val servers = result.value?.servers.orEmpty()
            runOnUiThread {
                binding.dashboardSubtitle.text = if (result.isOk) {
                    when (servers.size) {
                        0 -> "No servers available"
                        1 -> "1 server available"
                        else -> "${servers.size} servers available"
                    }
                } else {
                    "Manage servers and resources"
                }
            }
        }
    }

    private fun updateReminderSubtitle() {
        val nextReminder = AppPrefs.reminders(this)
            .firstOrNull { it.atMillis >= System.currentTimeMillis() }
        binding.reminderSubtitle.text = if (nextReminder == null) {
            "No reminders scheduled"
        } else {
            "Next: ${formatShortDateTime(nextReminder.atMillis)}"
        }
    }

    private fun showReminders() {
        val reminders = AppPrefs.reminders(this)
        if (reminders.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Reminders")
                .setMessage("No reminders scheduled.")
                .setPositiveButton("Create") { _, _ -> showReminderEditor(null) }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        val items = reminders.map { reminder ->
            "${reminder.text}\n${formatReminderTime(reminder.atMillis)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Reminders")
            .setItems(items) { _, which -> showReminderActions(reminders[which]) }
            .setPositiveButton("Create") { _, _ -> showReminderEditor(null) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showReminderActions(reminder: AppPrefs.Reminder) {
        AlertDialog.Builder(this)
            .setTitle(reminder.text)
            .setMessage(formatReminderTime(reminder.atMillis))
            .setPositiveButton("Edit") { _, _ -> showReminderEditor(reminder) }
            .setNegativeButton("Delete") { _, _ -> confirmDeleteReminder(reminder) }
            .setNeutralButton("Back") { _, _ -> showReminders() }
            .show()
    }

    private fun confirmDeleteReminder(reminder: AppPrefs.Reminder) {
        AlertDialog.Builder(this)
            .setTitle("Delete reminder?")
            .setMessage("${reminder.text}\n${formatReminderTime(reminder.atMillis)}")
            .setPositiveButton("Delete") { _, _ ->
                cancelReminder(reminder.id)
                AppPrefs.deleteReminder(this, reminder.id)
                updateReminderSubtitle()
                Toast.makeText(this, "Reminder deleted.", Toast.LENGTH_SHORT).show()
                showReminders()
            }
            .setNegativeButton("Cancel") { _, _ -> showReminderActions(reminder) }
            .show()
    }

    private fun showReminderEditor(existing: AppPrefs.Reminder?) {
        val input = EditText(this).apply {
            hint = "What's the reminder?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.text.orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New reminder" else "Edit reminder")
            .setView(input)
            .setPositiveButton("Pick time") { _, _ ->
                val text = input.text.toString().ifBlank { "Reminder" }
                val now = Calendar.getInstance()
                TimePickerDialog(this, { _, h, m ->
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, h)
                        set(Calendar.MINUTE, m)
                        set(Calendar.SECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                    }
                    val id = existing?.id ?: newReminderId()
                    if (existing != null) cancelReminder(existing.id)
                    scheduleReminder(id, text, cal.timeInMillis)
                    AppPrefs.saveReminder(this, AppPrefs.Reminder(id, text, cal.timeInMillis))
                    updateReminderSubtitle()
                    showReminders()
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }
            .setNegativeButton("Cancel") { _, _ -> showReminders() }
            .show()
    }

    private fun showAppLockOptions() {
        val options = mutableListOf("Set PIN", "Set Pattern")
        if (canUseBiometric()) options += "Manage Biometrics"
        if (AppPrefs.hasLocalLock(this)) options += "Disable PIN/Pattern"

        AlertDialog.Builder(this)
            .setTitle("App Lock")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Set PIN" -> showSetPinDialog()
                    "Set Pattern" -> showSetPatternDialog()
                    "Manage Biometrics" -> openBiometricSettings()
                    "Disable PIN/Pattern" -> {
                        AppPrefs.clearLocalLocks(this)
                        updateLockSubtitle()
                        Toast.makeText(this, "PIN and pattern removed.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openBiometricSettings() {
        val tries = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tries += Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BIOMETRIC_STRONG or BIOMETRIC_WEAK
            )
        }
        tries += Intent("android.settings.FINGERPRINT_ENROLL")
        tries += Intent(Settings.ACTION_SECURITY_SETTINGS)
        tries += Intent(Settings.ACTION_SETTINGS)
        for (intent in tries) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                break
            }
        }
    }

    private fun showSetPinDialog() {
        val input = EditText(this).apply {
            hint = "Enter at least 4 digits"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Set PIN")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val pin = input.text.toString()
                        if (pin.length < 4) {
                            input.error = "Use at least 4 digits"
                        } else {
                            AppPrefs.setPin(this@MainActivity, pin)
                            updateLockSubtitle()
                            dismiss()
                            Toast.makeText(this@MainActivity, "PIN saved.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                show()
            }
    }

    private fun showSetPatternDialog() {
        showPatternDialog(
            title = "Set Pattern",
            positiveText = "Save",
            cancelable = true
        ) { pattern ->
            if (pattern.length < 4) {
                Toast.makeText(this, "Use at least 4 dots.", Toast.LENGTH_SHORT).show()
                false
            } else {
                AppPrefs.setPattern(this, pattern)
                updateLockSubtitle()
                Toast.makeText(this, "Pattern saved.", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun showUnlockDialog() {
        when {
            AppPrefs.hasPin(this) -> showPinUnlockDialog()
            AppPrefs.hasPattern(this) -> showPatternUnlockDialog()
            else -> onUnlocked()
        }
    }

    private fun showPinUnlockDialog() {
        val input = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Unlock MonkeyBytes")
            .setView(input)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Close") { _, _ -> finishAffinity() }
            .setCancelable(false)
        if (AppPrefs.hasPattern(this)) {
            builder.setNeutralButton("Pattern", null)
        }
        builder
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (AppPrefs.verifyPin(this@MainActivity, input.text.toString())) {
                            dismiss()
                            onUnlocked()
                        } else {
                            input.error = "Incorrect PIN"
                        }
                    }
                    getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                        dismiss()
                        showPatternUnlockDialog()
                    }
                }
                show()
            }
    }

    private fun showPatternUnlockDialog() {
        showPatternDialog(
            title = "Unlock MonkeyBytes",
            positiveText = "Unlock",
            cancelable = false,
            negativeText = "Close",
            onNegative = { finishAffinity() }
        ) { pattern ->
            if (AppPrefs.verifyPattern(this, pattern)) {
                onUnlocked()
                true
            } else {
                Toast.makeText(this, "Incorrect pattern.", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun showPatternDialog(
        title: String,
        positiveText: String,
        cancelable: Boolean,
        negativeText: String = "Cancel",
        onNegative: (() -> Unit)? = null,
        onSubmit: (String) -> Boolean
    ) {
        val selected = mutableListOf<Int>()
        val status = TextView(this).apply {
            text = "Tap at least 4 dots"
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val buttons = mutableListOf<Button>()

        fun refresh() {
            status.text = "Pattern: ${"*".repeat(selected.size)}"
            buttons.forEachIndexed { index, button ->
                val dot = index + 1
                button.text = if (selected.contains(dot)) "*" else dot.toString()
                button.isEnabled = !selected.contains(dot)
            }
        }

        for (rowIndex in 0 until 3) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (colIndex in 0 until 3) {
                val dot = rowIndex * 3 + colIndex + 1
                val button = Button(this).apply {
                    text = dot.toString()
                    textSize = 18f
                    setOnClickListener {
                        selected += dot
                        refresh()
                    }
                }
                buttons += button
                row.addView(button, LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    if (colIndex > 0) leftMargin = dp(8)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (rowIndex > 0) topMargin = dp(8)
            })
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(status)
            addView(grid)
        }
        refresh()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(body)
            .setPositiveButton(positiveText, null)
            .setNegativeButton(negativeText) { _, _ -> onNegative?.invoke() }
            .setNeutralButton("Clear", null)
            .setCancelable(cancelable)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (onSubmit(selected.joinToString(""))) dismiss()
                    }
                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        selected.clear()
                        refresh()
                    }
                }
                show()
            }
    }

    private fun scheduleReminder(id: Int, text: String, atMillis: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("title", "MonkeyBytes Reminder")
            putExtra("body", text)
            putExtra("id", id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
        }
        val mins = ((atMillis - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
        Toast.makeText(this, "Reminder set for ${mins}m from now", Toast.LENGTH_SHORT).show()
    }

    private fun cancelReminder(id: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            id,
            Intent(this, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun newReminderId(): Int =
        (System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1)

    private fun formatReminderTime(atMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(atMillis))

    private fun formatShortDateTime(atMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(atMillis))

    private fun formatShortTime(atMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(atMillis))

    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (AppPrefs.hasLocalLock(this@MainActivity)) {
                        showUnlockDialog()
                    } else {
                        finishAffinity()
                    }
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open.
                }
            }
        )

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("MonkeyBytes")
            .setSubtitle("Verify to continue")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build()
    }

    private fun canUseBiometric(): Boolean {
        val biometricManager = BiometricManager.from(this)
        return biometricManager.canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun onUnlocked() {
        binding.root.visibility = View.VISIBLE
        updateLockSubtitle()
        refreshMenuContext()
        refreshPublicStatus()
        animateHomeIn()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (AppPrefs.apiToken(this) == null) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token != AppPrefs.lastRegisteredFcmToken(this)) {
                thread {
                    if (PanelApi.registerPushToken(applicationContext, token)) {
                        AppPrefs.setLastRegisteredFcmToken(applicationContext, token)
                    }
                }
            }
        }
    }

    private fun refreshPublicStatus() {
        binding.panelStatusText.text = "Checking"
        binding.panelStatusText.setTextColor(COLOR_PENDING)
        binding.networkStatusText.text = "Checking"
        binding.networkStatusText.setTextColor(COLOR_PENDING)
        binding.nodesStatusText.text = "Checking"
        binding.nodesStatusText.setTextColor(COLOR_PENDING)
        binding.statusLastCheckedText.text = "Checking now"
        binding.nodeStatusToggle.visibility = View.GONE
        binding.nodeStatusRows.visibility = View.GONE
        binding.nodeStatusRows.removeAllViews()

        thread {
            val panelOnline = PanelApi.panelOnline()
            val nodes = PanelApi.publicStatus().value.orEmpty()
            val onlineNodes = nodes.count { it.isOnline }
            val allNodesOnline = nodes.isNotEmpty() && onlineNodes == nodes.size

            runOnUiThread {
                latestNodes = nodes
                binding.panelStatusText.text = if (panelOnline) "Online" else "Offline"
                binding.panelStatusText.setTextColor(if (panelOnline) COLOR_ONLINE else COLOR_OFFLINE)
                binding.networkStatusText.text = when {
                    nodes.isEmpty() -> "No nodes"
                    allNodesOnline -> "$onlineNodes/${nodes.size} live"
                    onlineNodes > 0 -> "$onlineNodes/${nodes.size} partial"
                    else -> "0/${nodes.size} down"
                }
                binding.networkStatusText.setTextColor(when {
                    allNodesOnline -> COLOR_ONLINE
                    onlineNodes > 0 -> COLOR_WARNING
                    else -> COLOR_OFFLINE
                })
                binding.nodesStatusText.text = if (nodes.isEmpty()) {
                    "No nodes"
                } else {
                    "$onlineNodes/${nodes.size} online"
                }
                binding.nodesStatusText.setTextColor(when {
                    allNodesOnline -> COLOR_ONLINE
                    onlineNodes > 0 -> COLOR_WARNING
                    else -> COLOR_OFFLINE
                })
                binding.statusLastCheckedText.text = "Last checked ${formatShortTime(System.currentTimeMillis())}"
                showNodeStatuses(nodes)
            }
        }
    }

    private fun showNodeStatuses(nodes: List<PanelApi.NodeStatus>) {
        binding.nodeStatusRows.removeAllViews()
        if (nodes.isEmpty()) {
            binding.nodeStatusToggle.visibility = View.GONE
            binding.nodeStatusRows.visibility = View.GONE
            return
        }
        binding.nodeStatusToggle.visibility = View.VISIBLE
        nodes.forEachIndexed { index, node ->
            binding.nodeStatusRows.addView(nodeStatusRow(node), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                if (index > 0) topMargin = dp(6)
            })
        }
        applyNodeRowsVisibility()
    }

    private fun nodeStatusRow(node: PanelApi.NodeStatus): View {
        return LinearLayout(this).apply {
            isClickable = true
            isFocusable = true
            gravity = android.view.Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "${node.name}: ${if (node.isOnline) "online" else "offline"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (node.isOnline) COLOR_ONLINE else COLOR_OFFLINE)
                }
            }, LinearLayout.LayoutParams(dp(9), dp(9)))
            addView(TextView(this@MainActivity).apply {
                text = node.name
                textSize = 13f
                setTextColor(COLOR_TEXT)
                setSingleLine(true)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
                rightMargin = dp(8)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (node.isOnline) "Live" else "Offline"
                textSize = 13f
                setTextColor(if (node.isOnline) COLOR_ONLINE else COLOR_OFFLINE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
    }

    private fun applyNodeRowsVisibility() {
        val hasNodes = latestNodes.isNotEmpty()
        binding.nodeStatusToggle.visibility = if (hasNodes) View.VISIBLE else View.GONE
        binding.nodeStatusToggle.text = if (nodeRowsExpanded) "Hide nodes" else "Show nodes"
        binding.nodeStatusRows.visibility = if (hasNodes && nodeRowsExpanded) View.VISIBLE else View.GONE
    }

    private fun updateLockSubtitle() {
        binding.biometricSubtitle.text = when {
            AppPrefs.hasPin(this) && AppPrefs.hasPattern(this) -> "PIN and pattern enabled"
            AppPrefs.hasPin(this) -> "PIN enabled"
            AppPrefs.hasPattern(this) -> "Pattern enabled"
            canUseBiometric() -> "Biometrics available"
            else -> "Set up PIN or pattern"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_ONLINE = 0xFFD9FFE5.toInt()
        private const val COLOR_WARNING = 0xFFFFD180.toInt()
        private const val COLOR_OFFLINE = 0xFFFF9A9A.toInt()
        private const val COLOR_PENDING = 0xFFB7C0D6.toInt()
    }
}
