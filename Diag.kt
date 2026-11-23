package com.powerlock.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash + startup diagnostics, on-device only (no network, no analytics,
 * nothing leaves the phone unless the user copies the log themselves):
 * - a global uncaught-exception handler records the last crash (time,
 *   thread, exception, full stack trace) and chains to the previous
 *   handler, so system behaviour is unchanged;
 * - a breadcrumb trail records timestamped startup/lifecycle events, kept
 *   in memory and persisted on every event, so it survives a process death.
 * If the app closes by itself, the trail shows how far it got and the crash
 * record shows whether it died from an exception at all.
 *
 * Everything here is wrapped in try/catch (Throwable): diagnostics must
 * never crash the app they are watching.
 */
object Diag {
    private const val PREFS = "diag"
    private const val KEY_CRASH = "last_crash"
    private const val KEY_CRUMBS = "crumbs"
    private const val MAX_CRUMBS = 50
    private const val MAX_CRASH_LEN = 8000
    private const val MAX_CRUMB_LEN = 200

    /** Process start, approximated by the first touch of this object (App.onCreate). */
    private val t0 = SystemClock.uptimeMillis()

    private var appCtx: Context? = null
    private var installed = false
    private val lock = Any()
    private val crumbs = ArrayList<String>()

    /**
     * Install the crash handler (once) and remember the app context. Safe to
     * call from anywhere, any number of times. The installed flag is
     * essential: installing twice would chain the handler to itself and
     * recurse on the first crash.
     */
    fun init(ctx: Context) {
        try {
            val app = ctx.applicationContext
            synchronized(lock) {
                if (appCtx == null) appCtx = app
                if (installed) return
                installed = true
            }
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, e ->
                try {
                    recordCrash(thread, e)
                } catch (_: Throwable) {
                }
                try {
                    if (prev != null) {
                        prev.uncaughtException(thread, e)
                    } else {
                        // no previous handler (never happens on Android — the
                        // runtime always installs one): end the process the
                        // same way the system would, so a crash never leaves
                        // a zombie process behind
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                } catch (_: Throwable) {
                    try {
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Record one breadcrumb: "[+1234ms] message". Kept in memory (last 50) and persisted on every event. */
    fun crumb(msg: String) {
        try {
            val line = "[+" + (SystemClock.uptimeMillis() - t0) + "ms] " + msg.take(MAX_CRUMB_LEN)
            synchronized(lock) {
                crumbs.add(line)
                while (crumbs.size > MAX_CRUMBS) crumbs.removeAt(0)
            }
            persistCrumbs()
        } catch (_: Throwable) {
        }
    }

    /** Synchronous persist (commit, not apply): the process may die at any moment, and apply's queued write might not make it. */
    private fun persistCrumbs() {
        try {
            val c = appCtx ?: return
            val text = synchronized(lock) { crumbs.joinToString("\n") }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CRUMBS, text).commit()
        } catch (_: Throwable) {
        }
    }

    /** Record a crash synchronously (commit — the process is about to die). */
    fun recordCrash(thread: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val whenStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "time: $whenStr\nthread: ${thread.name}\n${e.javaClass.name}: ${e.message}\n$sw"
            val c = appCtx
            if (c != null) {
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CRASH, text.take(MAX_CRASH_LEN)).commit()
            }
            Log.e(TAG, "crash recorded: ${e.javaClass.simpleName}: ${e.message}")
            persistCrumbs()
        } catch (_: Throwable) {
        }
    }

    fun lastCrash(ctx: Context): String? {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CRASH, null)
        } catch (_: Throwable) {
            null
        }
    }

    fun crumbsText(ctx: Context): String {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CRUMBS, null) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** Full report for copy/paste: build, device, last crash, breadcrumb trail. */
    fun fullReport(ctx: Context): String {
        return try {
            val sb = StringBuilder()
            sb.append("PowerLock 2 diagnostics\n")
            sb.append("build: ${Extra.BUILD_ID}  version: $APP_VERSION\n")
            sb.append("android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})  device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            val crash = lastCrash(ctx)
            sb.append("\n--- last crash ---\n")
            sb.append(crash ?: "No crash recorded")
            sb.append("\n\n--- startup trail ---\n")
            val c = crumbsText(ctx)
            sb.append(if (c.isEmpty()) "No events recorded" else c)
            sb.append("\n")
            sb.toString()
        } catch (_: Throwable) {
            "Diagnostics unavailable"
        }
    }

    fun clear(ctx: Context) {
        try {
            synchronized(lock) { crumbs.clear() }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_CRASH).remove(KEY_CRUMBS).commit()
        } catch (_: Throwable) {
        }
    }
}
