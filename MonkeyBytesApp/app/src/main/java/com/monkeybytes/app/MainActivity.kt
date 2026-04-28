package com.monkeybytes.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
