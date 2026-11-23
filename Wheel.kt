package com.powerlock.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// =====================================================================
// STREAK PROTECTION
// =====================================================================

/**
 * Streak protection tokens, won from the lucky wheel. A token is consumed
 * automatically when a streak day would otherwise be missed: the check runs
 * once a day (from the Home tick) and protects YESTERDAY when yesterday had
 * no focus / an unfinished routine but the streak before it was alive.
 * Protected days count as complete in both streak computations.
 */
object StreakProtection {
    fun tokens(ctx: Context): Int = Store.lock(ctx).getInt("streak_tokens", 0)

    @Synchronized
    fun addToken(ctx: Context) {
        val p = Store.lock(ctx)
        p.edit().putInt("streak_tokens", tokens(ctx) + 1).apply()
    }

    private fun protectedSet(ctx: Context, key: String): Set<String> {
        val raw = Store.lock(ctx).getString(key, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun protect(ctx: Context, key: String, dayKey: String) {
        val set = protectedSet(ctx, key).toMutableSet()
        set.add(dayKey)
        Store.lock(ctx).edit().putString(key, set.joinToString(",")).apply()
    }

    fun isFocusProtected(ctx: Context, dayKey: String): Boolean =
        dayKey in protectedSet(ctx, "streak_prot_focus")

    fun isRoutineProtected(ctx: Context, dayKey: String): Boolean =
        dayKey in protectedSet(ctx, "streak_prot_routine")

    private fun consume(ctx: Context): Boolean {
        val t = tokens(ctx)
        if (t <= 0) return false
        Store.lock(ctx).edit().putInt("streak_tokens", t - 1).apply()
        return true
    }

    /** Focus streak ending on the day [ago] days ago (protected days count). */
    fun focusStreakEnding(ctx: Context, ago: Int): Int {
        val map = Store.focusDayMap(ctx)
        var streak = 0
        var i = ago
        while (i < 90) {
            val key = Store.dayKey(i)
            if ((map[key] ?: 0) > 0 || isFocusProtected(ctx, key)) {
                streak++
                i++
            } else break
        }
        return streak
    }

    /** Routine streak ending on the day [ago] days ago (protected days count). */
    fun routineStreakEnding(ctx: Context, ago: Int): Int {
        var streak = 0
        var i = ago
        while (i < 90) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val tasks = Routine.tasksForDay(ctx, dayIdx(c))
            if (tasks.isEmpty()) {
                i++
                continue
            }
            val key = Store.dayKey(i)
            val done = Routine.doneIds(ctx, key)
            if (tasks.all { it.id in done } || isRoutineProtected(ctx, key)) {
                streak++
                i++
            } else break
        }
        return streak
    }

    /** Once-a-day check: protect yesterday when a live streak would break. */
    fun check(ctx: Context) {
        try {
            val p = Store.lock(ctx)
            val today = Store.dayKey(0)
            if (p.getString("streak_check_day", "") == today) return
            // check for a token BEFORE marking the day: stamping first would
            // burn the day when no token exists yet, so a token earned later
            // the same day could never save the streak
            if (tokens(ctx) <= 0) return
            p.edit().putString("streak_check_day", today).apply()
            val yest = Store.dayKey(1)
            // focus streak: yesterday had no focus, but the streak before it was alive
            val focusMap = Store.focusDayMap(ctx)
            if ((focusMap[yest] ?: 0) <= 0 && !isFocusProtected(ctx, yest) &&
                focusStreakEnding(ctx, 2) > 0 && consume(ctx)
            ) {
                protect(ctx, "streak_prot_focus", yest)
            }
            // routine streak: yesterday had tasks and not all were done
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -1)
            val tasks = Routine.tasksForDay(ctx, dayIdx(c))
            if (tasks.isNotEmpty() && tokens(ctx) > 0 && !isRoutineProtected(ctx, yest)) {
                val done = Routine.doneIds(ctx, yest)
                if (!tasks.all { it.id in done } && routineStreakEnding(ctx, 2) > 0 && consume(ctx)) {
                    protect(ctx, "streak_prot_routine", yest)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "streak protection check failed", e)
        }
    }
}

// =====================================================================
// LUCKY WHEEL
// =====================================================================

data class WheelSeg(val label: String, val color: Int)

data class RewardDef(
    val id: String, val weight: Int,
    val labelEn: String, val labelBn: String, val color: String
)

/**
 * Lucky wheel: +1 spin per completed lock, +1 bonus spin when the day's
 * routine is fully complete. Rewards are FUN things only — never break
 * minutes. Each reward actually applies when won (unlock stored, token
 * added, badge shown). Exhausted rewards drop out of the wheel; the token
 * segment always remains, so the wheel is never empty.
 */
object LuckyWheel {
    private val REWARDS = listOf(
        RewardDef("species", 12, "Tree", "গাছ", "#8A5A3B"),
        RewardDef("animal", 12, "Animal", "প্রাণী", "#FF9F43"),
        RewardDef("theme", 10, "Colour", "রঙ", "#B07CFF"),
        RewardDef("token", 10, "Token", "টোকেন", "#FFD54F"),
        RewardDef("badge", 6, "Badge", "ব্যাজ", "#F48FB1"),
        RewardDef("ocean", 5, "Ocean", "সাগর", "#1E88E5")
    )

