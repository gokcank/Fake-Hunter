package com.gokcank.fakehunter

import android.app.Application
import android.content.Context
import com.gokcank.fakehunter.core.settings.LocaleHelper
import com.gokcank.fakehunter.core.settings.SettingsManager

class FakeHunterApp : Application() {

    override fun attachBaseContext(base: Context) {
        val lang = SettingsManager.getLanguage(base)
        super.attachBaseContext(LocaleHelper.applyLocale(base, lang))
    }
}
