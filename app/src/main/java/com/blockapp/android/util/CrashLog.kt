package com.blockapp.android.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Keeps the last crash on the phone, so "the app just crashed" can be turned into a stack trace
 * without adb. There is no crash-reporting library and no network (CLAUDE.md invariant 8), and
 * the first real-phone report of a crash came with nothing else to go on. SettingsScreen shows
 * the saved trace with a copy button until it's dismissed.
 *
 * [install] chains in front of the existing default handler and always hands the crash on, so
 * the process still dies exactly as it would have. This records and never swallows.
 *
 * [recordNonFatal] is for exceptions caught on background paths that the app survives on purpose
 * (BlockApplication's coroutine handler). Those are logged the same way, marked non-fatal, so a
 * failure that is quietly degrading something still shows up somewhere the user can see.
 *
 * Lives in filesDir, which Auto Backup doesn't include (see backup_rules.xml), so a crash on one
 * install doesn't come back on the next.
 */
object CrashLog {

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Writing must never be the thing that stops the crash reaching the system handler.
            runCatching { write(app, error, fatal = true, where = thread.name) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun recordNonFatal(context: Context, where: String, error: Throwable) {
        Log.w(TAG, "non-fatal in $where", error)
        runCatching { write(context.applicationContext, error, fatal = false, where = where) }
    }

    /** The saved report, or null when there is none. */
    fun last(context: Context): String? =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(context: Context, error: Throwable, fatal: Boolean, where: String) {
        // A non-fatal never overwrites a crash: the crash is the one the user came to report.
        if (!fatal && last(context)?.startsWith(FATAL_MARK) == true) return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val report = buildString {
            appendLine(if (fatal) FATAL_MARK else "Non-fatal error")
            appendLine("When: ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            appendLine("Where: $where")
            appendLine("App: $version")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(trace.take(MAX_TRACE_CHARS))
        }
        file(context).writeText(report)
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last_crash.txt"
    private const val FATAL_MARK = "Crash"

    /** A trace this long is already past every frame that matters; keeps the copy short. */
    private const val MAX_TRACE_CHARS = 12_000
}
