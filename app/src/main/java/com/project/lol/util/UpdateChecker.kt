package com.project.lol.util

import android.content.Context

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "update"
        private const val OWNER = "Katzover"
        private const val REPO = "Spotilol"
        private const val PREFS_NAME = "spotilol_prefs"
        private const val KEY_LAST_CHECK = "LastUpdateCheck"
        const val KEY_INSTALL_CHECKED = "InstallSourceChecked"
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }

    /**
     * Fetches the latest release and reports the result on the main thread.
     * [onResult] fires on every successful fetch: [url] is non-null only when
     * an update is available. No callback on fetch failure (the first-install
     * check then simply retries on a later launch — the hourly throttle is
     * bypassed while KEY_INSTALL_CHECKED is still missing).
     */
    fun autoCheck(onResult: (url: String?, latest: String, current: String) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val checkedBefore = prefs.contains(KEY_INSTALL_CHECKED)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        if (checkedBefore && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
            Logger.v(TAG, "update check throttled (last ${(System.currentTimeMillis() - lastCheck) / 1000}s ago)")
            return
        }

        Logger.i(TAG, "checking for updates ($OWNER/$REPO)")
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        GitHubApi.fetchLatestRelease(OWNER, REPO) { release ->
            val tag = release?.tagName?.removePrefix("v")
            if (tag == null) {
                Logger.w(TAG, "update check failed or no release found")
                return@fetchLatestRelease
            }
            val current = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }.getOrElse { "" }

            val newer = isNewer(tag, current)
            Logger.i(TAG, "latest=$tag current=$current newer=$newer")
            val url = if (newer) {
                release.apkUrl.ifBlank {
                    release.htmlUrl.ifBlank {
                        "https://github.com/$OWNER/$REPO/releases/latest"
                    }
                }
            } else {
                null
            }
            if (url != null) Logger.s(TAG, "update available: $url")
            onResult(url, tag, current)
        }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".")
        val currentParts = current.split(".")
        val size = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until size) {
            val l = latestParts.getOrNull(i)?.toIntOrNull() ?: 0
            val c = currentParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
