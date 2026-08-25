package com.pocketguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (PocketDetectService.isEnabled(context)) {
                PocketDetectService.start(context)
            }
        }
    }
}
