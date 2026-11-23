package com.powerlock.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.concurrent.thread

/**
 * Focus sounds: calming noise generated in code (no downloads, no audio assets).
 * White / Brown / Rain, played through an AudioTrack while a lock runs.
 * Auto-start never fires when the phone is on silent/vibrate — only an explicit
 * user choice starts playback then.
 */
object FocusSound {
    const val OFF = "off"
    const val WHITE = "white"
    const val BROWN = "brown"
    const val RAIN = "rain"
    const val OCEAN = "ocean" // wheel-only: unlocked from the lucky wheel
    const val MIX = "mix" // the user's own mix from the sound mixer

    fun choice(ctx: Context): String = Store.settings(ctx).getString("focus_sound", OFF) ?: OFF

    fun setChoice(ctx: Context, c: String) {
        Store.settings(ctx).edit().putString("focus_sound", c).apply()
    }

    fun label(c: String): String = when (c) {
        WHITE -> L.t("White noise", "হোয়াইট নয়েজ")
        BROWN -> L.t("Brown noise", "ব্রাউন নয়েজ")
        RAIN -> L.t("Rain", "বৃষ্টি")
        OCEAN -> L.t("Ocean waves", "সমুদ্রের ঢেউ")
        MIX -> L.t("My mix", "আমার মিক্স")
        else -> L.t("Off", "বন্ধ")
    }

    private const val RATE = 22050
    // one track + one generator thread per playing channel (a mix plays up to 4)
    private var tracks = ArrayList<AudioTrack>()
    private var workers = ArrayList<Thread>()
    private var appCtx: Context? = null
    private var focusReq: AudioFocusRequest? = null
    @Volatile private var running = false
    @Volatile private var wantsPlay = false
    private var currentChoice = OFF

    /** Called on lock start/end and on service restart: play iff a lock runs and a sound is chosen. */
    fun sync(ctx: Context) {
        val toJoin = ArrayList<Thread>()
        synchronized(this) {
            if (Store.isActive(ctx) && choice(ctx) != OFF) startInternal(ctx, explicit = false, toJoin = toJoin)
            else {
                wantsPlay = false
                toJoin.addAll(halt())
            }
        }
        joinAsync(toJoin)
    }

    /** The user picked a sound in Settings: start right away even on silent/vibrate. */
    fun startExplicit(ctx: Context) {
        val toJoin = ArrayList<Thread>()
        synchronized(this) {
            if (choice(ctx) != OFF) startInternal(ctx, explicit = true, toJoin = toJoin)
        }
        joinAsync(toJoin)
    }

    /** Mixer preview: plays the given (unsaved) volumes right away, even without a lock. */
    fun previewMix(ctx: Context, vols: Map<String, Int>) {
        val toJoin = ArrayList<Thread>()
        synchronized(this) {
            if (SoundMixer.activeChannels(ctx, vols).isEmpty()) {
                wantsPlay = false
                toJoin.addAll(halt())
            } else {
                startInternal(ctx, explicit = true, forceChoice = MIX, forceVols = vols, toJoin = toJoin)
            }
        }
        joinAsync(toJoin)
    }

    fun stop() {
        val toJoin: List<Thread>
        synchronized(this) {
            wantsPlay = false
            toJoin = halt()
        }
        joinAsync(toJoin)
    }

    /** Stops playback but keeps [wantsPlay], so an audio-focus regain can resume.
     * Returns the worker threads to join — the caller joins them AFTER
     * releasing the monitor (see [joinAsync]), so a stop never blocks the
     * main thread or holds the lock while workers exit.
     * [keepFocus] stops the tracks but keeps the focus request registered,
     * so AUDIOFOCUS_GAIN still arrives after a transient loss. */
    private fun halt(keepFocus: Boolean = false): List<Thread> {
        running = false
        val toJoin = ArrayList(workers)
        for (w in toJoin) w.interrupt()
        workers.clear()
        for (tr in tracks) {
            try {
                tr.stop()
                tr.release()
            } catch (_: Exception) {
            }
        }
        tracks.clear()
        currentChoice = OFF
        if (!keepFocus) {
            try {
                val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val req = focusReq
                if (am != null && req != null) am.abandonAudioFocusRequest(req)
            } catch (_: Exception) {
            }
            focusReq = null
        }
        return toJoin
    }

