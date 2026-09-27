package com.project.lol

import android.app.Application
import android.content.Context
import com.project.lol.util.AppDefaults
import com.project.lol.util.CrashHandler
import com.project.lol.util.LocaleHelper
import com.project.lol.util.Logger
import com.project.lol.util.NetworkMonitor

class SpotilolApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        CrashHandler.install(this)
        AppDefaults.enforce(getSharedPreferences("spotilol_prefs", MODE_PRIVATE))
        NetworkMonitor.start(this)
        Logger.s("app", "started")
    }
}
