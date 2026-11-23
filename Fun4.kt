package com.powerlock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

const val ACTION_CAPSULE_DUE = "com.powerlock.app.CAPSULE_DUE"

// =====================================================================
// TIME CAPSULE
// =====================================================================

/** A letter to the user's future self. Sealed until [open]; [notified] marks the fired notification. */
data class TimeCapsule(
    val id: Long,
    val text: String,
    val created: Long,
    val open: Long,
    val opened: Boolean,
    val notified: Boolean
)

/**
 * Time capsule: write a letter, seal it until a chosen date. The content
 * stays hidden until the open date; on the date a notification fires and
 * the capsule can be opened and read. Stored as a JSON list in prefs.
 */
object TimeCapsules {
    private const val KEY = "time_capsules"
    private const val MAX_CAPSULES = 50
    private const val MAX_TEXT = 1000

    // Capsule ids already notified in this process: if the stored JSON is
    // corrupt, save() refuses to persist notified=true, so without this the
    // same capsule would re-notify on every checkDue call. The persisted
    // flag still governs across restarts.
    private val notifiedThisProcess = HashSet<Long>()

    fun all(ctx: Context): List<TimeCapsule> {
        val out = ArrayList<TimeCapsule>()
        try {
            val arr = JSONArray(Store.settings(ctx).getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    out.add(
                        TimeCapsule(
                            o.getLong("id"), o.getString("t"), o.optLong("c", 0),
                            o.getLong("o"), o.optBoolean("d", false), o.optBoolean("n", false)
                        )
                    )
                } catch (_: Exception) {
                    // one malformed entry: skip it, keep the rest
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capsules read failed", e)
        }
        return out
    }

    private fun save(ctx: Context, list: List<TimeCapsule>) {
        // a corrupt stored list must never be overwritten with a fresh one
        if (JsonGuard.isCorrupt(Store.settings(ctx), KEY)) {
            Log.e(TAG, "capsules corrupt, keeping stored value")
            return
        }
        val arr = JSONArray()
        for (c in list.takeLast(MAX_CAPSULES)) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("t", c.text)
            o.put("c", c.created)
            o.put("o", c.open)
            o.put("d", c.opened)
            o.put("n", c.notified)
            arr.put(o)
        }
        Store.settings(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Seals a new letter. Returns false when the text is empty. */
    fun add(ctx: Context, text: String, openMs: Long): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val now = System.currentTimeMillis()
        val list = all(ctx).toMutableList()
        list.add(TimeCapsule(Store.newId(), clean.take(MAX_TEXT), now, openMs, false, false))
        save(ctx, list)
        rescheduleAlarms(ctx)
        return true
    }

    fun markOpened(ctx: Context, id: Long) {
        save(ctx, all(ctx).map { if (it.id == id) it.copy(opened = true) else it })
        rescheduleAlarms(ctx)
    }

    fun delete(ctx: Context, id: Long) {
        // cancel this capsule's alarm first: once deleted it is no longer
        // in the list, so rescheduleAlarms could not cancel it afterwards
        val c = all(ctx).find { it.id == id }
        if (c != null) {
            try {
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(capsulePending(ctx, c))
            } catch (_: Exception) {
            }
        }
        save(ctx, all(ctx).filter { it.id != id })
        rescheduleAlarms(ctx)
    }

    private fun capsulePending(ctx: Context, c: TimeCapsule): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_CAPSULE_DUE)
            .putExtra("capsule_id", c.id)
        return PendingIntent.getBroadcast(
            ctx, (c.id % Int.MAX_VALUE).toInt(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Arms one alarm per sealed capsule for its open date (re-armed on boot,
     * app open and every capsule change, so the notification fires even when
     * the app is never opened on the day). Idempotent. */
    fun rescheduleAlarms(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            var dueNow = false
            for (c in all(ctx)) {
                val pi = capsulePending(ctx, c)
                am.cancel(pi)
                if (c.opened || c.notified) continue
                if (c.open <= now) {
                    dueNow = true
                    continue
                }
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.open, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.open, pi)
                }
            }
            if (dueNow) checkDue(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "capsule reschedule failed", e)
        }
    }

    fun isDue(c: TimeCapsule, now: Long = System.currentTimeMillis()): Boolean = now >= c.open

    /** "3 days left" / "Opens today" / "Open now!" — never reveals the content. */
    fun countdown(c: TimeCapsule, now: Long = System.currentTimeMillis()): String {
        if (isDue(c, now)) return L.t("Open now!", "এখন খুলুন!")
        val days = ceil((c.open - now).toDouble() / 86_400_000.0).toInt().coerceAtLeast(1)
        return if (days == 1) L.t("Opens today", "আজ খুলবে")
        else L.num(days.toString()) + " " + L.t("days left", "দিন বাকি")
    }

    /** Notifies once per capsule when its date arrives. Called from the open-date
     * alarm, and on app open, boot, and the Home tick as a backstop. */
    fun checkDue(ctx: Context) {
        try {
            val now = System.currentTimeMillis()
            val list = all(ctx)
            var changed = false
            val updated = list.map { c ->
                if (isDue(c, now) && !c.notified && c.id !in notifiedThisProcess) {
                    changed = true
                    notifiedThisProcess.add(c.id)
                    Reminders.show(
                        ctx, 30000 + ((c.id / 1000) % 50).toInt(), // capsule range 30000–30049
                        L.t("Your time capsule opened!", "তোমার টাইম ক্যাপসুল খুলে গেছে!"),
                        L.t("A letter from your past self is ready to read.", "তোমার অতীতের নিজের চিঠি পড়ার সময় হয়েছে।")
                    )
                    c.copy(notified = true)
                } else c
            }
            if (changed) save(ctx, updated)
        } catch (e: Exception) {
            Log.e(TAG, "capsule due check failed", e)
        }
    }
}

