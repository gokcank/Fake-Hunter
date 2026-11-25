package com.gokcank.fakehunter.core.settings

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    fun applyLocale(context: Context, langCode: String): Context {
        val targetLocale = when (langCode) {
            SettingsManager.LANG_TR -> Locale("tr", "TR")
            SettingsManager.LANG_EN -> Locale("en", "US")
            else -> Locale.getDefault()
        }

        Locale.setDefault(targetLocale)

        val res = context.resources
        val config = Configuration(res.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = targetLocale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            return context
        }
    }
}
