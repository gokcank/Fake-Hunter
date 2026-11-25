package com.gokcank.fakehunter.core.settings

import android.content.Context
import android.content.SharedPreferences
import com.gokcank.fakehunter.core.model.AuditOptions

object SettingsManager {

    private const val PREFS_NAME = "fake_hunter_prefs"
    const val LANG_SYSTEM = "system"
    const val LANG_TR = "tr"
    const val LANG_EN = "en"

    private const val KEY_LANGUAGE = "key_language"
    private const val KEY_AUTO_ROOT = "key_auto_root"
    private const val KEY_STORAGE_TEST_MB = "key_storage_test_mb"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getLanguage(context: Context): String {
        return prefs(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, lang).apply()
    }

    fun isAutoRoot(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_ROOT, true)
    }

    fun setAutoRoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_ROOT, enabled).apply()
    }

    fun getStorageTestMb(context: Context): Int {
        return prefs(context).getInt(KEY_STORAGE_TEST_MB, 100)
    }

    fun setStorageTestMb(context: Context, mb: Int) {
        prefs(context).edit().putInt(KEY_STORAGE_TEST_MB, mb).apply()
    }

    fun getAuditOptions(context: Context): AuditOptions {
        return AuditOptions(
            wakeTest = true,
            requestRoot = isAutoRoot(context),
            storageTestMb = getStorageTestMb(context)
        )
    }
}
