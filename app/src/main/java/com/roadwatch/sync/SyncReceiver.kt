package com.roadwatch.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val i = Intent(context, SyncService::class.java)
        context.startService(i)
    }
}

