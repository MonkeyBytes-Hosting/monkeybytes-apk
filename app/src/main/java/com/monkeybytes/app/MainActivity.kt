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
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.Calendar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.monkeybytes.app.databinding.ActivityMainBinding
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

        wireMenu()
        setupBiometric()

        if (canUseBiometric()) {
            binding.root.visibility = android.view.View.INVISIBLE
            biometricPrompt.authenticate(promptInfo)
        } else {
            onUnlocked()
        }
    }

    private fun wireMenu() {
        binding.btnDashboard.setOnClickListener {
            if (AppPrefs.apiToken(this) == null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PanelApi.DISCORD_LOGIN_URL)))
            } else {
                startActivity(
                    Intent(this, WebViewActivity::class.java)
                        .putExtra("url", "https://dash.monkey-network.xyz")
                )
            }
        }
        binding.btnDiscord.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/kQbasjfGaM")))
        }
        binding.btnStatus.setOnClickListener {
            startActivity(
                Intent(this, WebViewActivity::class.java)
                    .putExtra("url", "https://status.mbint.dpdns.org/")
            )
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
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG or BIOMETRIC_WEAK
                )
            }
            tries += Intent("android.settings.FINGERPRINT_ENROLL")
            tries += Intent(Settings.ACTION_SECURITY_SETTINGS)
            tries += Intent(Settings.ACTION_SETTINGS)
            for (i in tries) {
                if (i.resolveActivity(packageManager) != null) {
                    startActivity(i); break
                }
            }
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
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = (atMillis / 1000).toInt()
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("title", "MonkeyBytes Reminder")
            putExtra("body", text)
            putExtra("id", id)
        }
        val pi = PendingIntent.getBroadcast(
            this, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
        val mins = ((atMillis - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
        Toast.makeText(this, "Reminder set for ${mins}m from now", Toast.LENGTH_SHORT).show()
    }

    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    finishAffinity()
                }
                override fun onAuthenticationFailed() { /* keep prompt open */ }
            })

        // BIOMETRIC_WEAK accepts face/iris/fingerprint regardless of class.
        // Cannot combine with DEVICE_CREDENTIAL on API < 30, so use biometric-only for compatibility.
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("MonkeyBytes")
            .setSubtitle("Verify to continue")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
            .build()
    }

    private fun canUseBiometric(): Boolean {
        val bm = BiometricManager.from(this)
        return bm.canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun onUnlocked() {
        binding.root.visibility = android.view.View.VISIBLE
        binding.biometricSubtitle.text =
            if (canUseBiometric()) "Active — manage in Settings" else "Set up fingerprint or face"
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
}
