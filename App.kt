package com.powerlock.app

import android.app.Application

/** Application entry point: installs the crash handler as early as possible, before any activity or service runs. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Diag.crumb("App.onCreate")
    }
}
