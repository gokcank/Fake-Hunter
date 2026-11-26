package com.gokcank.fakehunter.core.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.gokcank.fakehunter.core.safeRun
import java.util.Locale

object LocaleHelper {

    /** Captured once before any in-app locale override mutates Locale.getDefault(). */
    private val initialSystemLocale: Locale = Locale.getDefault()

    fun resolveTargetLocale(langCode: String, systemLocaleProvider: () -> Locale = ::getDeviceSystemLocale): Locale {
        return when (langCode) {
            SettingsManager.LANG_TR -> Locale("tr", "TR")
            SettingsManager.LANG_EN -> Locale("en", "US")
            else -> systemLocaleProvider()
        }
    }

    fun getDeviceSystemLocale(): Locale {
        return safeRun("SystemLocale", initialSystemLocale) {
            val sysConfig = Resources.getSystem().configuration
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!sysConfig.locales.isEmpty) sysConfig.locales.get(0) else null
            } else {
                @Suppress("DEPRECATION")
                sysConfig.locale
            }
            resolved ?: initialSystemLocale
        }
    }

    fun applyLocale(context: Context, langCode: String): Context {
        val targetLocale = resolveTargetLocale(langCode)
        Locale.setDefault(targetLocale)

        val res = context.resources
        val config = Configuration(res.configuration)

        // Keep applicationContext resources aligned too so any app-level lookup matches
        safeRun("AppCtxLocale", Unit) {
            val appCtx = context.applicationContext
            if (appCtx != null && appCtx !== context) {
                val appRes = appCtx.resources
                val appCfg = Configuration(appRes.configuration)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appCfg.setLocales(LocaleList(targetLocale))
                } else {
                    @Suppress("DEPRECATION")
                    appCfg.locale = targetLocale
                }
                @Suppress("DEPRECATION")
                appRes.updateConfiguration(appCfg, appRes.displayMetrics)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(targetLocale))
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = targetLocale
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            context
        }
    }
}
