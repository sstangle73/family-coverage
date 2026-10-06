package com.storiedev.familycoverage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts logging after a reboot or an app update, if it was on. A location foreground service may start from
 * BOOT_COMPLETED, but it only gets location with "Allow all the time". The watchdog's check does the restart, so a
 * permission that's off or a refused start shows its notification instead of failing silently.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        val household = prefs.household ?: return
        if (prefs.loggingEnabled && prefs.member != null && prefs.consentAt != null && !Config.ended(household.end)) {
            // Here as well as at Start: an update from a version without the watchdog starts it.
            Watchdog.schedule(context)
        }
        Watchdog.check(context)
    }
}
