package com.monkeybytes.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object AppPrefs {
    private const val FILE = "monkeybytes_prefs"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_FCM_TOKEN_REGISTERED = "fcm_token_registered"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PATTERN_HASH = "pattern_hash"
    private const val KEY_REMINDERS = "reminders"

    data class Reminder(
        val id: Int,
        val text: String,
        val atMillis: Long
    )

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

    fun hasPin(ctx: Context): Boolean = prefs(ctx).contains(KEY_PIN_HASH)
    fun hasPattern(ctx: Context): Boolean = prefs(ctx).contains(KEY_PATTERN_HASH)
    fun hasLocalLock(ctx: Context): Boolean = hasPin(ctx) || hasPattern(ctx)

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString(KEY_PIN_HASH, hash("pin:$pin")).apply()
    }

    fun verifyPin(ctx: Context, pin: String): Boolean =
        prefs(ctx).getString(KEY_PIN_HASH, null) == hash("pin:$pin")

    fun setPattern(ctx: Context, pattern: String) {
        prefs(ctx).edit().putString(KEY_PATTERN_HASH, hash("pattern:$pattern")).apply()
    }

    fun verifyPattern(ctx: Context, pattern: String): Boolean =
        prefs(ctx).getString(KEY_PATTERN_HASH, null) == hash("pattern:$pattern")

    fun clearLocalLocks(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PATTERN_HASH)
            .apply()
    }

    fun reminders(ctx: Context): List<Reminder> {
        val raw = prefs(ctx).getString(KEY_REMINDERS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Reminder(
                    id = item.optInt("id"),
                    text = item.optString("text", "Reminder"),
                    atMillis = item.optLong("atMillis")
                )
            }.filter { it.id != 0 && it.atMillis > 0 }
                .sortedBy { it.atMillis }
        }.getOrDefault(emptyList())
    }

    fun saveReminder(ctx: Context, reminder: Reminder) {
        val updated = reminders(ctx)
            .filterNot { it.id == reminder.id }
            .plus(reminder)
            .sortedBy { it.atMillis }
        saveReminders(ctx, updated)
    }

    fun deleteReminder(ctx: Context, id: Int) {
        saveReminders(ctx, reminders(ctx).filterNot { it.id == id })
    }

    private fun saveReminders(ctx: Context, reminders: List<Reminder>) {
        val array = JSONArray()
        reminders.forEach { reminder ->
            array.put(JSONObject().apply {
                put("id", reminder.id)
                put("text", reminder.text)
                put("atMillis", reminder.atMillis)
            })
        }
        prefs(ctx).edit().putString(KEY_REMINDERS, array.toString()).apply()
    }

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
