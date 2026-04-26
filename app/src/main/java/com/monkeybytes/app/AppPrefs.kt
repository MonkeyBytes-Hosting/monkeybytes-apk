package com.monkeybytes.app

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val FILE = "monkeybytes_prefs"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_FCM_TOKEN_REGISTERED = "fcm_token_registered"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiToken(ctx: Context): String? = prefs(ctx).getString(KEY_API_TOKEN, null)
    fun setApiToken(ctx: Context, token: String?) {
        prefs(ctx).edit().putString(KEY_API_TOKEN, token).apply()
        if (token == null) {
            prefs(ctx).edit().remove(KEY_FCM_TOKEN_REGISTERED).apply()
        }
    }

    fun lastRegisteredFcmToken(ctx: Context): String? =
        prefs(ctx).getString(KEY_FCM_TOKEN_REGISTERED, null)
    fun setLastRegisteredFcmToken(ctx: Context, token: String?) {
        prefs(ctx).edit().putString(KEY_FCM_TOKEN_REGISTERED, token).apply()
    }
}
