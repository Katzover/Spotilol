package com.project.lol

import android.app.Application
import com.project.lol.util.CrashHandler
import com.project.lol.util.Logger

class SpotilolApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        CrashHandler.install(this)
        Logger.s("app", "started")
    }
}