    fun spins(ctx: Context): Int = Store.lock(ctx).getInt("wheel_spins", 0)

    @Synchronized
    private fun addSpins(ctx: Context, n: Int) {
        val p = Store.lock(ctx)
        p.edit().putInt("wheel_spins", spins(ctx) + n).apply()
    }

    /** Called from Store.addSession for every completed lock. */
    fun onLockCompleted(ctx: Context) = addSpins(ctx, 1)

    /** Called when a routine check-off completes the whole day (once a day). */
    fun onRoutineComplete(ctx: Context) {
        val p = Store.lock(ctx)
        val today = Store.dayKey(0)
        if (p.getString("wheel_routine_day", "") == today) return
        val (done, total) = Routine.todaySummary(ctx)
        if (total <= 0 || done != total) return
        p.edit().putString("wheel_routine_day", today).apply()
        addSpins(ctx, 1)
    }

    // ----- wheel-only unlocks -----

    /** Wheel accent indices (5..7) the user has won. Base accents 0..4 are always open. */
    fun isThemeUnlocked(ctx: Context, idx: Int): Boolean {
        if (idx < C.ACCENTS.size) return true
        val raw = Store.lock(ctx).getString("wheel_themes", "") ?: ""
        return idx.toString() in raw.split(",").map { it.trim() }
    }

    private fun unlockTheme(ctx: Context): Int? {
        val locked = (C.ACCENTS.size until C.ACCENTS.size + C.WHEEL_ACCENTS.size).filter { !isThemeUnlocked(ctx, it) }
        if (locked.isEmpty()) return null
        val pick = locked[Random.nextInt(locked.size)]
        val raw = Store.lock(ctx).getString("wheel_themes", "") ?: ""
        val set = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        set.add(pick.toString())
        Store.lock(ctx).edit().putString("wheel_themes", set.joinToString(",")).apply()
        return pick
    }

    fun isOceanUnlocked(ctx: Context): Boolean =
        Store.lock(ctx).getBoolean("ocean_unlocked", false)

    private fun unlockOcean(ctx: Context) {
        Store.lock(ctx).edit().putBoolean("ocean_unlocked", true).apply()
    }

    // ----- rolling -----

    private fun isAvailable(ctx: Context, id: String): Boolean = when (id) {
        "species" -> TreeSpecies.locked(ctx).isNotEmpty()
        "animal" -> GardenAnimals.locked(ctx).isNotEmpty()
        "theme" -> (C.ACCENTS.size until C.ACCENTS.size + C.WHEEL_ACCENTS.size).any { !isThemeUnlocked(ctx, it) }
        "badge" -> !Badges.earned(ctx).containsKey("wheel_lucky")
        "ocean" -> !isOceanUnlocked(ctx)
        else -> true // token is always available
    }

