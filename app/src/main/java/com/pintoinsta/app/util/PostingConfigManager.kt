package com.pintoinsta.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages preferences for Smart AI Captions, First Comment hashtags, posting configuration,
 * background service execution, and app updates.
 */
object PostingConfigManager {

    private const val PREFS_NAME = "crossai_posting_config"

    private const val KEY_SMART_CAPTIONS = "key_smart_captions"
    private const val KEY_FIRST_COMMENT_HASHTAGS = "key_first_comment_hashtags"
    private const val KEY_CREDIT_ATTRIBUTION = "key_credit_attribution"
    private const val KEY_BIOMETRIC_LOCK = "key_biometric_lock"
    private const val KEY_BACKGROUND_RUNNING = "key_background_running"
    private const val KEY_AUTO_CHECK_UPDATES = "key_auto_check_updates"
    private const val KEY_LAST_UPDATE_CHECK_TIME = "key_last_update_check_time"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSmartCaptionsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SMART_CAPTIONS, true)
    }

    fun setSmartCaptionsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMART_CAPTIONS, enabled).apply()
    }

    fun isFirstCommentHashtagsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FIRST_COMMENT_HASHTAGS, true)
    }

    fun setFirstCommentHashtagsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FIRST_COMMENT_HASHTAGS, enabled).apply()
    }

    fun isCreditAttributionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CREDIT_ATTRIBUTION, true)
    }

    fun setCreditAttributionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CREDIT_ATTRIBUTION, enabled).apply()
    }

    fun isBiometricLockEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BIOMETRIC_LOCK, false)
    }

    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BIOMETRIC_LOCK, enabled).apply()
    }

    fun isBackgroundRunningEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BACKGROUND_RUNNING, true)
    }

    fun setBackgroundRunningEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BACKGROUND_RUNNING, enabled).apply()
    }

    fun isAutoCheckUpdatesEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CHECK_UPDATES, true)
    }

    fun setAutoCheckUpdatesEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CHECK_UPDATES, enabled).apply()
    }

    fun getLastUpdateCheckTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)
    }

    fun setLastUpdateCheckTime(context: Context, timeMs: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK_TIME, timeMs).apply()
    }
}
