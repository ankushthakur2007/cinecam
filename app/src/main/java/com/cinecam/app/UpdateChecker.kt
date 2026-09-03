package com.cinecam.app

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Professional-style update check against GitHub stable releases.
 *
 * Queries `releases/latest` (which ignores CI prereleases), compares the tag
 * against [BuildConfig.VERSION_NAME], and shows a dialog that opens the
 * release page in the browser. Fully best-effort: offline or API failure is
 * silent, and nothing here touches capture/recording — it runs once per
 * launch on a background thread.
 */
object UpdateChecker {
    private const val TAG = "CineCamUpdate"
    private const val API = "https://api.github.com/repos/ankushthakur2007/cinecam/releases/latest"
    private val io = Executors.newSingleThreadExecutor()

    fun check(activity: Activity) {
        io.execute {
            try {
                val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "CineCam-App")
                }
                if (conn.responseCode != 200) {
                    Log.d(TAG, "no release info: http=${conn.responseCode}")
                    return@execute
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                val htmlUrl = json.optString("html_url", "")
                val current = try {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
                } catch (_: Throwable) {
                    "0.0.0"
                }
                if (tag.isEmpty() || htmlUrl.isEmpty() || !isNewer(tag, current)) return@execute
                if (activity.isFinishing || activity.isDestroyed) return@execute
                activity.runOnUiThread { showDialog(activity, tag, htmlUrl) }
                Log.d(TAG, "update available: $tag (current $current)")
            } catch (t: Throwable) {
                Log.d(TAG, "update check failed (offline?): ${t.message}")
            }
        }
    }

    private fun showDialog(activity: Activity, tag: String, htmlUrl: String) {
        try {
            AlertDialog.Builder(activity)
                .setTitle("CineCam update available")
                .setMessage("Version $tag is on GitHub. Download the APK from the release page to update.")
                .setPositiveButton("View release") { _, _ ->
                    runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, htmlUrl.toUri()))
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "update dialog failed: ${t.message}")
        }
    }

    /** True when [tag] (e.g. "v0.2.0") is strictly newer than [current]. */
    fun isNewer(tag: String, current: String): Boolean {
        val t = numericParts(tag)
        if (t.isEmpty()) return false
        val c = numericParts(current)
        val n = maxOf(t.size, c.size)
        for (i in 0 until n) {
            val diff = (t.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun numericParts(s: String): List<Int> =
        s.trim().trimStart('v', 'V').split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
}
