package com.storiedev.familycoverage

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Checks a household's server address when it's entered: is anything there, is it a Family Coverage server, and does
 * it take this household? A server answers `/api/hello` without a key. A server from before that answers only the
 * server test, and refuses it without a key (401), which is still a Family Coverage answer. Blocking: run it off the
 * main thread. It gives up after a few seconds.
 */
object ServerCheck {
    enum class Verdict(val ok: Boolean) {
        OK(true),
        OLDER_SERVER(true),
        OTHER_HOUSEHOLD(false),
        NOT_FAMILY_COVERAGE(false),
        REDIRECTS(false),
        PLAIN_HTTP(false),
        REFUSED(false),
        UNREACHABLE(false),
    }

    /** A verdict, and where the address redirects to when it does. */
    data class Result(val verdict: Verdict, val location: String? = null)

    fun run(server: String, householdId: String): Result {
        val url = try {
            URL(server)
        } catch (e: IOException) {
            return Result(Verdict.UNREACHABLE)
        }
        val addresses = try {
            InetAddress.getAllByName(url.host).toList()
        } catch (e: IOException) {
            return Result(Verdict.UNREACHABLE)
        }
        if (!UrlRules.allowed(url.protocol, addresses)) return Result(Verdict.PLAIN_HTTP)
        return try {
            val hello = get(server + "/api/hello?household=" + URLEncoder.encode(householdId, "UTF-8"))
            val ping = if (hello.code == 404) get("$server/api/test/ping").code else null
            classify(hello.code, hello.body, ping, hello.location)
        } catch (e: IOException) {
            Result(Verdict.UNREACHABLE)
        }
    }

    /** What the answers mean: [pingCode] is the server test's answer, asked only after the hello's 404. */
    fun classify(helloCode: Int, helloBody: String, pingCode: Int?, location: String? = null): Result = when {
        helloCode == 200 -> {
            val o = try {
                JSONObject(helloBody)
            } catch (e: Exception) {
                null
            }
            when {
                o == null || o.optString("app") != "family-coverage" -> Result(Verdict.NOT_FAMILY_COVERAGE)
                o.optString("household") in setOf("other", "locked") -> Result(Verdict.OTHER_HOUSEHOLD)
                else -> Result(Verdict.OK)
            }
        }
        helloCode in 300..399 -> Result(Verdict.REDIRECTS, location)
        helloCode == 403 -> Result(Verdict.REFUSED)
        helloCode == 404 && pingCode == 401 -> Result(Verdict.OLDER_SERVER)
        else -> Result(Verdict.NOT_FAMILY_COVERAGE)
    }

    fun describe(r: Result, householdId: String): String = when (r.verdict) {
        Verdict.OK -> "✓  Found it: a Family Coverage server that takes this household."
        Verdict.OLDER_SERVER -> "✓  Found it: a Family Coverage server (an older version)."
        Verdict.OTHER_HOUSEHOLD ->
            "✗  That's a Family Coverage server, but it's set up for another household. Whoever runs it can set its " +
                "FC_HOUSEHOLD to this household's id, $householdId."
        Verdict.NOT_FAMILY_COVERAGE ->
            "✗  Something answered at that address, but it isn't a Family Coverage server, so the phones couldn't copy " +
                "their data there. Check the address, or leave it blank to keep the data on the phones."
        Verdict.REDIRECTS ->
            "✗  That address sends the app somewhere else" + (r.location?.let { " ($it)" } ?: "") +
                ". Enter the address it goes to instead."
        Verdict.PLAIN_HTTP ->
            "✗  Plain http:// only goes to an address on your home network or a private VPN. Use https:// for " +
                "anything else."
        Verdict.REFUSED ->
            "▲  The server answered, but it doesn't serve this network (its FC_API_ALLOW). It may work from home or " +
                "over your VPN."
        Verdict.UNREACHABLE ->
            "▲  No answer from that address from here. If the server is only on your home network or a VPN, that's " +
                "expected away from it: the phones keep their data until they reach it."
    }

    private class Answer(val code: Int, val body: String, val location: String?)

    private fun get(url: String): Answer {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.instanceFollowRedirects = false // the uploader doesn't follow them either
            c.setRequestProperty("User-Agent", "FamilyCoverage/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(4096) } ?: ""
            return Answer(code, text, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }
}
