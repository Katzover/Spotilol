package com.project.lol.yt

import com.project.lol.util.Logger

/** Minimal stand-in for Meld's reportException (no crash-reporting backend here). */
fun reportException(throwable: Throwable) {
    Logger.e("Spl-DL", "Exception", throwable)
}
