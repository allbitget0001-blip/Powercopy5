package com.powerlock.app

import android.view.accessibility.AccessibilityWindowInfo
import android.os.Build
import android.content.IntentFilter
import android.content.Context
import android.content.BroadcastReceiver
import android.widget.TextView
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Power menu protection engine (event driven, no polling).
 *
 * While a session is active:
 *  1. the power menu is recognised from the window event itself (class / text), with no extra IPC,
 *  2. if needed, the window content is scanned (throttled, only for system UI),
 *  3. Back is pressed immediately, then Back again and Home shortly after, so the user ends on the Home screen,
 *  4. a short "PowerLock active" shield blocks touches on the slider while the menu is closing.
 * The protection state is cached from SharedPreferences, so events do not read storage.
 * Settings / uninstall pages that show "PowerLock" are sent Home when "protect app settings" is on.
 * App block (Protection section) also runs here while a session is active: fully
 * blocked apps are covered instantly by a protection screen (with a Home button),
 * Reels-blocked apps get Back (repeated while the feed is still showing)
 * when a Reels / Shorts screen is detected, and apps with search block get
 * Back (with the typed query cleared) as soon as the user starts typing in
 * search — the keyboard still appears, and searching inside a chat or
 * channel is not blocked.
 * Home is never used for Reels: it sends YouTube Shorts into picture-in-picture
 * instead of closing it.
 */
class PowerBlockService : AccessibilityService() {

