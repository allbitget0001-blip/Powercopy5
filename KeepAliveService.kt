package com.powerlock.app

import android.graphics.drawable.Icon
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.Bitmap
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Foreground service: keeps the process alive and shows the countdown notification. */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val finishTask = Runnable { onFinished() }

    /** Writes a fresh timestamp every 60s while alive; the accessibility service revives us if it goes stale. */
    private val heartbeatTask = Runnable { onHeartbeat() }

    private fun onHeartbeat() {
        try {
            Store.lock(this).edit().putLong("kas_heartbeat", System.currentTimeMillis()).apply()
            // backup arrival check for a location-unlock session (cheap, last known fix only)
            PlaceWatcher.checkUnlockLastKnown(this)
            Routine.sweep(this) // a routine task may have become overdue while the app is closed
            Scheduler.onAlarm(this) // a schedule window may have opened (e.g. missed while another lock ran)
            PlaceWatcher.sync(this) // retry the watcher if it never registered (location was off)
            // re-sync the site-block VPN while a lock runs: if it died mid-lock
            // (revoked, another VPN), it comes back within a minute
            if (SiteBlock.isOn(this) && Store.isActive(this)) SiteBlock.sync(this)
            // a DND restore that failed earlier (permission revoked mid-lock)
            // is retried while no lock runs
            if (!Store.isActive(this)) Dnd.restore(this)
            LockWidget.refresh(this) // keep the widget countdown fresh while the app is closed
        } catch (e: Exception) {
            Log.e(TAG, "heartbeat failed", e)
        }
        handler.postDelayed(heartbeatTask, 60_000)
    }

    private val smallIcon: Icon by lazy { buildStatusIcon() }
    private val largeIcon: Bitmap? by lazy { buildLogo() }

    /** Status-bar icon: a shield with a lock cut-out (single colour, the system tints it). */
    private fun buildStatusIcon(): Icon {
        val s = 96
        val u = s / 24f
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.style = Paint.Style.FILL
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        path.moveTo(12f * u, 2f * u)
        path.lineTo(20f * u, 5f * u)
        path.lineTo(20f * u, 11.5f * u)
        path.cubicTo(20f * u, 16.5f * u, 16.5f * u, 20f * u, 12f * u, 22f * u)
        path.cubicTo(7.5f * u, 20f * u, 4f * u, 16.5f * u, 4f * u, 11.5f * u)
        path.lineTo(4f * u, 5f * u)
        path.close()
        // lock body (cut out)
        path.addRect(9f * u, 11f * u, 15f * u, 16.2f * u, Path.Direction.CW)
        // lock shackle (cut out)
        path.moveTo(9.8f * u, 11f * u)
        path.lineTo(9.8f * u, 9.4f * u)
        path.arcTo(RectF(9.8f * u, 7.2f * u, 14.2f * u, 11.6f * u), 180f, 180f)
        path.lineTo(14.2f * u, 11f * u)
        path.lineTo(13f * u, 11f * u)
        path.lineTo(13f * u, 9.4f * u)
        path.arcTo(RectF(11f * u, 8.4f * u, 13f * u, 10.4f * u), 0f, -180f)
        path.lineTo(11f * u, 11f * u)
        path.close()
        c.drawPath(path, p)
        return Icon.createWithBitmap(bmp)
    }

    /**
     * Large icon: the app logo (dark tile, glowing green shield, white padlock), drawn here directly.
     * It does not depend on the launcher icon or on any icon cache of the phone.
     */
    private fun buildLogo(): Bitmap? {
        return try {
            val size = dp(64)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val dark = Color.parseColor("#0B1F16")
            val green = Color.parseColor("#1FCB85")
            val milk = Color.parseColor("#F6F3EA")

            val tile = Paint(Paint.ANTI_ALIAS_FLAG)
            tile.color = dark
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.24f, size * 0.24f, tile)

            // soft glow behind the shield (radial gradient, same as the launcher icon)
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            glowPaint.shader = RadialGradient(
                size / 2f, size / 2f, size * 0.48f,
                intArrayOf(Color.argb(140, 0x1F, 0xCB, 0x85), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(size / 2f, size / 2f, size * 0.48f, glowPaint)

            // same shapes as the launcher icon (108 x 108 viewport), zoomed on the shield
            c.save()
            c.scale(size / 72f, size / 72f)
            c.translate(-18f, -18f)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            fill.style = Paint.Style.FILL
            fill.color = green
            val shield = Path()
            shield.moveTo(54f, 28f)
            shield.lineTo(74f, 36f)
            shield.lineTo(74f, 53f)
            shield.cubicTo(74f, 67f, 64f, 74.5f, 54f, 80f)
            shield.cubicTo(44f, 74.5f, 34f, 67f, 34f, 53f)
            shield.lineTo(34f, 36f)
            shield.close()
            c.drawPath(shield, fill)

            fill.color = milk
            c.drawRoundRect(RectF(44f, 53f, 64f, 67.5f), 2.5f, 2.5f, fill)

            val line = Paint(Paint.ANTI_ALIAS_FLAG)
            line.style = Paint.Style.STROKE
            line.strokeWidth = 2.6f
            line.strokeCap = Paint.Cap.ROUND
            line.color = milk
            val shackle = Path()
            shackle.moveTo(48.5f, 53f)
            shackle.lineTo(48.5f, 49.5f)
            shackle.arcTo(RectF(48.5f, 44f, 59.5f, 55f), 180f, 180f)
            shackle.lineTo(59.5f, 53f)
            c.drawPath(shackle, line)

            fill.color = green
            c.drawCircle(54f, 58.5f, 1.8f, fill)
            c.restore()
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "logo failed", e)
            null
        }
    }

    /** MIUI / HyperOS hint: use the PowerLock logo as the notification's app icon (ignored by other phones). */
    private fun addLogoExtras(b: Notification.Builder) {
        try {
            val bmp = largeIcon ?: return
            val ex = Bundle()
            ex.putParcelable("miui.appIcon", Icon.createWithBitmap(bmp))
            b.addExtras(ex)
        } catch (e: Exception) {
            Log.e(TAG, "logo extras failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diag.init(this) // backstop: App.onCreate already installed the handler
        Diag.crumb("KeepAliveService.onStartCommand start")
        try {
            L.load(this)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("powerlock", "PowerLock 2", NotificationManager.IMPORTANCE_LOW)
            )
            val done = NotificationChannel("powerlock_done", "Session finished", NotificationManager.IMPORTANCE_HIGH)
            done.enableVibration(true)
            nm.createNotificationChannel(done)
            nm.createNotificationChannel(
                NotificationChannel("powerlock_done_quiet", "Session finished (quiet)", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel("powerlock_alert", "Boundary alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
            // go foreground FIRST: refresh() does fallible work (Store,
            // icon bitmaps) before its own startForeground, and any throw
            // would leave the foreground promise unfulfilled -> the system
            // kills the process ~10 s after a startForegroundService start
            startFg(minimalNotification())
            refresh()
            Diag.crumb("refresh done")
            handler.removeCallbacks(heartbeatTask)
            handler.post(heartbeatTask) // fresh heartbeat every 60s so the accessibility service can tell we are alive
            FocusSound.sync(this) // resume the focus sound after a restart if a lock is running
            Diag.crumb("FocusSound.sync done")
            SiteBlock.sync(this) // resume the site-block VPN after a restart if a lock is running
            Diag.crumb("SiteBlock.sync done")
            // location lock no longer uses sessions: end any old location session, the flag takes over
            if (Store.isLocationSession(this)) Store.stopSession(this, force = true)
            PlaceWatcher.sync(this) // resume location watching after a restart/reboot
            Diag.crumb("PlaceWatcher.sync done")
            PlaceWatcher.checkNow(this) // already at a saved place? lock right away
            Diag.crumb("PlaceWatcher.checkNow called")
            PlaceWatcher.checkUnlockNow(this) // location-unlock session? check arrival right away
            Diag.crumb("PlaceWatcher.checkUnlockNow called")
            Watchdog.enqueue(this) // safety net: restart the service every 15 min if it dies
            Diag.crumb("onStartCommand end")
        } catch (e: Exception) {
            Log.e(TAG, "KeepAliveService start failed", e)
            Diag.crumb("onStartCommand FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
        return START_STICKY
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** Minimal placeholder notification (app name only): refresh() replaces
     * its content at once. Built without the lazy icons, so it cannot throw. */
    private fun minimalNotification(): Notification {
        return Notification.Builder(this, "powerlock")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    /**
     * Goes foreground with the right type: on Android 14+ a location-type
     * start throws SecurityException when location is not usable right now
     * (no permission, or while-in-use only while the app is in the
     * background), and the unfulfilled foreground promise kills the process
     * ~10 s later. Fall back to specialUse, which never needs location.
     * (A plain permission check cannot decide this: checkSelfPermission
     * returns GRANTED even in the background.)
     */
    private fun startFg(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (hasUsableLocation()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                startForeground(1, notification, type)
                Diag.crumb("startFg ok type=$type")
            } catch (e: SecurityException) {
                Log.w(TAG, "location-type foreground start refused, retrying specialUse", e)
                Diag.crumb("startFg location refused, retrying specialUse")
                // build 70: the fallback used to throw uncaught out of
                // startFg, leaving the foreground promise unfulfilled with
                // no record of why — now a failure is recorded in the trail
                // before it propagates to onStartCommand's catch
                try {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    Diag.crumb("startFg fallback ok specialUse")
                } catch (e2: Exception) {
                    Log.e(TAG, "specialUse foreground start failed", e2)
                    Diag.crumb("startFg fallback FAILED: ${e2.javaClass.simpleName}: ${e2.message}")
                    throw e2
                }
            }
        } else {
            startForeground(1, notification)
            Diag.crumb("startFg ok (legacy)")
        }
    }

    /** Location is usable for a location-type foreground service: granted, and
     * either background location is granted or the app is in the foreground. */
    private fun hasUsableLocation(): Boolean {
        return Perm.locationOn(this) && (Perm.backgroundLocationOn(this) || MainActivity.isForeground)
    }

    private fun refresh() {
        handler.removeCallbacks(finishTask)
        Store.finalizeIfExpired(this)
        val now = System.currentTimeMillis()
        val active = Store.isActive(this)
        // the real end time: monotonic remaining + the current wall clock, so
        // the countdown and the finish task stay right after a date/time change
        val end = now + Store.remainingMs(this).coerceAtLeast(0)

        val b = Notification.Builder(this, "powerlock")
            .setSmallIcon(smallIcon)
            .setLargeIcon(largeIcon)
            .setColor(Color.parseColor("#16C784"))
            .setOngoing(true)
            .setContentIntent(openApp())
        addLogoExtras(b)

        if (active) {
            val mode = Store.unlockMode(this)
            val target = Store.unlockTarget(this)
            b.setContentTitle(L.t("Protection active", "প্রটেকশন সক্রিয়"))
            when {
                mode == UNLOCK_LOC && target != null -> {
                    // no countdown: arrival ends it (the 15-day end is only a safety cap)
                    b.setContentText(L.t("Opens at ${target.name}", "${target.name} পৌঁছালে খুলবে"))
                }
                mode == UNLOCK_EITHER && target != null -> {
                    b.setContentText(L.t("Ends at ", "শেষ হবে ") + fmtClock(end) + L.t(" or at ${target.name}", " অথবা ${target.name} পৌঁছালে"))
                        .setUsesChronometer(true)
                        .setChronometerCountDown(true)
                        .setShowWhen(true)
                        .setWhen(end)
                }
                else -> {
                    b.setContentText(L.t("Ends at ", "শেষ হবে ") + fmtClock(end))
                        .setUsesChronometer(true)
                        .setChronometerCountDown(true)
                        .setShowWhen(true)
                        .setWhen(end)
                }
            }
            handler.postDelayed(finishTask, end - now)
        } else {
            b.setContentTitle(L.t("PowerLock 2 is ready", "PowerLock 2 প্রস্তুত"))
                .setContentText(L.t("Protection is off", "প্রটেকশন বন্ধ আছে"))
        }
        startFg(b.build())
        LockWidget.refresh(this) // the notification and the widget show the same state
    }

    private fun onFinished() {
        try {
            L.load(this)
            if (Store.isActive(this)) { // clock changed, not finished yet
                refresh()
                return
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = if (Store.soundVib(this)) "powerlock_done" else "powerlock_done_quiet"
            val nb = Notification.Builder(this, channel)
                .setSmallIcon(smallIcon)
                .setLargeIcon(largeIcon)
                .setColor(Color.parseColor("#16C784"))
                .setContentTitle(L.t("Protection finished", "প্রটেকশন শেষ"))
                .setContentText(L.t("You can use the power menu now.", "এখন পাওয়ার মেনু ব্যবহার করতে পারবেন।"))
                .setAutoCancel(true)
                .setContentIntent(openApp())
            addLogoExtras(nb)
            nm.notify(2, nb.build())
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "onFinished failed", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Diag.crumb("KeepAliveService.onTaskRemoved (swiped from Recents)")
        try {
            // swiped away from Recents: restart so the lock keeps working
            try {
                startForegroundService(Intent(this, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "onTaskRemoved start failed", e)
            }
            scheduleRestart(500)
        } catch (e: Exception) {
            Log.e(TAG, "onTaskRemoved restart failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Restart via an alarm-clock alarm: unlike setExactAndAllowWhileIdle it needs NO
     * "exact alarms" permission, is always exact, and the system lets the alarm start a
     * foreground service from the background when it fires. This is the restart that
     * survives a Recents swipe even when the user never granted exact alarms.
     * Falls back to the exact/inexact alarm when setAlarmClock throws.
     */
    private fun scheduleRestart(delayMs: Long) {
        try {
            val intent = Intent(this, KeepAliveService::class.java)
            val pi = PendingIntent.getService(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val at = System.currentTimeMillis() + delayMs
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openApp()), pi)
                return
            } catch (e: Exception) {
                Log.e(TAG, "setAlarmClock failed, falling back", e)
            }
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "scheduleRestart failed", e)
        }
    }

    companion object {
        /** Any component (Watchdog, accessibility service, receiver) can ask for a restart. */
        fun restartSoon(ctx: Context, delayMs: Long) {
            try {
                val intent = Intent(ctx, KeepAliveService::class.java)
                val pi = PendingIntent.getService(
                    ctx, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val show = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val at = System.currentTimeMillis() + delayMs
                try {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                } catch (e: Exception) {
                    Log.e(TAG, "restartSoon alarm-clock failed", e)
                    if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "restartSoon failed", e)
            }
        }
    }

    override fun onDestroy() {
        Diag.crumb("KeepAliveService.onDestroy")
        handler.removeCallbacks(finishTask)
        handler.removeCallbacks(heartbeatTask)
        try {
            // system stopped the service (e.g. notification dismissed): come back
            scheduleRestart(2000)
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy restart failed", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