    fun available(ctx: Context): List<RewardDef> = REWARDS.filter { isAvailable(ctx, it.id) }

    private fun roll(ctx: Context): RewardDef {
        val avail = available(ctx)
        val total = avail.sumOf { it.weight }
        var r = Random.nextInt(total)
        for (d in avail) {
            if (r < d.weight) return d
            r -= d.weight
        }
        return avail.first()
    }

    /**
     * Applies a won reward and returns the bilingual result text for the
     * result dialog. Also awards the wheel badges (first win, collector).
     */
    fun applyReward(ctx: Context, id: String): String {
        val p = Store.lock(ctx)
        val wins = p.getInt("wheel_wins", 0)
        p.edit().putInt("wheel_wins", wins + 1).apply()
        if (wins == 0) Badges.award(ctx, "wheel_first")
        val text = when (id) {
            "species" -> {
                val sp = TreeSpecies.unlockFromWheel(ctx)
                if (sp != null) {
                    p.edit().putBoolean("wheel_won_species", true).apply()
                    checkCollector(ctx)
                    L.t(
                        "New tree species: ${sp.nameEn}! Tap the tree card to choose it.",
                        "নতুন গাছের প্রজাতি: ${sp.nameBn}! বাছতে গাছের কার্ডে চাপুন।"
                    )
                } else {
                    // exhausted after all (race-proof fallback): a token instead
                    StreakProtection.addToken(ctx)
                    L.t("Streak protection +1 instead (all species already owned)", "এর বদলে স্ট্রিক সুরক্ষা +১ (সব প্রজাতি আগেই আছে)")
                }
            }
            "animal" -> {
                val an = GardenAnimals.unlockFromWheel(ctx)
                if (an != null) {
                    p.edit().putBoolean("wheel_won_animal", true).apply()
                    checkCollector(ctx)
                    L.t(
                        "A new friend joined your garden: ${an.nameEn}!",
                        "বাগানে নতুন বন্ধু এসেছে: ${an.nameBn}!"
                    )
                } else {
                    StreakProtection.addToken(ctx)
                    L.t("Streak protection +1 instead (all animals already owned)", "এর বদলে স্ট্রিক সুরক্ষা +১ (সব প্রাণী আগেই আছে)")
                }
            }
            "theme" -> {
                val idx = unlockTheme(ctx)
                if (idx != null) {
                    val name = C.WHEEL_ACCENT_NAMES[idx - C.ACCENTS.size]
                    L.t(
                        "New accent colour unlocked: ${name.first}! Pick it in Settings → Theme.",
                        "নতুন অ্যাকসেন্ট রঙ খুলেছে: ${name.second}! সেটিংস → থিম থেকে বাছুন।"
                    )
                } else {
                    StreakProtection.addToken(ctx)
                    L.t("Streak protection +1 instead (all colours already owned)", "এর বদলে স্ট্রিক সুরক্ষা +১ (সব রঙ আগেই আছে)")
                }
            }
            "token" -> {
                StreakProtection.addToken(ctx)
                L.t(
                    "Streak protection +1! You now have ${StreakProtection.tokens(ctx)} token(s).",
                    "স্ট্রিক সুরক্ষা +১! এখন তোমার ${L.num(StreakProtection.tokens(ctx).toString())}টি টোকেন আছে।"
                )
            }
            "badge" -> {
                Badges.award(ctx, "wheel_lucky")
                L.t("New badge: Lucky! See it in Statistics.", "নতুন ব্যাজ: ভাগ্যবান! পরিসংখ্যানে দেখুন।")
            }
            "ocean" -> {
                unlockOcean(ctx)
                L.t(
                    "New focus sound: Ocean waves! Pick it in Settings → Focus sounds.",
                    "নতুন ফোকাস সাউন্ড: সমুদ্রের ঢেউ! সেটিংস → ফোকাস সাউন্ড থেকে বাছুন।"
                )
            }
            else -> L.t("You won a prize!", "তুমি পুরস্কার জিতেছো!")
        }
        return text
    }

