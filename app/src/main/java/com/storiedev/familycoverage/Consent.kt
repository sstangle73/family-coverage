package com.storiedev.familycoverage

import java.net.URI
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What each person agrees to before their phone records: built from the household's own settings, so it names the
 * real end date and the real server (or says there is none). The text is the app's promise; keep it exact.
 */
object Consent {
    fun text(h: Household, automaticTexts: Boolean, speedTests: Boolean, dataChecks: Boolean): String {
        val end = h.end.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US))
        val host = h.server?.let { runCatching { URI(it).host }.getOrNull() ?: it }
        val tests = buildList {
            if (dataChecks) add("a tiny check that mobile data works (every 2 to 10 minutes, off Wi-Fi)")
            if (speedTests) add("a small speed test over cellular against Cloudflare's speed test (every 30 to 60 minutes)")
            if (host != null) add("a test that reaches your household's server, $host (every 1 to 3 hours, off Wi-Fi)")
        }
        return buildString {
            append("Family Coverage helps ${h.name} compare mobile networks. Until $end, when it stops by itself, ")
            append("this phone records, for each SIM: the network, 4G or 5G, signal strength and service, every 10 ")
            append("seconds while you move and about every 2 minutes while you're still; a precise GPS track while ")
            append("you move; and how much mobile data the phone uses (totals, not which apps).")
            if (tests.isNotEmpty()) {
                append(" It also runs ${tests.joinToString("; ")}: together about 1 to 2 GB of data a month.")
            }
            append(" It pauses all of that during calls. Call and text tests are buttons you tap. ")
            append(
                if (automaticTexts) {
                    "If you turn on test texts, it texts one other household phone at your places (at most 8 times a " +
                        "day, never 9 pm to 7 am) and answers that phone's tests."
                } else {
                    "If you turn on test texts, it reminds you at your places to send one (you press send)."
                },
            )
            append("\n\n")
            append(
                if (host == null) {
                    "The data stays on this phone. It leaves only when you export it, to wherever you choose."
                } else {
                    "The data stays on this phone and is copied to $host, a server your household runs."
                },
            )
            append(" Nothing goes to StorieDev, the app's maker, or anyone else.\n\n")
            append("It never reads your calls, texts (apart from its own test texts), contacts, Wi-Fi names or ")
            append("browsing. Stop any time with the notification's Stop button, or uninstall the app.")
        }
    }
}
