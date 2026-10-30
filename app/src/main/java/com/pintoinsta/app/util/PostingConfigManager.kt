package com.pintoinsta.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages preferences for Smart AI Captions, First Comment hashtags, and posting configuration.
 */
object PostingConfigManager {

    private const val PREFS_NAME = "crossai_posting_config"

    private const val KEY_SMART_CAPTIONS = "key_smart_captions"
    private const val KEY_FIRST_COMMENT_HASHTAGS = "key_first_comment_hashtags"
    private const val KEY_CREDIT_ATTRIBUTION = "key_credit_attribution"

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
}

