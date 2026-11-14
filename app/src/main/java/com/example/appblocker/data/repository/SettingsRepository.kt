package com.example.appblocker.data.repository

import android.content.Context

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    var lastUsageSync: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /**
     * Latest end time of any active lock. Kept in SharedPreferences so callers that cannot
     * suspend (for example DeviceAdminReceiver.onDisableRequested) can read it instantly
     * without touching the database or blocking the main thread.
     */
    var lockedUntil: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKED_UNTIL, value).apply()

    val isLockActive: Boolean
        get() = lockedUntil > System.currentTimeMillis()

    /** "Locked screen" page: show the block screen (true) or just go to the app's home (false). */
    var lockScreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_SCREEN_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_SCREEN_ON, value).apply()

    /** Cropped background picked on the "Locked screen" page, as a data: URL. Empty if none. */
    var lockScreenImage: String
        get() = prefs.getString(KEY_LOCK_SCREEN_IMAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LOCK_SCREEN_IMAGE, value).apply()

    /** Other page settings (theme, defaults, block-screen message, ...) as one JSON object. */
    var extraPrefs: String
        get() = prefs.getString(KEY_EXTRA, "{}").orEmpty()
        set(value) = prefs.edit().putString(KEY_EXTRA, value).apply()

    private companion object {
        const val KEY_EXTRA = "extra_prefs"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_LAST_SYNC = "last_usage_sync"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_LOCK_SCREEN_ON = "lock_screen_on"
        const val KEY_LOCK_SCREEN_IMAGE = "lock_screen_image"
    }
}
