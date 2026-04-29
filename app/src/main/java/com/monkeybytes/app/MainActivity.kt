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
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
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
import java.util.concurrent.Executor
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

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
        } else {
            onUnlocked()
        }
    }

    private fun wireMenu() {
        binding.btnDashboard.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        binding.btnDiscord.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/kQbasjfGaM")))
        }
        binding.btnStatus.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://status.mbint.dpdns.org/")))
        }
        binding.btnLogout.setOnClickListener {
            AppPrefs.setApiToken(this, null)
            recreate()
        }
        binding.btnReminder.setOnClickListener { showReminderDialog() }

        binding.btnBiometric.setOnClickListener {
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
    }

    private fun setupMotion() {
        val animatedViews = listOf(
            binding.heroPanel,
            binding.btnDashboard,
            binding.btnDiscord,
            binding.btnStatus,
            binding.btnReminder,
            binding.btnBiometric,
            binding.btnLogout
        )
        animatedViews.forEach { view ->
            view.alpha = 0f
            view.translationY = dp(18).toFloat()
        }
        listOf(
            binding.btnDashboard,
            binding.btnDiscord,
            binding.btnStatus,
            binding.btnReminder,
            binding.btnBiometric,
            binding.btnLogout
        ).forEach(::addPressMotion)
    }

    private fun animateHomeIn() {
        val animatedViews = listOf(
            binding.heroPanel,
            binding.btnDashboard,
            binding.btnDiscord,
            binding.btnStatus,
            binding.btnReminder,
            binding.btnBiometric,
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

    private fun showReminderDialog() {
        val input = EditText(this).apply {
            hint = "What's the reminder?"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle("New reminder")
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
                    scheduleReminder(text, cal.timeInMillis)
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleReminder(text: String, atMillis: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = (atMillis / 1000).toInt()
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
                    finishAffinity()
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
        binding.biometricSubtitle.text =
            if (canUseBiometric()) "Active - manage in Settings" else "Set up fingerprint or face"
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