// =====================================================================
// SOUND MIXER
// =====================================================================

/**
 * Sound mixer: per-channel volumes (0–100) for the four generated focus
 * sounds, played together during a lock when the "My mix" choice is on.
 * Ocean is only mixable once the lucky wheel has unlocked it.
 */
object SoundMixer {
    val CHANNELS = listOf(FocusSound.WHITE, FocusSound.BROWN, FocusSound.RAIN, FocusSound.OCEAN)

    private fun key(ch: String) = "mix_$ch"

    private fun defaultVol(ch: String): Int = when (ch) {
        FocusSound.WHITE -> 50
        FocusSound.RAIN -> 50
        else -> 0
    }

    /** Stored volumes for all four channels (0–100). */
    fun volumes(ctx: Context): Map<String, Int> {
        val p = Store.settings(ctx)
        return CHANNELS.associateWith { p.getInt(key(it), defaultVol(it)).coerceIn(0, 100) }
    }

    fun save(ctx: Context, vols: Map<String, Int>) {
        val e = Store.settings(ctx).edit()
        for (ch in CHANNELS) e.putInt(key(ch), (vols[ch] ?: 0).coerceIn(0, 100))
        e.apply()
    }

    /** Channels that actually play: volume > 0, Ocean only when unlocked. Volume is a 0–1 AudioTrack gain. */
    fun activeChannels(ctx: Context, override: Map<String, Int>? = null): List<Pair<String, Float>> {
        val vols = override ?: volumes(ctx)
        val out = ArrayList<Pair<String, Float>>()
        for (ch in CHANNELS) {
            val v = (vols[ch] ?: 0).coerceIn(0, 100)
            if (v <= 0) continue
            if (ch == FocusSound.OCEAN && !LuckyWheel.isOceanUnlocked(ctx)) continue
            out.add(ch to (v / 100f) * 0.6f)
        }
        return out
    }

    /** One-line summary for the Settings row ("White 50% · Rain 50%"). */
    fun summary(ctx: Context): String {
        val vols = volumes(ctx)
        val parts = CHANNELS.filter { (vols[it] ?: 0) > 0 && (it != FocusSound.OCEAN || LuckyWheel.isOceanUnlocked(ctx)) }
            .map { FocusSound.label(it) + " " + L.num((vols[it] ?: 0).toString()) + "%" }
        return if (parts.isEmpty()) L.t("No sound in the mix yet", "মিক্সে এখনো কোনো সাউন্ড নেই")
        else parts.joinToString(" · ")
    }
}

// =====================================================================
// VOICE QUOTES (TTS)
// =====================================================================

/**
 * Voice quotes: reads the lock-end quote aloud with the system TextToSpeech
 * engine (no downloads). Prefers the Bengali voice for the Bengali quote;
 * falls back to the English quote when the device has no Bengali voice.
 * TTS is created lazily and shut down from MainActivity.onDestroy.
 */
object VoiceQuotes {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Triple<Context, String, String>? = null

    fun speak(ctx: Context, en: String, bn: String) {
        try {
            if (failed) {
                toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
                return
            }
            if (tts == null) {
                pending = Triple(ctx.applicationContext, en, bn)
                // a stale ready=true from an engine shut down earlier must
                // not survive: the new engine is not ready until its init
                // callback runs, or a rapid second speak() would take the
                // speakNow path on the uninitialized engine
                ready = false
                tts = TextToSpeech(ctx.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ready = true
                        val p = pending
                        pending = null
                        if (p != null) speakNow(p.first, p.second, p.third)
                    } else {
                        failed = true
                        pending = null
                        toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
                    }
                }
                return
            }
            if (!ready) {
                pending = Triple(ctx.applicationContext, en, bn)
                return
            }
            speakNow(ctx.applicationContext, en, bn)
        } catch (e: Exception) {
            Log.e(TAG, "voice quote failed", e)
            toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
        }
    }

    private fun speakNow(ctx: Context, en: String, bn: String) {
        val engine = tts ?: return
        try {
            if (L.bn) {
                val avail = engine.isLanguageAvailable(Locale("bn"))
                if (avail == TextToSpeech.LANG_AVAILABLE ||
                    avail == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    avail == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                ) {
                    engine.language = Locale("bn")
                    engine.speak(bn, TextToSpeech.QUEUE_FLUSH, null, "quote")
                    return
                }
                // no Bengali voice: fall back to English, and say so once
                val p = Store.settings(ctx)
                if (!p.getBoolean("tts_bn_warned", false)) {
                    p.edit().putBoolean("tts_bn_warned", true).apply()
                    toast(ctx, L.t("No Bengali voice on this phone — reading in English", "এই ফোনে বাংলা ভয়েস নেই — ইংরেজিতে শোনাচ্ছি"))
                }
            }
            engine.language = Locale.ENGLISH
            engine.speak(en, TextToSpeech.QUEUE_FLUSH, null, "quote")
        } catch (e: Exception) {
            Log.e(TAG, "voice quote speak failed", e)
            toast(ctx, L.t("Voice is not available on this phone", "এই ফোনে ভয়েস চালু করা যায়নি"))
        }
    }

    /** Stops any speech and releases the engine. Called from MainActivity.onDestroy. */
    fun shutdown() {
        try {
            pending = null
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "voice quote shutdown failed", e)
        }
        tts = null
        ready = false
        failed = false
    }

    private fun toast(ctx: Context, m: String) {
        try {
            android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "voice quote toast failed", e)
        }
    }
}
