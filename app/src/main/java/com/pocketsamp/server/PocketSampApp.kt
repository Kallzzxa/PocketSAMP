package com.pocketsamp.server

import android.app.Application
import com.pocketsamp.server.crash.CrashHandler

class PocketSampApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
