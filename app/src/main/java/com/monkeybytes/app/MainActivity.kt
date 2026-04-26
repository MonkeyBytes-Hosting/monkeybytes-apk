package com.monkeybytes.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import com.monkeybytes.app.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* fine either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDashboard.setOnClickListener {
            if (AppPrefs.apiToken(this) == null) {
                startDiscordLogin()
            } else {
                openWeb("https://dash.monkey-network.xyz/")
            }
        }
        binding.btnDiscord.setOnClickListener { openExternal("https://discord.gg/kQbasjfGaM") }
        binding.btnStatus.setOnClickListener { openWeb("https://status.mbint.dpdns.org/") }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        ensureFcmRegistered()
    }

    private fun ensureFcmRegistered() {
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

    private fun startDiscordLogin() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PanelApi.DISCORD_LOGIN_URL)))
    }

    private fun openWeb(url: String) {
        startActivity(Intent(this, WebViewActivity::class.java).putExtra("url", url))
    }

    private fun openExternal(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
