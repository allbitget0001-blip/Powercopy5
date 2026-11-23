package com.powerlock.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** startActivityForResult codes: VPN consent for the site block, gallery pick for the home photo. */
const val REQ_VPN = 1001
const val REQ_WALLPAPER = 1002

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var navHolder: FrameLayout
    private val navItems = ArrayList<LinearLayout>()
    private val navLabels = ArrayList<TextView>()
    private val navIcons = ArrayList<IconView>()
    private var tab = 0
    private var lastNavTab = -1
    private var home: HomeScreen? = null

    /** Selected quick-timer duration in minutes (persisted). */
    var selectedMin: Int
        get() = Store.settings(this).getInt("sel_min", 30)
        set(v) {
            Store.settings(this).edit().putInt("sel_min", v).apply()
        }

    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tickCount++
                // the first ticks only: the self-close happens ~1-2 s after
                // open, so ticks 1-5 show whether the activity was still
                // alive and ticking when the process died
                if (tickCount <= 5) Diag.crumb("ticker tick #$tickCount")
                Store.finalizeIfExpired(this@MainActivity)
                // a DND restore that failed earlier (permission revoked
                // mid-lock) is retried while no lock runs
                if (!Store.isActive(this@MainActivity)) Dnd.restore(this@MainActivity)
                home?.tick()
                // widget countdown: every 30 s is enough (the service heartbeat
                // updates it every 60 s while the app is closed)
                if (System.currentTimeMillis() - lastWidgetRefresh >= 30_000) {
                    lastWidgetRefresh = System.currentTimeMillis()
                    LockWidget.refresh(this@MainActivity)
                }
            } catch (e: Exception) {
                Log.e(TAG, "tick failed", e)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private var lastWidgetRefresh = 0L

    companion object {
        /** True while the activity is in the foreground (onResume..onPause). */
        @Volatile
        var isForeground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(this) // backstop: App.onCreate already installed the handler
        Diag.refreshSnapshotIfEmpty(this) // build 72: the process may have survived the last close — re-read the stored trail before this run's first crumb overwrites it
        Diag.crumb("MainActivity.onCreate start")
        L.load(this)
        C.load(this)
        autoCopyPrevDiag() // build 71: a previous run's log, copied before the app can die again
        tab = savedInstanceState?.getInt("tab") ?: 0

        // Notifications: ask once per install. Re-asking on every cold start
        // is noise, and after two denials the system auto-denies silently —
        // the Settings permission card explains the rest.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED &&
            !Store.settings(this).getBoolean("notif_asked", false)
        ) {
            Store.settings(this).edit().putBoolean("notif_asked", true).apply()
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        frame = FrameLayout(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true
        content = FrameLayout(this)
        navHolder = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, weight = 1f))
        root.addView(navHolder, lp(MATCH, WRAP))
        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)

        applyChrome()
        navHolder.addView(buildNav())
        Scheduler.onAlarm(this)
        Diag.crumb("Scheduler.onAlarm done")
        Reminders.rescheduleAll(this) // morning alarms (idempotent)
        Diag.crumb("Reminders.rescheduleAll done")
        TimeCapsules.checkDue(this) // a capsule may have opened while the app was closed
        Diag.crumb("TimeCapsules.checkDue done")
        go(tab, false)
        Diag.crumb("go(tab) done")
        Store.kickService(this) // restart the foreground service if it was swiped away
        Diag.crumb("kickService called")
        Watchdog.enqueue(this) // safety net: restart the service every 15 min if it dies
        Diag.crumb("Watchdog.enqueue done")
        PlaceWatcher.checkNow(this) // already at a saved place? lock right away
        Diag.crumb("PlaceWatcher.checkNow called")
        PlaceWatcher.checkUnlockNow(this) // location-unlock session? check arrival right away
        Diag.crumb("PlaceWatcher.checkUnlockNow called")
        Diag.crumb("MainActivity.onCreate end")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        Diag.crumb("onResume")
        handler.post(ticker)
        if (tab == 3) go(3, false) // refresh permission status after returning from system settings
        if (tab == 4) PowerBlockService.diagOpen = true // back on Protection: the Diagnostic card is visible
    }

    override fun onPause() {
        isForeground = false
        Diag.crumb("onPause")
        handler.removeCallbacks(ticker)
        PowerBlockService.diagOpen = false // the app is in the background: no card is visible
        super.onPause()
    }

    override fun onDestroy() {
        Diag.crumb("onDestroy")
        VoiceQuotes.shutdown()
        Store.flushRoutePoints(this) // persist any batched route points
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val granted = res.any { it == PackageManager.PERMISSION_GRANTED }
        when (code) {
            1 -> {
                // notifications: refresh the Settings permission card (no re-prompt here)
                if (tab == 3) go(3, false)
            }
            11 -> {
                // foreground location: restart watching, refresh the screen,
                // then chain the background-location prompt
                if (granted) {
                    PlaceWatcher.sync(this)
                    go(tab, false)
                    requestLocationPerm()
                }
            }
            12 -> {
                if (granted) {
                    PlaceWatcher.sync(this)
                    go(tab, false)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> {
                if (resultCode == RESULT_OK) {
                    SiteBlock.sync(this)
                    toast(L.t("Site block is ready", "সাইট ব্লক প্রস্তুত"))
                } else {
                    SiteBlock.setOn(this, false)
                    toast(L.t("Site block needs VPN permission", "সাইট ব্লকের জন্য VPN অনুমতি লাগবে"))
                }
                if (tab == 4) go(4, false) // refresh the Protection tab's switch + state line
            }
            REQ_WALLPAPER -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data
                    if (uri != null && Wallpaper.saveFromUri(this, uri)) {
                        crossfade { }
                        toast(L.t("Home photo set", "হোমের ছবি সেট হয়েছে"))
                    } else {
                        toast(L.t("Could not use that photo", "ওই ছবি ব্যবহার করা যায়নি"))
                    }
                }
            }
        }
    }

    /**
     * Build 71: copy the previous run's diagnostic report to the clipboard
     * (once per process). The app may die seconds after launch, so the user
     * must not need to navigate anywhere: open, let it close, open again,
     * paste the clipboard into chat. Fires only when the previous run left
     * a crash or a trail; the Settings Diagnostics Clear action stops it.
     */
    private fun autoCopyPrevDiag() {
        try {
            val report = Diag.takePreviousReport() ?: return
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PowerLock diagnostics", report))
            toast(L.t("Diagnostic log copied — paste it to Muse", "ডায়াগনস্টিক লগ কপি হয়েছে — Muse-কে পাঠিয়ে দিন"))
        } catch (t: Throwable) {
            Log.e(TAG, "diag auto-copy failed", t)
        }
    }

    fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    private fun applyChrome() {
        root.setBackgroundColor(C.bg)
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg
    }

    /**
     * Applies a language / theme change without recreating the activity:
     * a snapshot of the current screen stays on top and fades out while the new screen is built underneath.
     */
    fun crossfade(change: () -> Unit) {
        var bmp: Bitmap? = null
        var overlay: ImageView? = null
        try {
            val w = root.width
            val h = root.height
            if (w > 0 && h > 0) {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(b))
                val iv = ImageView(this)
                iv.setImageBitmap(b)
                iv.isClickable = true
                frame.addView(iv, FrameLayout.LayoutParams(w, h))
                bmp = b
                overlay = iv
            }
        } catch (t: Throwable) {
            Log.e(TAG, "snapshot failed", t)
        }
        change()
        rebuildAll()
        val iv = overlay
        val b2 = bmp
        if (iv != null) {
            iv.animate()
                .alpha(0f)
                .setDuration(Motion.SLOW)
                .setInterpolator(Motion.EASE)
                .withEndAction {
                    frame.removeView(iv)
                    b2?.recycle()
                }
                .start()
        }
    }

    private fun rebuildAll() {
        applyChrome()
        navItems.clear()
        navLabels.clear()
        navIcons.clear()
        navHolder.removeAllViews()
        navHolder.addView(buildNav())
        go(tab, false)
    }

    fun go(i: Int, animate: Boolean = true) {
        tab = i
        PowerBlockService.diagOpen = false // a new screen is coming: the Diagnostic card re-enables it if shown
        content.removeAllViews()
        home = null
        val v: View = when (i) {
            0 -> {
                val h = HomeScreen(this)
                home = h
                h.build()
            }
            1 -> ScheduleScreen(this).build()
            2 -> StatsScreen(this).build()
            3 -> SettingsScreen(this).build()
            else -> ProtectionScreen(this).build()
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        refreshNav(animate)
        if (animate) enter(v)
    }

    /** Page transition: the page fades in and its cards rise in one after another. */
    private fun enter(v: View) {
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.EASE).start()
        // Home wraps its ScrollView in a FrameLayout when a wallpaper is set
        val sv = when (v) {
            is ScrollView -> v
            is FrameLayout -> (0 until v.childCount).firstNotNullOfOrNull { v.getChildAt(it) as? ScrollView }
            else -> null
        }
        val col = sv?.getChildAt(0) as? ViewGroup
        if (col != null) staggerIn(col)
    }

    private fun buildNav(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(C.bg)
        val line = View(this)
        line.setBackgroundColor(C.card)
        wrap.addView(line, lp(MATCH, dp(1)))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(8), dp(10), dp(8), dp(10))
        val labels = listOf(
            L.t("Home", "হোম"),
            L.t("Schedule", "সময়সূচি"),
            L.t("Statistics", "পরিসংখ্যান"),
            L.t("Settings", "সেটিংস"),
            L.t("Protection", "সুরক্ষা")
        )
        val kinds = listOf(Ic.HOME, Ic.CAL, Ic.BARS, Ic.GEAR, Ic.SHIELD)
        for (i in 0..4) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.setPadding(0, dp(4), 0, dp(4))
            val ic = IconView(this, kinds[i], C.muted)
            item.addView(ic, lp(dp(26), dp(26)))
            val lb = tv(labels[i], 12f, C.muted, 1)
            lb.gravity = Gravity.CENTER
            item.addView(lb, lp(WRAP, WRAP, t = dp(4)))
            item.setOnClickListener { if (tab != i) go(i) }
            item.pressFeedback()
            navItems.add(item)
            navLabels.add(lb)
            navIcons.add(ic)
            bar.addView(item, lp(0, WRAP, weight = 1f))
        }
        wrap.addView(bar, lp(MATCH, WRAP))
        return wrap
    }

    private fun refreshNav(animate: Boolean) {
        for (i in navItems.indices) {
            val sel = i == tab
            navLabels[i].setTextColor(if (sel) C.primary else C.muted)
            navIcons[i].color = if (sel) C.primary else C.muted
            if (animate && sel && lastNavTab != tab) {
                navIcons[i].scaleX = 0.8f
                navIcons[i].scaleY = 0.8f
                navIcons[i].animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Motion.MED)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
        }
        lastNavTab = tab
    }
}