    /** "Collector" badge: won a species AND an animal from the wheel. */
    private fun checkCollector(ctx: Context) {
        val p = Store.lock(ctx)
        if (p.getBoolean("wheel_won_species", false) && p.getBoolean("wheel_won_animal", false)) {
            Badges.award(ctx, "wheel_collector")
        }
    }

    // ----- the wheel dialog -----

    /** Opens the wheel when spins are available; [onDone] refreshes the UI after a spin. */
    fun showWheel(a: MainActivity, onDone: () -> Unit) {
        // reconcile a spin that was paid for but killed mid-animation: its
        // reward was recorded before the spin started, so apply it once now
        // (cleared with commit() BEFORE applying, so a second kill here
        // can't double-apply it) and show the result instead of the wheel
        val pending = Store.lock(a).getString("wheel_pending_reward", null)
        if (!pending.isNullOrEmpty()) {
            Store.lock(a).edit().remove("wheel_pending_reward").commit()
            val pendingText = applyReward(a, pending)
            a.dlg()
                .setTitle(L.t("You won!", "তুমি জিতেছো!"))
                .setMessage(pendingText)
                .setPositiveButton(L.t("Great!", "ভালো হয়েছে!")) { _, _ -> onDone() }
                .show()
            return
        }
        if (spins(a) <= 0) {
            a.toast(L.t("No spins left — complete a lock to earn one!", "স্পিন নেই — একটা লক সম্পন্ন করে স্পিন পান!"))
            return
        }
        val avail = available(a)
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        val wheel = WheelView(a)
        wheel.segs = avail.map { WheelSeg(L.t(it.labelEn, it.labelBn), Color.parseColor(it.color)) }
        root.addView(wheel, lp(MATCH, a.dp(300)))
        val countText = a.tv(
            L.t("Spins left: ", "স্পিন বাকি: ") + L.num(spins(a).toString()),
            14f, C.muted
        )
        countText.gravity = Gravity.CENTER
        root.addView(countText, lp(MATCH, WRAP, t = a.dp(8)))
        val spinBtn = a.primaryBtn(L.t("SPIN", "ঘোরাও"))
        root.addView(spinBtn, lp(MATCH, a.dp(52), t = a.dp(12)))
        val dlg = a.dlg()
            .setTitle(L.t("Lucky wheel", "লাকি হুইল"))
            .setView(root)
            .setNegativeButton(L.t("Close", "বন্ধ"), null)
            .show()
        spinBtn.setOnClickListener {
            if (spins(a) <= 0) {
                a.toast(L.t("No spins left", "স্পিন নেই"))
                return@setOnClickListener
            }
            spinBtn.isEnabled = false
            spinBtn.alpha = 0.5f
            // the spin is paid for now: lock the dialog until it lands, so a
            // mid-spin dismiss can't eat the spin (the reward is applied in
            // the completion callback, which also dismisses the dialog)
            dlg.setCancelable(false)
            dlg.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = false
            Store.lock(a).edit().putInt("wheel_spins", spins(a) - 1).apply()
            countText.text = L.t("Spins left: ", "স্পিন বাকি: ") + L.num(spins(a).toString())
            val won = roll(a)
            // record the won reward BEFORE animating (commit, not apply, so
            // it survives a process kill mid-spin): the reward is applied in
            // the completion callback, or reconciled on the next showWheel
            Store.lock(a).edit().putString("wheel_pending_reward", won.id).commit()
            val winIdx = avail.indexOf(won).coerceAtLeast(0)
            wheel.spinTo(winIdx) {
                try {
                    dlg.dismiss()
                } catch (_: Exception) {
                }
                // clear the pending record BEFORE applying, so the reward
                // can never be applied twice (see the showWheel reconcile)
                Store.lock(a).edit().remove("wheel_pending_reward").commit()
                val text = applyReward(a, won.id)
                // the spin may have ended via cancel after the view detached
                // (activity going away): the reward is already applied, so
                // just skip the dialog instead of crashing on a dead window
                if (a.isFinishing || a.isDestroyed) return@spinTo
                a.dlg()
                    .setTitle(L.t("You won!", "তুমি জিতেছো!"))
                    .setMessage(text)
                    .setPositiveButton(L.t("Great!", "ভালো হয়েছে!")) { _, _ -> onDone() }
                    .show()
            }
        }
    }
}

/** A spin wheel: coloured segments with labels, a pointer at the top, and a
 * decelerating spin animation that lands on the chosen segment. */
class WheelView(ctx: Context) : View(ctx) {
    var segs: List<WheelSeg> = emptyList()
    private var rotationDeg = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null