    companion object {
        /** Diagnostic log: what the service sees on reels screens. Read by the
         * Protection screen's Diagnostic card. Capped at 120 lines. */
        val debugLog = mutableListOf<String>()

        /** Plain-language summary of the same scans (2 lines per scan), shown
         * in the Diagnostic card so it reads simply. Capped at 60 lines. */
        val debugSummary = mutableListOf<String>()

        /** True while the Diagnostic card is on screen: the 3 s diagnostic
         * scan only runs then, so it never walks the tree unseen. Set by the
         * card itself and cleared when the app leaves the Protection tab. */
        @Volatile
        var diagOpen = false

    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastAction = 0L
    private var lastMenuScan = 0L
    private var lastGuardScan = 0L

    @Volatile
    private var provisionalCheckRunning = false // a location check is running for the power menu

    @Volatile
    private var destroyed = false // set in cleanup(): a stale location callback must not act after it

    private var overlay: View? = null
    private var overlaySub: TextView? = null
    private var overlayAttached = false

    @Volatile
    private var locProtecting = false // location lock: blocked while at a saved place, no session

    @Volatile
    private var guardOn = true

    @Volatile
    private var focusOn = false

    @Volatile
    private var focusApps: Set<String> = emptySet()

    private var lastFocusAction = 0L

    // App block (Protection section): full block + reels block + search block.
    @Volatile
    private var abOn = true

    @Volatile
    private var abFull: Set<String> = emptySet()

    @Volatile
    private var abReels: Set<String> = emptySet()

    @Volatile
    private var abMsg: Set<String> = emptySet()

    // Daily limits: foreground time per app, counted without any lock running.
    @Volatile
    private var limitMap: Map<String, Int> = emptyMap()
    private var usagePkg: String? = null
    private var usageSince = 0L
    private var usageDay = ""
    private val usageToday = HashMap<String, Long>()
    // Usage history: the session currently being timed (wall clock for display,
    // uptime for the duration so a wall-clock change can't stretch it).
    private var histPkg: String? = null
    private var histOpenWall = 0L
    private var histOpenUptime = 0L
    // Last closed session, for the 30 s merge rule (a quick re-open resumes it).
    private var lastClosedPkg: String? = null
    private var lastClosedUptime = 0L
    private var lastClosedOpenWall = 0L
    private var lastClosedOpenUptime = 0L
    // Usage history cache: the list is parsed once and kept in memory, so
    // closing a session no longer re-parses up to 1000 entries on every app
    // switch. The stored string's signature catches external changes.
    private var histCache: MutableList<UsageSession>? = null
    private var histCacheSig = ""
    private var lastMidnightCheck = 0L // throttles the midnight split check (once per 60 s)
    private var imePkg: String? = null
    private var imeCheckedAt = 0L
    private var launcherPkg: String? = null
    private var launcherResolved = false
    private var limitBlockActive = false // the overlay is up for a daily limit (no lock needed)
    private var blockMsg = "" // the user's own message on the block screen


    private var lastAppBlockAction = 0L
    private var lastReelsScan = 0L
    private var lastDebugScan = 0L
    private var lastReelsBack = 0L // cooldown after a reels Back (the close animation)
    private var lastMsgScan = 0L
    private var lastMsgAction = 0L

    // Full-block protection screen: covers a blocked app the instant it opens.
    private var blockOverlay: View? = null
    private var blockOverlayTitle: TextView? = null
    private var blockOverlaySub: TextView? = null
    private var blockOverlayMsg: TextView? = null
    private var blockOverlayIcon: IconView? = null
    private var blockOverlayAttached = false
    private var blockedPkg: String? = null
    private var lastBlockPkg: String? = null
    private var lastBlockAt = 0L
    private var lastBlockShowFailedAt = 0L // a failed overlay add retries at most once a second
    private var lastBlockHomeFallback = 0L // the HOME fallback in showBlockOverlay is throttled (once per 3 s)
    private val appLabelCache = HashMap<String, String>()


    // Tab labels that mean a Reels / Shorts / Status feed is showing when selected.
    private val reelsTabMarkers = listOf(
        "Reels", "Shorts", "রিলস", "শর্টস", "Spotlight", "For You", "Following", "Updates"
    )
    private val reelsMarkersLower = listOf(
        "reels", "shorts", "রিলস", "শর্টস", "spotlight", "for you", "following", "updates"
    )

    private var lastKeepAliveCheck = 0L // throttles the revive check (once per 30s)

    // English + Bengali: the phone's system language decides the menu text.
    private val triggers = listOf(
        "Swipe to power off", "Swipe to restart", "Swipe for SOS",
        "Power off", "Restart",
        "পাওয়ার বন্ধ", "পাওয়ার অফ", "রিস্টার্ট", "বন্ধ করতে সোয়াইপ"
    )
    private val triggersLower = triggers.map { it.lowercase() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // the keep-alive heartbeat writes kas_heartbeat every 60 s: it is not
        // config, so it must not trigger a full state re-read (JSON parses)
        if (key == "kas_heartbeat") return@OnSharedPreferenceChangeListener
        readState()
        // the lock may just have ended: re-check on the main thread, since
        // taps on the overlay itself generate no accessibility event to
        // trigger the hide in onAccessibilityEvent
        handler.post {
            if (isBlockOverlayAttached() && !protecting() && !limitBlockActive) hideBlockOverlay()
        }
    }
    private val hideTask = Runnable { hideOverlay() }
    private val backAgain = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private val backAgain2 = Runnable { if (protecting()) performGlobalAction(GLOBAL_ACTION_BACK) }
    private var lastWinScan = 0L
    private var receiverOn = false
    private var screenReceiverOn = false


    // The system announces "globalactions" the instant the power button is long-pressed,
    // before the menu is even drawn - the earliest possible trigger.
    private val closeDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            try {
                if (i?.getStringExtra("reason") == "globalactions" && protecting()) block()
            } catch (ex: Exception) {
                Log.e(TAG, "close-dialogs receiver failed", ex)
            }
        }
    }

    // Screen off: stop the usage clock — screen-off time is not screen time.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) flushUsageOnScreenOff()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        destroyed = false
        try {
            L.load(this)
            readState()
            loadUsage()
            Store.lock(this).registerOnSharedPreferenceChangeListener(prefsListener)
            Store.settings(this).registerOnSharedPreferenceChangeListener(prefsListener)
            ensureOverlay() // pre-build the shield so it can appear instantly
            ensureBlockOverlay() // pre-build the block screen too: it must appear instantly
            registerCloseDialogs()
            registerScreenOff()
            Store.kickService(this)
            Scheduler.onAlarm(this)
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }


    @Suppress("DEPRECATION")
    private fun registerCloseDialogs() {
        try {
            val f = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(closeDialogsReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(closeDialogsReceiver, f)
            }
            receiverOn = true
        } catch (ex: Exception) {
            Log.e(TAG, "register close-dialogs failed", ex)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerScreenOff() {
        try {
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenOffReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, f)
            }
            screenReceiverOn = true
        } catch (ex: Exception) {
            Log.e(TAG, "register screen-off failed", ex)
        }
    }

    private fun readState() {
        val p = Store.lock(this)
        locProtecting = p.getBoolean("loc_protecting", false)
        // the guard follows the session while one runs (locked at its start); otherwise it
        // follows the live "App settings protection" switch in Settings.
        // isActive is monotonic: the wall clock cannot fake a session on or off.
        val sessionOn = Store.isActive(this)
        guardOn = if (sessionOn) p.getBoolean("s_guard", true)
        else Store.settings(this).getBoolean("guard", true)
        val st = Store.settings(this)
        focusOn = st.getBoolean("focus_on", false)
        focusApps = (st.getString("focus_apps", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()
        abOn = st.getBoolean("ab_on", true)
        abFull = Extra.appBlockFull(this)
        // a session started with a lock profile blocks its apps too (snapshot taken at
        // lock start — later profile edits do not change the running session)
        if (sessionOn) {
            val snap = p.getString("s_profile_apps", "") ?: ""
            if (snap.isNotEmpty()) abFull = abFull + snap.split(",").filter { it.isNotEmpty() }.toSet()
        }
        abReels = Extra.appBlockReels(this)
        abMsg = Extra.appBlockMsg(this)
        limitMap = Extra.dailyLimits(this)
        blockMsg = Extra.blockMessage(this)
        C.load(this) // the chosen accent colour also colours the block overlay
    }

    private fun protecting(): Boolean = Store.isActive(this) || locProtecting

    /**
     * The system re-binds this service after the process dies, so it is the one
     * watchdog the system itself brings back: if the keep-alive service has not
     * written a heartbeat for 90s while protection should be on, bring it back.
     */
    private fun maybeReviveKeepAlive() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastKeepAliveCheck < 30_000) return
            lastKeepAliveCheck = now
            if (!protecting()) return
            val hb = Store.lock(this).getLong("kas_heartbeat", 0)
            // fail-safe: a clock-backward jump makes the age negative, which
            // must not suppress revival of a dead keep-alive service
            val age = System.currentTimeMillis() - hb
            if (age in 0 until 90_000) return
            Store.kickService(this)
            KeepAliveService.restartSoon(this, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "revive keep-alive failed", e)
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        try {
            maybeReviveKeepAlive() // the system re-binds this service, so it revives the keep-alive service
            // midnight split (throttled): a slice is split within ~60 s of
            // midnight even without an app switch
            val nowU = SystemClock.uptimeMillis()
            if (nowU - lastMidnightCheck >= 60_000) {
                lastMidnightCheck = nowU
                checkMidnight(nowU)
            }
            // daily limits work without a lock: track foreground time on every app switch
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) trackUsage(pkg)
            // the block screen follows the foreground app: hide it once protection ends,
            // or once a real window change shows another app in front. Our own package
            // is excluded: taps on the overlay itself must not dismiss it. A daily-limit
            // block stays up without a lock — only leaving the app dismisses it.
            if (isBlockOverlayAttached() && !protecting() && !limitBlockActive) {
                hideBlockOverlay()
            } else if (isBlockOverlayAttached() && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.contains("systemui") && pkg != packageName && pkg != blockedPkg &&
                pkg != currentImePackage() && !pkg.contains("permissioncontroller") && pkg != "android"
            ) {
                hideBlockOverlay()
            }
            if (pkg.contains("systemui")) {
                // the power menu: even when the flag is not set yet, the location lock may apply
                onSystemUi(e)
            } else if (limitReached(pkg)) {
                onLimitBlockedApp(e)
            } else if (!protecting()) {
                return
            } else if (abOn && abFull.contains(pkg)) {
                onFullBlockedApp(e)
            } else if (abOn && (abReels.contains(pkg) || abMsg.contains(pkg))) {
                // reels and search blocks are independent: an app can have both
                if (abReels.contains(pkg)) onReelsApp(e)
                if (abMsg.contains(pkg)) onMsgSearchApp(e)
            } else if (focusOn && focusApps.contains(pkg)) {
                onFocusApp(e)
            } else if (guardOn && isGuarded(pkg)) {
                onGuarded(e)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "onAccessibilityEvent failed", ex)
        }
    }


    // ---------- detection ----------

    private fun onSystemUi(e: AccessibilityEvent) {
        // 1) instant check from the event itself (no IPC at all)
        val menu = if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            looksLikePowerMenu(e) || menuFromNode(e)
        } else {
            // bare trigger text ("Restart" in a notification) is not the
            // power menu: without a window-state change the text path
            // counts only with node confirmation, so the node scan decides
            menuFromNode(e)
        }
        if (!menu) return
        if (protecting()) {
            block()
            return
        }
        // location lock on but the flag has not caught up yet (updates are slow): shield now,
        // verify presence, then keep blocking or release the menu
        if (!Store.settings(this).getBoolean("loc_on", false)) return
        if (!Perm.locationOn(this)) return
        if (provisionalCheckRunning) {
            showOverlay() // keep the shield up while the check runs
            return
        }
        showOverlay()
        provisionalCheckRunning = true
        val checkStarted = SystemClock.uptimeMillis()
        PlaceWatcher.isAnyPlaceActiveNow(this) { active ->
            provisionalCheckRunning = false
            if (destroyed) return@isAnyPlaceActiveNow
            if (active == true) {
                Store.setLocProtecting(this, true)
                // stale answer: the power menu is long gone and the user is
                // in another app — never press Back there
                if (SystemClock.uptimeMillis() - checkStarted < 3000) block()
            } else if (!protecting()) {
                // a real block may have shown its own shield meanwhile:
                // only release when nothing is protecting any more
                hideOverlay()
            }
        }
    }

    /** Node / window scan for the power menu (throttled like before). */
    private fun menuFromNode(e: AccessibilityEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastMenuScan < 60) return false
            lastMenuScan = now
            if (hasPowerMenu(e.source)) return true
            if (now - lastWinScan < 150) return false
            lastWinScan = now
            return scanWindows(false)
        }
        return hasPowerMenu(e.source) || scanWindows(true)
    }

    private fun onGuarded(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastGuardScan < 120) return
            lastGuardScan = now
        }
        val root = rootInActiveWindow ?: return
        try {
            val nodes = root.findAccessibilityNodeInfosByText("PowerLock")
            try {
                if (nodes.isNotEmpty() && now - lastAction >= 300) {
                    lastAction = now
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            } finally {
                for (n in nodes) {
                    try { n.recycle() } catch (_: Exception) { }
                }
            }
        } finally {
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    private fun onFocusApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFocusAction < 300) return
        lastFocusAction = now
        Extra.logAttempt(this, "focus", e.packageName?.toString() ?: "")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Full app block: a protection screen covers the app the instant it opens.
     * No throttle on first sight of an app — the screen must appear before the
     * user can see or touch anything. The burst of events that follows a single
     * open is ignored while the screen is already up for that app.
     */
    private fun onFullBlockedApp(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        // the launcher, the keyboard and this app itself must never be
        // blocked: a daily limit on the launcher traps the user on the
        // home screen until midnight
        if (pkg == packageName || pkg == currentImePackage() || pkg == defaultLauncherPackage()) return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1000 && isBlockOverlayAttached()) return
        lastBlockPkg = pkg
        lastBlockAt = now
        limitBlockActive = false // a lock block, not a limit block
        showBlockOverlay(pkg) // screen first: it must cover the app before anything else runs
        Extra.logAttempt(this, "appblock", pkg)
    }

    // ---------- daily limits ----------

    /** Today's usage, loaded once at start; trackUsage keeps it fresh in memory. */
    private fun loadUsage() {
        try {
            usageDay = Store.dayKey(0)
            usageToday.clear()
            usageToday.putAll(Extra.usageTodayAll(this))
            histCached() // warm the history cache (one parse at startup, not per switch)
            flushRestartHistorySession()
            resumeLiveSlice()
        } catch (e: Exception) {
            Log.e(TAG, "loadUsage failed", e)
        }
    }

    /**
     * A previous service instance may have died mid-app: resume its persisted
     * live slice so the in-flight seconds aren't lost from the per-day totals
     * (the next app switch flushes the whole slice via trackUsage). Only
     * today's slice with a sane elapsed time counts — uptime resets on
     * reboot, so a negative or over-12 h elapsed means a stale slice, which
     * is cleared instead.
     */
    private fun resumeLiveSlice() {
        try {
            val (livePkg, liveSince, liveDay) = Extra.liveSlice(this)
            if (livePkg == null) return
            val elapsed = SystemClock.uptimeMillis() - liveSince
            if (liveDay == usageDay && elapsed in 0..(12L * 60 * 60 * 1000)) {
                usagePkg = livePkg
                usageSince = liveSince
            } else {
                Extra.setLiveSlice(this, null, 0L, "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "live slice resume failed", e)
        }
    }

    /** The current keyboard package (cached 60 s): typing must not count as leaving the app. */
    private fun currentImePackage(): String? {
        val now = SystemClock.uptimeMillis()
        if (now - imeCheckedAt < 60_000) return imePkg
        imeCheckedAt = now
        imePkg = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore("/")
        } catch (e: Exception) {
            null
        }
        return imePkg
    }

    /** The default launcher package (resolved once, cached): it must never be blocked. */
    private fun defaultLauncherPackage(): String? {
        if (launcherResolved) return launcherPkg
        launcherResolved = true
        launcherPkg = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        return launcherPkg
    }

    /**
     * Called on every window-state change: closes the previous app's time slice
     * (persisted for every app — the usage dashboard and the live screen-time
     * total need all of them, not just limited ones) and opens one for the new
     * app. System UI, the permission screen, our own package and the keyboard
     * never switch the slice. At midnight the counters reset.
     */
    private fun trackUsage(pkg: String) {
        try {
            if (pkg.contains("systemui") || pkg == packageName || pkg.contains("permissioncontroller")) return
            if (pkg == currentImePackage()) return
            val now = SystemClock.uptimeMillis()
            checkMidnight(now) // a slice spanning midnight splits there first
            val day = Store.dayKey(0)
            val prev = usagePkg
            if (prev != null && prev != pkg) {
                val secs = (now - usageSince) / 1000
                if (secs > 0) {
                    Extra.addUsage(this, prev, secs)
                    usageToday[prev] = (usageToday[prev] ?: 0L) + secs
                }
                closeHistorySession(now)
            }
            if (prev != pkg) {
                usagePkg = pkg
                usageSince = now
                Extra.setLiveSlice(this, pkg, now, day)
                openHistorySession(pkg, now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "trackUsage failed", e)
        }
    }

    /**
     * Midnight split: when the day changed since the slice started, the
     * in-flight slice is closed at midnight so the old day keeps its own
     * seconds (usage is attributed to the day it happened on, like the
     * Statistics totals). The history session splits too, so each day's log
     * stays within its day. Called from trackUsage and, throttled, from the
     * event stream — so a slice is split within ~60 s of midnight even
     * without an app switch.
     */
    private fun checkMidnight(nowUptime: Long) {
        try {
            val today = Store.dayKey(0)
            if (today == usageDay) return
            val nowWall = System.currentTimeMillis()
            val prev = usagePkg
            if (prev != null) {
                // the slice may span more than one midnight: credit each day
                var segWall = nowWall - (nowUptime - usageSince)
                var segUptime = usageSince
                while (Store.dayKeyFor(segWall) != today) {
                    val nextMidnight = Store.dayStart(segWall, 1)
                    val segSecs = ((nextMidnight - segWall) / 1000).coerceAtLeast(0)
                    if (segSecs > 0) Extra.addUsageForDay(this, prev, segSecs, Store.dayKeyFor(segWall))
                    val segEndUptime = segUptime + (nextMidnight - segWall)
                    closeHistorySession(segEndUptime)
                    // no 30 s merge across the split: it would undo it
                    lastClosedPkg = null
                    openHistorySession(prev, segEndUptime, nextMidnight)
                    segWall = nextMidnight
                    segUptime = segEndUptime
                }
                // the rest of the slice belongs to today: restart it at midnight
                usageSince = segUptime
                Extra.setLiveSlice(this, prev, segUptime, today)
            }
            usageDay = today
            usageToday.clear()
            usageToday.putAll(Extra.usageTodayAll(this))
            // the day rolled over: a limit block from yesterday is stale —
            // hide it instead of leaving "opens again tomorrow" up all day
            if (limitBlockActive) hideBlockOverlay()
        } catch (e: Exception) {
            Log.e(TAG, "midnight check failed", e)
        }
    }

    /**
     * Close the history session being timed (if any) and log it. The close
     * time is derived from the uptime delta so a wall-clock change mid-session
     * can't stretch or shrink the entry.
     */
    private fun closeHistorySession(nowUptime: Long) {
        val pkg = histPkg ?: return
        try {
            val closeWall = histOpenWall + (nowUptime - histOpenUptime)
            histAdd(pkg, histOpenWall, closeWall)
            lastClosedPkg = pkg
            lastClosedUptime = nowUptime
            lastClosedOpenWall = histOpenWall
            lastClosedOpenUptime = histOpenUptime
        } catch (e: Exception) {
            Log.e(TAG, "closeHistorySession failed", e)
        }
        histPkg = null
        Extra.setOpenHistory(this, null, 0L, 0L)
    }

    // ---------- usage history cache ----------

    /** The cached history list (oldest first, storage order), reloaded when
     * the stored value changed behind the service's back. */
    private fun histCached(): MutableList<UsageSession> {
        val raw = Extra.historyRaw(this)
        val sig = raw.length.toString() + ":" + raw.hashCode()
        val cached = histCache
        if (cached != null && sig == histCacheSig) return cached
        val loaded = Extra.historyEntries(this).asReversed().toMutableList()
        histCache = loaded
        histCacheSig = sig
        return loaded
    }

    private fun histSigSync() {
        val raw = Extra.historyRaw(this)
        histCacheSig = raw.length.toString() + ":" + raw.hashCode()
    }

    /** Append one closed session through the cache (write-through). */
    private fun histAdd(pkg: String, openWall: Long, closeWall: Long) {
        if (closeWall <= openWall) return
        val list = histCached()
        val sigBefore = histCacheSig
        list.add(UsageSession(pkg, openWall, closeWall))
        // mirror the stored prune/cap so the cache stays in sync with disk
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val kept = list.filter { it.open >= cutoff }.takeLast(1000)
        if (kept.size != list.size) {
            list.clear()
            list.addAll(kept)
        }
        Extra.saveHistoryEntries(this, list)
        histSigSync()
        if (histCacheSig == sigBefore) {
            // the write did not land (the stored list is corrupt, so the
            // save refuses to overwrite it): drop the cache so the diverged
            // in-memory entry stops being served — the next read reloads disk
            histCache = null
        }
    }

    /** Remove the newest entry if it matches pkg + open time (the 30 s merge). */
    private fun histRemoveLast(pkg: String, openWall: Long): Boolean {
        val list = histCached()
        val last = list.lastOrNull() ?: return false
        if (last.pkg != pkg || last.open != openWall) return false
        val sigBefore = histCacheSig
        list.removeAt(list.size - 1)
        Extra.saveHistoryEntries(this, list)
        histSigSync()
        if (histCacheSig == sigBefore) {
            // the write did not land: the entry is still on disk, so the
            // removal did not happen — drop the cache and report failure so
            // the caller starts a fresh session instead of resuming
            histCache = null
            return false
        }
        return true
    }

    /**
     * Start timing a history session for [pkg]. If the same app closed less
     * than 30 s ago, the closed entry is removed and the session resumes from
     * its original open time (rapid switching doesn't spam the log).
     * [openWall] is the session's wall-clock open time (midnight for a
     * split session, now otherwise).
     */
    private fun openHistorySession(pkg: String, nowUptime: Long, openWall: Long = System.currentTimeMillis()) {
        val nowWall = openWall
        val lastPkg = lastClosedPkg
        if (lastPkg != null && lastPkg == pkg && nowUptime - lastClosedUptime < 30_000L) {
            if (histRemoveLast(pkg, lastClosedOpenWall)) {
                histPkg = pkg
                histOpenWall = lastClosedOpenWall
                histOpenUptime = lastClosedOpenUptime
                Extra.setOpenHistory(this, pkg, histOpenWall, histOpenUptime)
                return
            }
        }
        histPkg = pkg
        histOpenWall = nowWall
        histOpenUptime = nowUptime
        Extra.setOpenHistory(this, pkg, nowWall, nowUptime)
    }

    /**
     * A previous service instance may have died mid-session: flush its
     * persisted open session with the restart time as the close time. A
     * session older than 12 h is stale (the screen would have gone off) and
     * is dropped instead of logged.
     */
    private fun flushRestartHistorySession() {
        try {
            val (pkg, openWall, _) = Extra.openHistory(this)
            if (pkg == null) return
            val nowWall = System.currentTimeMillis()
            if (nowWall > openWall && nowWall - openWall < 12L * 60 * 60 * 1000) {
                histAdd(pkg, openWall, nowWall)
            }
            Extra.setOpenHistory(this, null, 0L, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "flushRestartHistorySession failed", e)
        }
    }

    /** Flush the in-flight usage slice to the per-day totals (no-op when none). */
    private fun flushUsageSlice() {
        try {
            checkMidnight(SystemClock.uptimeMillis()) // split at midnight first, if crossed
            val prev = usagePkg ?: return
            val secs = (SystemClock.uptimeMillis() - usageSince) / 1000
            if (secs > 0) {
                Extra.addUsage(this, prev, secs)
                usageToday[prev] = (usageToday[prev] ?: 0L) + secs
            }
            usagePkg = null
            Extra.setLiveSlice(this, null, 0L, "")
        } catch (e: Exception) {
            Log.e(TAG, "usage slice flush failed", e)
        }
    }

    /** Screen went off: close the running slice (screen-off time is not usage). */
    private fun flushUsageOnScreenOff() {
        flushUsageSlice()
        closeHistorySession(SystemClock.uptimeMillis())
    }

    /** True when the app has a daily limit and today's time (including the live slice) is up. */
    private fun limitReached(pkg: String): Boolean {
        val lim = limitMap[pkg] ?: return false
        val used = usageToday[pkg] ?: 0L
        val live = if (pkg == usagePkg) (SystemClock.uptimeMillis() - usageSince) / 1000 else 0L
        return used + live >= lim * 60L
    }

    /** Daily-limit block: the same protection screen as a full block, until midnight. */
    private fun onLimitBlockedApp(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        // the launcher, the keyboard and this app itself must never be
        // blocked: a daily limit on the launcher traps the user on the
        // home screen until midnight
        if (pkg == packageName || pkg == currentImePackage() || pkg == defaultLauncherPackage()) return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockPkg && now - lastBlockAt < 1000 && isBlockOverlayAttached()) return
        lastBlockPkg = pkg
        lastBlockAt = now
        limitBlockActive = true
        showBlockOverlay(pkg, L.t("Daily limit reached — opens again tomorrow", "দৈনিক লিমিট শেষ — আগামীকাল আবার খুলবে"))
        Extra.logAttempt(this, "limit", pkg)
    }


    private fun appLabel(pkg: String): String {
        appLabelCache[pkg]?.let { return it }
        val label = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        appLabelCache[pkg] = label
        return label
    }

    private fun ensureBlockOverlay(): View {
        val existing = blockOverlay
        if (existing != null) return existing
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(250, 8, 16, 12))
        root.isClickable = true // touches must not reach the app underneath
        root.setPadding(dp(32), dp(32), dp(32), dp(32))
        val icon = IconView(this, Ic.SHIELD, C.primary)
        root.addView(icon, lp(dp(84), dp(84)))
        blockOverlayIcon = icon
        val title = tv("", 22f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv(L.t("This app is blocked while the lock runs", "লক চলাকালীন এই অ্যাপ ব্লক করা আছে"), 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        blockOverlaySub = sub
        val msg = tv("", 15f, C.mint, 1)
        msg.gravity = Gravity.CENTER
        root.addView(msg, lp(WRAP, WRAP, t = dp(10)))
        blockOverlayMsg = msg
        val btn = primaryBtn(L.t("Back to Home", "হোমে ফিরুন"))
        btn.setOnClickListener {
            hideBlockOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        root.addView(btn, lp(MATCH, dp(52), t = dp(12)))
        blockOverlay = root
        blockOverlayTitle = title
        return root
    }

    /**
     * True attach state of the block screen: the WindowManager is
     * authoritative, the boolean flag is only a cache. The two can disagree
     * after a failed add or remove, so every decision uses this, not the
     * flag alone — a stale flag is what used to re-add an already-attached
     * view (throwing) and leave a dark screen stuck up.
     */
    private fun isBlockOverlayAttached(): Boolean {
        if (blockOverlayAttached) return true
        return try { blockOverlay?.isAttachedToWindow == true } catch (_: Exception) { false }
    }

    private fun showBlockOverlay(pkg: String, sub: String? = null) {
        hideOverlay() // never stack the power-menu shield under the block screen
        blockedPkg = pkg
        val subText = sub ?: L.t("This app is blocked while the lock runs", "লক চলাকালীন এই অ্যাপ ব্লক করা আছে")
        // already up (the flag or the window itself says so): refresh the
        // text on the live screen — never re-add an attached view, or the
        // WindowManager throws and the user gets kicked home
        if (isBlockOverlayAttached()) {
            blockOverlayAttached = true
            // a reused view can be stuck mid-entrance: finish it instantly so
            // the message and the Home button are always fully visible
            try { blockOverlay?.animate()?.cancel() } catch (_: Exception) { }
            try { blockOverlayIcon?.animate()?.cancel() } catch (_: Exception) { }
            blockOverlay?.alpha = 1f
            blockOverlayIcon?.let { it.scaleX = 1f; it.scaleY = 1f }
            blockOverlayTitle?.text = appLabel(pkg)
            blockOverlaySub?.text = subText
            blockOverlayMsg?.text = blockMsg
            blockOverlayMsg?.visibility = if (blockMsg.isBlank()) View.GONE else View.VISIBLE
            return
        }
        // a failed add retries at most once a second: the event burst after
        // an app opens must not hammer the WindowManager (or the log)
        if (SystemClock.uptimeMillis() - lastBlockShowFailedAt < 1000) return
        try {
            val v = ensureBlockOverlay()
            // reset the reused view first: a previous show may have left it
            // mid-animation, and a stale animator would fight the new one
            try { v.animate().cancel() } catch (_: Exception) { }
            try { blockOverlayIcon?.animate()?.cancel() } catch (_: Exception) { }
            // attach first (label + animation after): the cover must be instant
            v.alpha = 0f
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, params)
            blockOverlayAttached = true
            blockOverlayTitle?.text = appLabel(pkg)
            blockOverlaySub?.text = subText
            blockOverlayMsg?.text = blockMsg
            blockOverlayMsg?.visibility = if (blockMsg.isBlank()) View.GONE else View.VISIBLE
            // entrance: the screen fades in and the shield pops
            v.animate().alpha(1f).setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
            val ic = blockOverlayIcon
            if (ic != null) {
                ic.scaleX = 0.6f
                ic.scaleY = 0.6f
                ic.animate().scaleX(1f).scaleY(1f).setDuration(380)
                    .setInterpolator(OvershootInterpolator(1.8f)).start()
            }
        } catch (ex: Exception) {
            Log.e(TAG, "showBlockOverlay failed", ex)
            lastBlockShowFailedAt = SystemClock.uptimeMillis()
            // reconcile: the add may have failed *because* the view is already
            // attached (double-add) — in that case the block screen is up and
            // doing its job, so refresh its text instead of pressing Home
            val attached = try { blockOverlay?.isAttachedToWindow == true } catch (_: Exception) { false }
            blockOverlayAttached = attached
            if (attached) {
                blockOverlayTitle?.text = appLabel(pkg)
                blockOverlaySub?.text = subText
                blockOverlayMsg?.text = blockMsg
                blockOverlayMsg?.visibility = if (blockMsg.isBlank()) View.GONE else View.VISIBLE
                return
            }
            // real failure: fall back to leaving the app at once — but
            // throttled, so one broken add cannot kick the user out on every
            // accessibility event while the app stays in front
            val now = SystemClock.uptimeMillis()
            if (now - lastBlockHomeFallback >= 3000) {
                lastBlockHomeFallback = now
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun hideBlockOverlay() {
        blockedPkg = null
        val v = blockOverlay
        if (v == null) {
            blockOverlayAttached = false
            limitBlockActive = false
            return
        }
        // stop the entrance animation and restore the resting state, so the
        // reused view never resumes half-faded or half-scaled — and so a view
        // that cannot be removed stays fully visible with its message instead
        // of a dark blank screen
        try { v.animate().cancel() } catch (_: Exception) { }
        try { blockOverlayIcon?.animate()?.cancel() } catch (_: Exception) { }
        v.alpha = 1f
        blockOverlayIcon?.let { it.scaleX = 1f; it.scaleY = 1f }
        if (!isBlockOverlayAttached()) {
            // the flag and the window agree the screen is down
            blockOverlayAttached = false
            limitBlockActive = false
            return
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideBlockOverlay failed", ex)
        }
        // reconcile: only the WindowManager knows whether the screen is
        // really gone — the flag follows it, never the other way round. If
        // the view is still attached, the limit block stays live and the next
        // hide retries the removal instead of assuming it worked.
        val stillAttached = try { v.isAttachedToWindow } catch (_: Exception) { false }
        blockOverlayAttached = stillAttached
        if (!stillAttached) limitBlockActive = false
    }

    /**
     * Reels block: the app stays usable, only the Reels / Shorts feed is closed.
     * A detection presses Back (out of the feed/player). Home is never used:
     * pressing Home while a Short plays sends YouTube into picture-in-picture,
     * where the video keeps playing in a small floating window. After a Back
     * there is a short cooldown: the close animation keeps the reel in the
     * tree for a moment, and Backing again would leave the app instead of
     * just the reel. Only the reel is closed — never the app itself.
     */
    private fun onReelsApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        // window state changes always scan (a new screen may be the feed);
        // other events are throttled so scrolling does not scan constantly
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - lastReelsScan < 400) return
            lastReelsScan = now
        }
        val detected = looksLikeReels(e)
        // diagnostic: every 3s, record what the service actually sees on this
        // screen, so a missed block can be traced instead of guessed — but
        // only while the Diagnostic card is open (the scan walks the tree)
        if (diagOpen && now - lastDebugScan >= 3000) {
            lastDebugScan = now
            debugScanReels(e.packageName?.toString() ?: "?", e, detected)
        }
        if (!detected) return
        if (now - lastAppBlockAction < 500) return
        if (now - lastReelsBack < 1500) return
        lastAppBlockAction = now
        lastReelsBack = now
        Extra.logAttempt(this, "reels", e.packageName?.toString() ?: "")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Diagnostic: records what the service actually sees on a reels app's
     * screen — which signals are present (marker text, counts, action labels,
     * viewer view IDs) and how many nodes were scanned. Written to
     * [debugLog], shown in the Protection screen's Diagnostic card. The log
     * is capped so it never grows without bound.
     */
    private fun debugScanReels(pkg: String, e: AccessibilityEvent, detected: Boolean) {
        val lines = mutableListOf<String>()
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.add("[$time] $pkg detected=$detected event=${e.eventType} cls=${e.className}")
        val countRe = Regex("^[0-9,]+(\\.[0-9]+)?[km]?$")
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val wins = windows ?: emptyList()
            for (w in wins) {
                try { w.root?.let { roots.add(it) } } catch (_: Exception) { }
            }
            lines.add("  windows=${roots.size}")
            var total = 0
            val markers = mutableListOf<String>()
            val counts = mutableListOf<String>()
            val actions = mutableListOf<String>()
            val viewIds = mutableListOf<String>()
            for (root in roots.asReversed()) {
                val stack = ArrayDeque<AccessibilityNodeInfo>()
                stack.add(root)
                var used = 0
                while (stack.isNotEmpty() && used < 800) {
                    val n = stack.removeFirst()
                    used++
                    total++
                    try {
                        val lt = (n.text?.toString() ?: "").lowercase()
                        val lc = (n.contentDescription?.toString() ?: "").lowercase()
                        val cls = n.className?.toString() ?: ""
                        if (reelsMarkersLower.any { lt.contains(it) || lc.contains(it) }) {
                            if (markers.size < 5) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                markers.add("marker cls=$cls t='$lt' cd='$lc' sel=${n.isSelected} chk=${n.isChecked} b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        if (countRe.matches(lt)) {
                            if (counts.size < 8) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                counts.add("count '$lt' b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        val label = lt.trim().ifEmpty { lc.trim() }
                        if (label.startsWith("like") || label.startsWith("comment") || label.startsWith("share")) {
                            if (actions.size < 8) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                actions.add("action '$label' b=(${r.left},${r.top}-${r.right},${r.bottom})")
                            }
                        }
                        val vid = n.viewIdResourceName ?: ""
                        if (vid.isNotEmpty() && (vid.contains("viewer") || vid.contains("player")) && reelsMarkersLower.any { vid.contains(it) }) {
                            if (viewIds.size < 5) viewIds.add("viewId $vid")
                        }
                        for (i in 0 until n.childCount) {
                            try { n.getChild(i)?.let { stack.add(it) } } catch (_: Exception) { }
                        }
                    } catch (_: Exception) {
                    } finally {
                        if (n !== root) {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                }
                while (stack.isNotEmpty()) {
                    val x = stack.removeFirst()
                    if (x !== root) {
                        try { x.recycle() } catch (_: Exception) { }
                    }
                }
            }
            lines.add("  nodes=$total markers=${markers.size} counts=${counts.size} actions=${actions.size} viewIds=${viewIds.size}")
            markers.forEach { lines.add("  $it") }
            counts.forEach { lines.add("  $it") }
            actions.forEach { lines.add("  $it") }
            viewIds.forEach { lines.add("  $it") }
            // plain-language summary (2 lines per scan) for the Diagnostic card
            val label = try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
            val status = if (detected) L.t("Reels detected ✓", "রিলস ধরা পড়েছে ✓") else L.t("Reels not detected ✗", "রিলস ধরা পড়েনি ✗")
            debugSummary.add("[${time.take(5)}] $label — $status")
            debugSummary.add(
                "  " + L.t("Reels text", "Reels লেখা") + ": ${markers.size} • " +
                    L.t("counts", "সংখ্যা") + ": ${counts.size} • " +
                    L.t("buttons", "বোতাম") + ": ${actions.size} • " +
                    L.t("viewer", "ভিউয়ার") + ": " + if (viewIds.isNotEmpty()) L.t("yes", "আছে") else L.t("no", "নেই")
            )
            while (debugSummary.size > 60) debugSummary.removeAt(0)
        } catch (ex: Exception) {
            lines.add("  debug error: ${ex.message}")
        } finally {
            for (r in roots) {
                try { r.recycle() } catch (_: Exception) { }
            }
        }
        debugLog.addAll(lines)
        while (debugLog.size > 120) debugLog.removeAt(0)
    }

    /**
     * Search block: the app stays usable and search opens normally (the
     * keyboard appears), but as soon as the user starts typing, the query
     * is cleared and Back closes search — the user lands back in the app,
     * never Home, and the next search starts empty. Searching inside a
     * chat or channel (Telegram, WhatsApp…) is NOT blocked: it shows a
     * "1 of 5" match counter, and only the main search bar is blocked.
     */
    private fun onMsgSearchApp(e: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - lastMsgScan < 400) return
            lastMsgScan = now
        }
        if (!looksLikeSearch(e)) return
        // the keyboard must appear first: only act once the user types
        if (!focusedSearchFieldHasText()) return
        // in-chat / in-channel search shows a "1 of 5" match counter — allowed
        if (isInChatSearch()) return
        if (now - lastMsgAction < 500) return
        lastMsgAction = now
        clearFocusedSearchField() // erase the query so the next search starts empty
        Extra.logAttempt(this, "msgsearch", e.packageName?.toString() ?: "")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Runs [block] with the focused search field (if any); the node is recycled after. */
    private fun withFocusedSearchField(block: (AccessibilityNodeInfo) -> Unit) {
        try {
            val root = rootInActiveWindow ?: return
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
                try {
                    val isEdit = (focused.className?.toString() ?: "").contains("EditText")
                    val hint = focused.hintText?.toString() ?: ""
                    if (!isEdit || !hint.lowercase().contains("search")) return
                    block(focused)
                } finally {
                    try { focused.recycle() } catch (_: Exception) { }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "focused search field failed", ex)
        }
    }

    /** True if the focused search field already has typed text in it. */
    private fun focusedSearchFieldHasText(): Boolean {
        var hasText = false
        withFocusedSearchField { hasText = !it.text.isNullOrEmpty() }
        return hasText
    }

    /** Clears the focused search field (if any) via ACTION_SET_TEXT with "". */
    private fun clearFocusedSearchField() {
        withFocusedSearchField {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
    }

    /** True if the current search is inside a chat/channel: it shows a "1 of 5" match counter. */
    private fun isInChatSearch(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            try {
                val nodes = root.findAccessibilityNodeInfosByText(" of ")
                try {
                    val re = Regex("^\\d+ of \\d+$")
                    for (n in nodes) {
                        val t = n.text?.toString()?.trim() ?: ""
                        if (re.matches(t)) return true
                    }
                    false
                } finally {
                    for (n in nodes) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Is a search screen open right now? Two precise signals only:
     * 1) a new window whose class contains "search" (a dedicated search screen),
     * 2) the focused input is a search field (its hint contains "search").
     * The chat list's own search bar is never focused, and a message input's
     * hint is "Message", so neither false-positives. No plain text check:
     * "Search" text sits on the chat list too.
     */
    private fun looksLikeSearch(e: AccessibilityEvent): Boolean {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = (e.className?.toString() ?: "").lowercase()
            if (cls.contains("search")) return true
        }
        return try {
            val root = rootInActiveWindow ?: return false
            try {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                try {
                    val isEdit = (focused.className?.toString() ?: "").contains("EditText")
                    val hint = focused.hintText?.toString() ?: ""
                    isEdit && hint.lowercase().contains("search")
                } finally {
                    try { focused.recycle() } catch (_: Exception) { }
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * A chat/messages screen (a "Messages" or "Chats" header at the top) is
     * never a Reels feed. This guards the Reels block against false-positives
     * inside messaging tabs (e.g. Facebook Lite's Messages tab with its notes
     * row), where a Back press would kick the user out of their chats.
     */
    private fun isChatScreen(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val screenH = resources.displayMetrics.heightPixels
            var found = false
            try {
                for (marker in listOf("Messages", "Chats")) {
                    val nodes = root.findAccessibilityNodeInfosByText(marker)
                    for (n in nodes) {
                        try {
                            val t = n.text?.toString()?.trim() ?: ""
                            if (t.equals(marker, ignoreCase = true)) {
                                val r = Rect()
                                n.getBoundsInScreen(r)
                                if (r.top >= 0 && r.top < screenH * 0.20) {
                                    found = true
                                    break
                                }
                            }
                        } finally {
                            try { n.recycle() } catch (_: Exception) { }
                        }
                    }
                    if (found) break
                }
            } finally {
                try { root.recycle() } catch (_: Exception) { }
            }
            found
        } catch (ex: Exception) {
            false
        }
    }

    /**
     * Signals, cheapest first:
     * 1) the window class on a window-state change (reel / shorts / story
     *    viewer) — on a content change the class is the changed node's class,
     *    and a story cell in the feed would false-positive,
     * 2) the app's navigation: a Reels / Shorts / Status tab that is selected
     *    or checked (bottom-nav items are Checkable, so isChecked is the state
     *    that actually changes — checking only isSelected missed them),
     * 3) a bounded tree scan: icon-only tabs (label in contentDescription),
     *    the story/status reply field, the YouTube Shorts "Remix" button, and
     *    the viewer's action/count rail at the right edge (no header check:
     *    the feed's Reels carousel has the same header).
     * A plain text search would false-positive (the "Reels" tab label is in
     * the tree even on the home feed, and the feed's Reels carousel carries
     * the same text), so selection/checked state — or a viewer-only
     * fingerprint — is what counts. Chat/messages screens are excluded up
     * front: they are never a Reels feed.
     */
    private fun looksLikeReels(e: AccessibilityEvent): Boolean {
        if (isChatScreen()) return false
        val pkg = e.packageName?.toString() ?: ""
        val cls = (e.className?.toString() ?: "").lowercase()
        // a messaging screen is never a Reels feed (the event's own class —
        // a message/chat/conversation screen must not be Backed out of)
        if (cls.contains("message") || cls.contains("chat") || cls.contains("conversation")) return false
        // the window class only identifies a screen on a window-state change:
        // on a content change it is the changed node's class (a story cell
        // in the feed would false-positive and Back the user out of the app)
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (cls.contains("reel") || cls.contains("shorts") || cls.contains("clips") ||
                cls.contains("spotlight") || cls.contains("story")
            ) return true
            if ((pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") && cls.contains("status")) return true
        }
        if (reelsTabSelected()) return true
        return reelsUiScan(pkg)
    }

    /** A marker tab is selected or checked (node, parent or grandparent). */
    private fun reelsTabSelected(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            for (marker in reelsTabMarkers) {
                val nodes = root.findAccessibilityNodeInfosByText(marker)
                var found = false
                for (n in nodes) {
                    // the label itself may not carry the state — its tab container does
                    var f = n.isSelected || n.isChecked
                    var p1: AccessibilityNodeInfo? = null
                    var p2: AccessibilityNodeInfo? = null
                    if (!f) {
                        p1 = n.parent
                        f = p1?.let { it.isSelected || it.isChecked } == true
                    }
                    if (!f && p1 != null) {
                        p2 = p1.parent
                        f = p2?.let { it.isSelected || it.isChecked } == true
                    }
                    try { p2?.recycle() } catch (_: Exception) { }
                    try { p1?.recycle() } catch (_: Exception) { }
                    if (f) {
                        found = true
                        break
                    }
                }
                for (n in nodes) {
                    try { n.recycle() } catch (_: Exception) { }
                }
                if (found) return true
            }
            return false
        } catch (ex: Exception) {
            return false
        } finally {
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    /**
     * Thorough tree scan (bounded: 500 nodes over all of the app's windows),
     * run only when the cheap signals miss. Catches what the text search cannot:
     * - icon-only tabs whose label is a contentDescription,
     * - the story/status viewer: its reply field's hint starts with "Reply"
     *   (Instagram / Facebook / WhatsApp / Telegram story viewers), or the
     *   "You can't reply to this story" notice at the bottom of the screen
     *   (Telegram stories with replies off have no reply field at all),
     * - the YouTube Shorts player: the "Remix" button exists only there
     *   (the bottom nav is hidden in the player, so the tab check can't see it),
     * - (no header check: the feed's Reels carousel carries the same "Reels"
     *   header at the top when scrolled, and blocking on it would Back the
     *   user out of the feed — the rail is the viewer-only fingerprint),
     * - viewer/player view IDs containing reel / short / story,
     * - the viewer's action rail: Like / Comment / Share stacked vertically
     *   on the right edge (the feed shows them in a horizontal row instead),
     * - the viewer's count rail: bare count numbers (14K, 562, 58) stacked
     *   at the right edge below the icons (their text is what FB Lite exposes).
     * All of the app's windows are scanned (topmost first, each with its own
     * budget): story viewers can live in their own window, not the active one.
     */
    private fun reelsUiScan(pkg: String): Boolean {
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            try {
                for (w in windows) {
                    try {
                        val r = w.root ?: continue
                        if (r.packageName?.toString() == pkg) roots.add(r)
                        else try { r.recycle() } catch (_: Exception) { }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
            if (roots.isEmpty()) {
                try {
                    rootInActiveWindow?.let { roots.add(it) }
                } catch (_: Exception) { }
            }
            // topmost window first: the viewer may sit in its own window above
            // the feed, and each window gets its own budget so a big feed tree
            // can no longer starve the viewer window
            for (r in roots.asReversed()) {
                val used = intArrayOf(0)
                if (scanReelsTree(r, pkg, screenH, screenW, 500, used)) return true
            }
            return false
        } finally {
            for (r in roots) {
                try { r.recycle() } catch (_: Exception) { }
            }
        }
    }

    /** Bounded DFS of one window's tree. Does not recycle [root]. */
    private fun scanReelsTree(
        root: AccessibilityNodeInfo, pkg: String, screenH: Int, screenW: Int, budget: Int, used: IntArray
    ): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        // right-edge action rail: kinds + y positions of Like / Comment / Share buttons
        val railKinds = HashSet<String>()
        val railTops = ArrayList<Int>()
        // viewer count rail: y positions of bare count numbers at the right edge
        val railCounts = ArrayList<Int>()
        // a bare count: 562, 58, 14K, 1.2M (lowercase text, so [km] not [KM])
        val countRe = Regex("^[0-9,]+(\\.[0-9]+)?[km]?$")
        try {
            while (stack.isNotEmpty() && used[0] < budget) {
                val n = stack.removeFirst()
                used[0]++
                try {
                    val text = n.text?.toString() ?: ""
                    val cd = n.contentDescription?.toString() ?: ""
                    val lt = text.lowercase()
                    val lc = cd.lowercase()
                    // viewer action rail: Like / Comment / Share stacked vertically
                    // on the right edge. At least two DIFFERENT kinds must be there:
                    // a feed post has only its Share button at the right edge, and
                    // two posts on screen would otherwise false-positive.
                    val label = lt.trim().ifEmpty { lc.trim() }
                    val kind = when {
                        label.startsWith("like") -> "like"
                        label.startsWith("comment") -> "comment"
                        label.startsWith("share") -> "share"
                        else -> null
                    }
                    if (kind != null) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        // centerX, not left: the icon may sit in a wider touch target
                        if (r.centerX() > screenW * 0.80 && r.top >= 0) {
                            railKinds.add(kind)
                            railTops.add(r.top)
                            val top = railTops.maxOrNull() ?: 0
                            val bottom = railTops.minOrNull() ?: 0
                            if (railKinds.size >= 2 && railTops.size >= 2 && top - bottom > screenH * 0.10) return true
                        }
                    }
                    // viewer / player view IDs (e.g. reels_viewer, story_viewer)
                    val vid = n.viewIdResourceName?.lowercase() ?: ""
                    if ((vid.contains("viewer") || vid.contains("player")) &&
                        (vid.contains("reel") || vid.contains("short") || vid.contains("story"))
                    ) return true
                    // YouTube Shorts player: Remix exists only in the Shorts player
                    if (pkg == "com.google.android.youtube" &&
                        (lt.contains("remix") || lc.contains("remix"))
                    ) return true
                    // Story / Status viewer: the reply field's hint starts with "Reply"
                    val clsN = n.className?.toString() ?: ""
                    if (clsN.contains("EditText")) {
                        val hint = n.hintText?.toString() ?: ""
                        if (hint.lowercase().startsWith("reply")) return true
                    }
                    // Viewer count rail: the Like / Comment / Share icons carry no
                    // text in FB Lite, but their counts do (14K, 562, 58) — short
                    // bare numbers stacked vertically at the right edge, just below
                    // each icon. The feed has no bare numbers at the right edge (its
                    // "10 comments" text is not a bare number), and the top-bar
                    // notification badge is excluded by the y > 15% rule.
                    if (countRe.matches(lt)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        // right edge, not left: the count TextView may be wide with
                        // right-aligned text (its left edge would be far from the rail)
                        if (r.right > screenW * 0.80 && r.top > screenH * 0.15 && r.top >= 0) {
                            railCounts.add(r.top)
                            val top = railCounts.maxOrNull() ?: 0
                            val bottom = railCounts.minOrNull() ?: 0
                            if (railCounts.size >= 2 && top - bottom > screenH * 0.10) return true
                        }
                    }
                    // Story viewer with replies off: "You can't reply to this story"
                    // sits at the bottom of the screen (Telegram)
                    if (lt.contains("this story")) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.top > screenH * 0.75) return true
                    }
                    if (reelsMarkersLower.any { lt.contains(it) || lc.contains(it) }) {
                        // marker tab, selected or checked (itself or an ancestor)
                        if (n.isSelected || n.isChecked) return true
                        var p1: AccessibilityNodeInfo? = null
                        var p2: AccessibilityNodeInfo? = null
                        var found = false
                        try {
                            p1 = n.parent
                            found = p1?.let { it.isSelected || it.isChecked } == true
                            if (!found && p1 != null) {
                                p2 = p1.parent
                                found = p2?.let { it.isSelected || it.isChecked } == true
                            }
                        } catch (_: Exception) {
                        }
                        try { p2?.recycle() } catch (_: Exception) { }
                        try { p1?.recycle() } catch (_: Exception) { }
                        if (found) return true
                        // NOTE: no header check here on purpose — the feed's Reels
                        // carousel has the same "Reels" header at the top when
                        // scrolled, and blocking on it would Back the user out of
                        // the feed. The viewer is caught by its rail instead.
                    }
                    for (i in 0 until n.childCount) {
                        try {
                            n.getChild(i)?.let { stack.add(it) }
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    if (n !== root) {
                        try { n.recycle() } catch (_: Exception) { }
                    }
                }
            }
            return false
        } catch (ex: Exception) {
            return false
        } finally {
            while (stack.isNotEmpty()) {
                val x = stack.removeFirst()
                if (x !== root) {
                    try { x.recycle() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun looksLikePowerMenu(e: AccessibilityEvent): Boolean {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = (e.className?.toString() ?: "").lowercase()
            if (cls.contains("globalaction") || cls.contains("powermenu") || cls.contains("poweroff") || cls.contains("shutdown")) {
                return true
            }
        }
        val sb = StringBuilder()
        for (t in e.text) sb.append(t).append(' ')
        val cd = e.contentDescription
        if (cd != null) sb.append(cd)
        if (sb.isEmpty()) return false
        val s = sb.toString().lowercase()
        for (t in triggersLower) {
            if (s.contains(t)) return true
        }
        return false
    }

    /** The power menu always has the "power off" slider (English or Bengali). */
    private fun hasPowerMenu(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        for (t in listOf("to power off", "পাওয়ার বন্ধ", "রিস্টার্ট")) {
            val nodes = n.findAccessibilityNodeInfosByText(t)
            val found = nodes.isNotEmpty()
            for (x in nodes) {
                try { x.recycle() } catch (_: Exception) { }
            }
            if (found) return true
        }
        return false
    }

    /** System windows first (where the power menu lives); other windows only when [all] is true. */
    private fun scanWindows(all: Boolean): Boolean {
        return try {
            val list = windows
            var found = false
            for (w in list) {
                if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    val root = w.root
                    try {
                        if (root != null && hasPowerMenu(root)) {
                            found = true
                            break
                        }
                    } finally {
                        try { root?.recycle() } catch (_: Exception) { }
                    }
                }
            }
            if (!found && all) {
                for (w in list) {
                    if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) {
                        val root = w.root
                        try {
                            if (root != null && hasPowerMenu(root)) {
                                found = true
                                break
                            }
                        } finally {
                            try { root?.recycle() } catch (_: Exception) { }
                        }
                    }
                }
            }
            found
        } catch (ex: Exception) {
            false
        }
    }

    private fun isGuarded(p: String): Boolean =
        p.contains("settings") || p.contains("packageinstaller") ||
                p.contains("securitycenter") || p.contains("permissioncontroller")

    // ---------- blocking ----------

    private fun block() {
        showOverlay() // shield first: from this moment the slider cannot be touched
        val now = SystemClock.uptimeMillis()
        if (now - lastAction >= 100) {
            lastAction = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.removeCallbacks(backAgain)
            handler.removeCallbacks(backAgain2)
            handler.postDelayed(backAgain, 60)
            handler.postDelayed(backAgain2, 150)
            // no HOME: the app the user is in must stay open
        }
    }

    private fun ensureOverlay(): View {
        val existing = overlay
        if (existing != null) return existing
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(245, 8, 16, 12))
        root.isClickable = true
        root.addView(IconView(this, Ic.SHIELD, C.primary), lp(dp(84), dp(84)))
        val title = tv(L.t("PowerLock 2 is active", "PowerLock 2 সক্রিয়"), 24f, C.text, 1)
        title.gravity = Gravity.CENTER
        root.addView(title, lp(WRAP, WRAP, t = dp(16)))
        val sub = tv("", 15f, C.muted)
        sub.gravity = Gravity.CENTER
        root.addView(sub, lp(WRAP, WRAP, t = dp(8)))
        overlay = root
        overlaySub = sub
        return root
    }

    /** True attach state of the power-menu shield, mirroring
     * isBlockOverlayAttached(): the WindowManager is authoritative, the
     * boolean flag is only a cache. */
    private fun isOverlayAttached(): Boolean {
        if (overlayAttached) return true
        return try { overlay?.isAttachedToWindow == true } catch (_: Exception) { false }
    }

    private fun showOverlay() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 1400)
        if (isOverlayAttached()) return // never more than one shield
        try {
            val v = ensureOverlay()
            val sessionOn = Store.isActive(this)
            overlaySub?.text = if (locProtecting && !sessionOn) {
                L.t("Location lock is active", "লোকেশন লক সক্রিয়")
            } else {
                val at = fmtClock(System.currentTimeMillis() + Store.remainingMs(this))
                L.t("Power menu is blocked until $at", "$at পর্যন্ত পাওয়ার মেনু ব্লক করা আছে")
            }
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(v, params)
            overlayAttached = true
        } catch (ex: Exception) {
            Log.e(TAG, "showOverlay failed", ex)
            // reconcile: the add may have failed *because* the view is
            // already attached — the flag follows the WindowManager
            overlayAttached = try { overlay?.isAttachedToWindow == true } catch (_: Exception) { false }
        }
    }

    private fun hideOverlay() {
        if (!isOverlayAttached()) {
            overlayAttached = false
            return
        }
        val v = overlay
        if (v == null) {
            overlayAttached = false
            return
        }
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        } catch (ex: Exception) {
            Log.e(TAG, "hideOverlay failed", ex)
        }
        // reconcile: only the WindowManager knows whether the shield is
        // really gone — the flag follows it, never the other way round
        overlayAttached = try { v.isAttachedToWindow } catch (_: Exception) { false }
    }

    private fun cleanup() {
        destroyed = true
        flushUsageSlice()
        closeHistorySession(SystemClock.uptimeMillis())
        Store.flushRoutePoints(this) // persist any batched route points
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        hideBlockOverlay()
        if (receiverOn) {
            receiverOn = false
            try {
                unregisterReceiver(closeDialogsReceiver)
            } catch (ex: Exception) {
                Log.e(TAG, "unregister close-dialogs failed", ex)
            }
        }
        if (screenReceiverOn) {
            screenReceiverOn = false
            try {
                unregisterReceiver(screenOffReceiver)
            } catch (ex: Exception) {
                Log.e(TAG, "unregister screen-off failed", ex)
            }
        }
        try {
            Store.lock(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister failed", ex)
        }
        try {
            Store.settings(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (ex: Exception) {
            Log.e(TAG, "unregister settings failed", ex)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
