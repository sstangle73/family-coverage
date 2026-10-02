package com.storiedev.familycoverage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.provider.Settings

/** What the full build (F-Droid, GitHub) does its own way: automatic test texts and the battery exemption request. */
object Flavor {
    const val TEXTS_AUTOMATIC = true

    val smsPermissions = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)

    fun texts(
        context: Context, handler: Handler, prefs: Prefs, sampler: TelephonySampler, tracker: LocationTracker,
        store: CsvStore, wifi: () -> Boolean, inCall: () -> Boolean,
    ): TextTests = AutoTexts(context, handler, prefs, sampler, tracker, store, wifi, inCall)

    fun textsUi(activity: Activity, prefs: Prefs): TextsUi = AutoTextsUi(activity, prefs)

    /** Asks Android to exempt the app from battery optimisation, so the 10 s pace holds with the screen off. */
    fun batteryIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** The SMS receivers follow the settings: off unless logging runs with test texts on. */
    fun applyReceivers(context: Context, prefs: Prefs, running: Boolean) = TextBus.applyReceivers(context, prefs, running)
}