    /** Spins (5 full turns + landing offset) and calls [onEnd] when it stops. */
    fun spinTo(index: Int, onEnd: () -> Unit) {
        if (segs.isEmpty()) {
            onEnd()
            return
        }
        val seg = 360f / segs.size
        val center = (index + 0.5f) * seg
        val from = rotationDeg
        // the pointer sits at the top (270°); rotate so the winning centre lands there
        // (offset must account for the current rotation, or a second spin
        // on the same view lands on the wrong segment)
        val offset = ((270f - center - from) % 360f + 360f) % 360f
        val jitter = (Random.nextFloat() - 0.5f) * seg * 0.5f
        val to = from + 360f * 5 + offset + jitter
        anim?.cancel()
        val va = ValueAnimator.ofFloat(from, to)
        va.duration = 4500L
        va.interpolator = DecelerateInterpolator()
        va.addUpdateListener {
            rotationDeg = it.animatedValue as Float
            invalidate()
        }
        va.addListener(object : AnimatorListenerAdapter() {
            // end and cancel are mutually exclusive for one animation, and
            // the flag guards anyway: the reward must apply exactly once,
            // even if the view is detached (animator cancelled) mid-spin
            private var done = false
            private fun finish() {
                if (done) return
                done = true
                rotationDeg %= 360f
                onEnd()
            }

            override fun onAnimationEnd(animation: Animator) {
                finish()
            }

            override fun onAnimationCancel(animation: Animator) {
                finish()
            }
        })
        anim = va
        va.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || segs.isEmpty()) return
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f - 8f).coerceAtLeast(10f)
        val seg = 360f / segs.size
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.save()
        canvas.rotate(rotationDeg, cx, cy)
        paint.style = Paint.Style.FILL
        for (i in segs.indices) {
            paint.color = segs[i].color
            canvas.drawArc(rect, i * seg, seg, true, paint)
        }
        // divider lines
        paint.color = Color.parseColor("#0A1510")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        for (i in segs.indices) {
            val a = Math.toRadians((i * seg).toDouble())
            canvas.drawLine(cx, cy, cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat(), paint)
        }
        // labels: upright text at each segment's middle radius
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = (radius * 0.13f).coerceAtLeast(24f)
        paint.textAlign = Paint.Align.CENTER
        for (i in segs.indices) {
            val mid = Math.toRadians(((i + 0.5f) * seg).toDouble())
            val lx = cx + radius * 0.62f * cos(mid).toFloat()
            val ly = cy + radius * 0.62f * sin(mid).toFloat() + paint.textSize * 0.35f
            canvas.drawText(segs[i].label, lx, ly, paint)
        }
        canvas.restore()

        // pointer: a triangle at the top, pointing down at the wheel
        paint.style = Paint.Style.FILL
        paint.color = C.danger
        val ptr = Path()
        ptr.moveTo(cx - 22f, 4f)
        ptr.lineTo(cx + 22f, 4f)
        ptr.lineTo(cx, 44f)
        ptr.close()
        canvas.drawPath(ptr, paint)
        // hub
        paint.color = C.card2
        canvas.drawCircle(cx, cy, radius * 0.14f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = C.muted
        canvas.drawCircle(cx, cy, radius * 0.14f, paint)
    }
}
