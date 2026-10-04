package com.storiedev.familycoverage

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Plain platform views, built in code (the app has no AndroidX). */
object Ui {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /**
     * Shows [content] as the screen, inside the status and navigation bars and above the keyboard. Android 15 and
     * later draw every app edge to edge, so a screen would otherwise start under the status bar and end under the
     * navigation bar. The keyboard shrinks the content, so a scrolling screen keeps the field being typed in visible.
     */
    fun show(a: Activity, content: View, background: Int? = null) {
        val frame = FrameLayout(a)
        background?.let { frame.setBackgroundColor(it) }
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        a.setContentView(frame)
    }

    fun text(c: Context, s: String, sizeSp: Float = 15f, bold: Boolean = false, top: Int = 0) = TextView(c).apply {
        text = s
        textSize = sizeSp
        // The theme's main text colour: a plain TextView's default is its paler secondary one.
        c.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use { a ->
            a.getColorStateList(0)?.let { setTextColor(it) }
        }
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(c, top), 0, dp(c, 4))
    }

    fun heading(c: Context, s: String) = text(c, s, 17f, bold = true, top = 16)

    fun button(c: Context, label: String, onClick: () -> Unit) = Button(c).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    fun checkbox(c: Context, label: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(c).apply {
        text = label
        isChecked = checked
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    fun pair(c: Context, a: View, b: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun vertical(c: Context) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    /** A text field that saves as it's typed. */
    fun field(c: Context, hint: String, value: String?, type: Int = InputType.TYPE_CLASS_TEXT, save: (String?) -> Unit) =
        EditText(c).apply {
            this.hint = hint
            inputType = type
            setText(value ?: "")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    save(s?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                }
            })
        }

    fun phoneField(c: Context, hint: String, value: String?, save: (String?) -> Unit) =
        field(c, hint, value, InputType.TYPE_CLASS_PHONE, save)

    fun openUrl(c: Context, url: String) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(c, "No browser to open $url", Toast.LENGTH_LONG).show()
        }
    }

    fun confirm(a: Activity, title: String, message: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(a).setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()
}