    /** Joins worker threads on a background thread: the old workers exit
     * promptly (running=false + the track was stopped), and nothing here
     * needs to wait for them — the next generation writes to new tracks. */
    private fun joinAsync(list: List<Thread>) {
        if (list.isEmpty()) return
        thread(name = "focus-sound-join", isDaemon = true) {
            for (w in list) {
                try {
                    w.join(500)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Starts playback. [forceChoice]/[forceVols] are for the mixer preview
     * (unsaved volumes, even without a lock); otherwise the stored choice
     * and mix are used. A mix starts one track per active channel.
     */
    private fun startInternal(ctx: Context, explicit: Boolean, forceChoice: String? = null, forceVols: Map<String, Int>? = null, toJoin: MutableList<Thread>) {
        val ch = forceChoice ?: choice(ctx)
        if (ch == OFF) {
            wantsPlay = false
            toJoin.addAll(halt())
            return
        }
        // the channels to play: the mix's active channels, or the single chosen sound
        val channels: List<Pair<String, Float>> = if (ch == MIX) {
            SoundMixer.activeChannels(ctx, forceVols)
        } else {
            listOf(ch to 0.6f)
        }
        if (channels.isEmpty()) {
            wantsPlay = false
            toJoin.addAll(halt())
            return
        }
        if (forceChoice == null && forceVols == null && running && currentChoice == ch) {
            wantsPlay = true
            return
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // never auto-play on silent/vibrate; an explicit user choice may
        if (!explicit && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        appCtx = ctx.applicationContext
        wantsPlay = true
        toJoin.addAll(halt())
        // audio focus: another app's audio pauses us; we resume when it ends
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener({ change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            val tj: List<Thread>
                            synchronized(this) { tj = halt() }
                            joinAsync(tj)
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // transient loss: stop the tracks but keep the
                            // focus request, so AUDIOFOCUS_GAIN still
                            // arrives and the resume branch below runs
                            val tj: List<Thread>
                            synchronized(this) { tj = halt(keepFocus = true) }
                            joinAsync(tj)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            val c = appCtx
                            if (c != null && wantsPlay && Store.isActive(c)) {
                                val tj = ArrayList<Thread>()
                                synchronized(this) { startInternal(c, explicit = true, toJoin = tj) }
                                joinAsync(tj)
                            }
                        }
                    }
                }, Handler(Looper.getMainLooper()))
                .build()
            if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                wantsPlay = false
                return
            }
            focusReq = req
        } catch (e: Exception) {
            Log.e(TAG, "focus sound: audio focus failed", e)
            wantsPlay = false
            return
        }
        try {
            val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = (minBuf * 2).coerceAtLeast(RATE / 2)
            for ((sound, vol) in channels) {
                val tr = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                tr.setVolume(vol)
                tr.play()
                tracks.add(tr)
            }
            currentChoice = ch
            running = true
            for ((i, soundVol) in channels.withIndex()) {
                val w = Thread { writeLoop(soundVol.first, tracks[i]) }
                workers.add(w)
                w.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "focus sound: start failed", e)
            wantsPlay = false
            toJoin.addAll(halt())
        }
    }

    /** Generates the chosen noise in chunks and streams it to the track. */
    private fun writeLoop(ch: String, tr: AudioTrack) {
        val chunk = ShortArray(2048)
        val rnd = Random(System.currentTimeMillis())
        var brownLast = 0f
        var lp = 0f
        var phase = 0.0
        while (running) {
            try {
                for (i in chunk.indices) {
                    val w = rnd.nextFloat() * 2f - 1f
                    val v = when (ch) {
                        BROWN -> {
                            brownLast = (brownLast + 0.02f * w) / 1.02f
                            (brownLast * 3.5f).coerceIn(-1f, 1f)
                        }
                        RAIN -> {
                            // low-passed noise + a slow amplitude wobble = rain-like patter
                            lp += 0.12f * (w - lp)
                            phase += 2.0 * PI * 0.09 / RATE
                            val lfo = 0.75f + 0.25f * sin(phase).toFloat()
                            (lp * 2.2f * lfo).coerceIn(-1f, 1f)
                        }
                        OCEAN -> {
                            // deep low-passed noise + a slow swell (waves rolling in)
                            lp += 0.06f * (w - lp)
                            phase += 2.0 * PI * 0.08 / RATE
                            val swell = 0.55f + 0.45f * sin(phase).toFloat()
                            (lp * 3.0f * swell).coerceIn(-1f, 1f)
                        }
                        else -> w * 0.5f // white, gentler
                    }
                    chunk[i] = (v * 12000f).toInt().coerceIn(-32768, 32767).toShort()
                }
                if (!running) break
                val written = tr.write(chunk, 0, chunk.size)
                if (written < 0) {
                    // a dead track returns an error instead of throwing: stop
                    // generating into it instead of busy-looping at full CPU
                    Log.e(TAG, "focus sound: write failed ($written)")
                    // reset state so the next startInternal revives playback
                    // instead of early-returning on a stale running=true
                    synchronized(this@FocusSound) { running = false; currentChoice = OFF }
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "focus sound: write failed", e)
                synchronized(this@FocusSound) { running = false; currentChoice = OFF }
                break
            }
        }
    }
}
