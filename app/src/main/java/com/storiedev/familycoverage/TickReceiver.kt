package com.storiedev.familycoverage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Hands the still-mode alarm to the running logger (same process). With no logger running, nothing happens. */
object TickBus {
    @Volatile var sink: (() -> Unit)? = null

    fun fire() {
        sink?.invoke()
    }
}

/** The still-mode alarm: wakes the phone about every 2 minutes so the logger can tick, then lets it sleep again. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TickBus.fire()
    }
}
