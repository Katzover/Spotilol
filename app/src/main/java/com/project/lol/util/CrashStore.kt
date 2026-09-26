package com.project.lol.util

import android.content.Context
import java.io.File

object CrashStore {

    private const val DIR = "crash"
    private const val REPORT = "last_crash.txt"
    private const val SUMMARY = "last_crash_summary.txt"

    fun save(context: Context, summary: String, report: String) {
        runCatching { file(context, REPORT).writeText(report) }
        runCatching { file(context, SUMMARY).writeText(summary) }
    }

    fun report(context: Context): String? =
        runCatching { file(context, REPORT).takeIf { it.exists() }?.readText() }.getOrNull()

    fun summary(context: Context): String? =
        runCatching { file(context, SUMMARY).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context, REPORT).delete() }
        runCatching { file(context, SUMMARY).delete() }
    }

    private fun file(context: Context, name: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, name)
    }
}
