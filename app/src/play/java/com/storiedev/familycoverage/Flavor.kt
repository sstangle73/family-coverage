package com.storiedev.familycoverage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.provider.Settings

/**
 * What the Google Play build does its own way. Play lets only default texting apps (and nine listed kinds of app)
 * use SMS permissions, so here a test text is a prompt that opens the phone's Messages app with the test filled in.
 * Play also limits the direct battery-exemption request, so the owner sets "Unrestricted" in Android's list.
 */
object Flavor {
    const val TEXTS_AUTOMATIC = false

    val smsPermissions = emptyArray<String>()

    fun texts(
        context: Context, handler: Handler, prefs: Prefs, sampler: TelephonySampler, tracker: LocationTracker,
        store: CsvStore, wifi: () -> Boolean, inCall: () -> Boolean,
    ): TextTests = PromptTexts(context, prefs, sampler, tracker, store, wifi, inCall)

    fun textsUi(activity: Activity, prefs: Prefs): TextsUi = PromptTextsUi(activity, prefs)

    fun batteryIntent(context: Context): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    @Suppress("UNUSED_PARAMETER")
    fun applyReceivers(context: Context, prefs: Prefs, running: Boolean) {
        // No SMS receivers in this build.
    }
}
