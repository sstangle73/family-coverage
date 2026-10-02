package com.storiedev.familycoverage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Shows the household's setup code as a QR code for the next phone to scan, or shares it as text. */
class ShareActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var image: ImageView
    private var includePlaces = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val household = prefs.household
        if (household == null) {
            finish()
            return
        }
        val pad = Ui.dp(this, 16)
        val root = Ui.vertical(this).apply { setPadding(pad, pad, pad, pad) }
        root.addView(Ui.text(this, "Add a phone to ${household.name}", 22f, bold = true))
        root.addView(
            Ui.text(
                this,
                "On the other phone, install Family Coverage, choose \"Join a household\" and scan this code. The " +
                    "code holds the household's name, members, end date, server and (if ticked) places. No phone " +
                    "numbers, and none of the recorded data.",
            ),
        )
        image = ImageView(this).apply { adjustViewBounds = true }
        root.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(Ui.checkbox(this, "Include the places (${household.places.size})", includePlaces) {
            includePlaces = it
            render()
        })
        root.addView(Ui.button(this, "Share the code as text") {
            val code = Household.encode(prefs.household ?: return@button, includePlaces)
            val text = "Join our household \"${household.name}\" in Family Coverage: open the app, choose \"Join a " +
                "household\", then \"Paste a setup code\", and paste this message.\n\n$code"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the setup code"))
        })
        root.addView(Ui.button(this, "Copy the code") {
            val code = Household.encode(prefs.household ?: return@button, includePlaces)
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Family Coverage setup code", code))
            Ui.toast(this, "Copied")
        })
        root.addView(
            Ui.text(
                this,
                "Changed the household (a new member, place or end date)? Share it again, and on each phone choose " +
                    "\"Scan an updated code\". Each phone keeps who it belongs to.",
                13f,
                top = 8,
            ),
        )
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        val h = prefs.household ?: return
        val size = minOf(resources.displayMetrics.widthPixels, 1080)
        image.setImageBitmap(Qr.bitmap(Household.encode(h, includePlaces), size))
    }
}
