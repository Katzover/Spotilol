package com.project.lol.util

import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object CrashHandler {

    private const val ACTIVITY = "com.project.lol.ui.CrashActivity"
    private const val EXTRA_SUMMARY = "crash_summary"

    private val handling = AtomicBoolean(false)

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            if (handling.compareAndSet(false, true)) {
                val summary = summary(throwable)
                val report = runCatching {
                    CrashReport.build(app, thread, throwable, Logger.snapshot())
                }.getOrNull()
                if (report != null) CrashStore.save(app, summary, report)
                runCatching {
                    val intent = Intent()
                        .setClassName(app, ACTIVITY)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        )
                        .putExtra(EXTRA_SUMMARY, summary)
                    app.startActivity(intent)
                }
            }
            if (previous != null) {
                runCatching { previous.uncaughtException(thread, throwable) }
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    fun summary(throwable: Throwable): String =
        "${throwable.javaClass.simpleName}: ${throwable.message?.take(240) ?: "no message"}"

    fun extraSummary(): String = EXTRA_SUMMARY
}
