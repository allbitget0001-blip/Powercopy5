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
 * - the previous run's crash + trail are snapshotted at init, before the
 *   new run's first crumb overwrites the stored trail (build 71): the
 *   snapshot is auto-copied to the clipboard on launch and shown on the
 *   Home screen, so the log is reachable even when the app dies seconds
 *   after opening and Settings can never be reached in time.
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

    // ---- previous-run snapshot (build 71) ----
    // The first crumb of a new run overwrites the stored trail, so the
    // previous run's crash + trail are snapshotted at init() — before any
    // crumb of this run can persist — and held in memory for the launch
    // auto-copy (MainActivity) and the Home crash line.
    private var prevReport: String? = null
    private var prevCrash: String? = null
    private var prevTrail: String = ""
    private var prevTaken = false

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
                // snapshot the previous run BEFORE any crumb of this run
                // persists: the first crumb() overwrites the stored trail
                snapshotPrevious(app)
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
            buildReport(lastCrash(ctx), crumbsText(ctx))
        } catch (_: Throwable) {
            "Diagnostics unavailable"
        }
    }

    private fun buildReport(crash: String?, crumbs: String): String {
        val sb = StringBuilder()
        sb.append("PowerLock 2 diagnostics\n")
        sb.append("build: ${Extra.BUILD_ID}  version: $APP_VERSION\n")
        sb.append("android: ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})  device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("\n--- last crash ---\n")
        sb.append(crash ?: "No crash recorded")
        sb.append("\n\n--- startup trail ---\n")
        sb.append(if (crumbs.isEmpty()) "No events recorded" else crumbs)
        sb.append("\n")
        return sb.toString()
    }

    /**
     * Snapshot the previous run's stored crash + trail. Only meaningful
     * data is kept: a stored crash, or a non-empty stored trail. Called
     * once, from init(), before this run's first crumb can persist — and
     * never allowed to throw, so a snapshot failure can never block the
     * crash-handler install that follows it in init().
     */
    private fun snapshotPrevious(ctx: Context) {
        try {
            val crash = lastCrash(ctx)
            val trail = crumbsText(ctx)
            if (crash == null && trail.isEmpty()) return
            prevCrash = crash
            prevTrail = trail
            prevReport = buildReport(crash, trail)
        } catch (_: Throwable) {
        }
    }

    /**
     * Take the previous run's report for the launch auto-copy: returns it
     * once per process, then null — so an activity recreation (rotation)
     * never copies or toasts twice. Null when the previous run left
     * nothing (first launch) or the report was already taken.
     */
    fun takePreviousReport(): String? {
        return try {
            synchronized(lock) {
                if (prevTaken) return null
                val r = prevReport ?: return null
                prevTaken = true
                r
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Previous run's crash exception line ("ExceptionClass: message"), or null when it did not crash. */
    fun previousCrashLine(): String? {
        return try {
            // crash line 3 is "ExceptionClass: message" (lines 1-2 are time and thread)
            prevCrash?.lines()?.getOrNull(2)?.take(MAX_CRUMB_LEN)
        } catch (_: Throwable) {
            null
        }
    }

    /** Previous run's startup-trail event count (0 when it left no trail). */
    fun previousTrailCount(): Int {
        return try {
            if (prevTrail.isEmpty()) 0 else prevTrail.lines().size
        } catch (_: Throwable) {
            0
        }
    }

    fun clear(ctx: Context) {
        try {
            synchronized(lock) {
                crumbs.clear()
                prevReport = null
                prevCrash = null
                prevTrail = ""
                prevTaken = false
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_CRASH).remove(KEY_CRUMBS).commit()
        } catch (_: Throwable) {
        }
    }
}
