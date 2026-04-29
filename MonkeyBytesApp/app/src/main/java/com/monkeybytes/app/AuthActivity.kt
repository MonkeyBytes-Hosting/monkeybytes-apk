package com.monkeybytes.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.concurrent.thread

class AuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data: Uri? = intent?.data
        val key = data?.getQueryParameter("key")
        if (!key.isNullOrBlank()) {
            AppPrefs.setApiToken(applicationContext, key)
            registerFcmToken()
            startActivity(Intent(this, DashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            finish()
            return
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }

    private fun registerFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            thread {
                if (PanelApi.registerPushToken(applicationContext, token)) {
                    AppPrefs.setLastRegisteredFcmToken(applicationContext, token)
                }
            }
        }
    }
}
