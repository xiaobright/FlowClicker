package com.flowclicker.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flowclicker.app.ai.WakeDispatcher

class StopAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WakeDispatcher.stopAll()
        context.stopService(Intent(context, RecordingService::class.java))
    }
}
