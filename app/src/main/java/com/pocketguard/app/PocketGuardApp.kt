package com.pocketguard.app

import android.app.Application

class PocketGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: PocketGuardApp
            private set
    }
}
