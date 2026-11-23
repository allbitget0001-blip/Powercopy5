package com.powerlock.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Calendar
import kotlin.random.Random

// =====================================================================
// FOCUS JOURNEY
// =====================================================================

/** One stop on the Dhaka → Cox's Bazar route. */
data class Milestone(val km: Int, val nameEn: String, val nameBn: String)

/**
 * Focus journey: lifetime focus minutes move the user along the Dhaka →
 * Cox's Bazar route (400 km, 1 focus minute = 1 km). Progress derives from
 * the existing lifetime focus total — no new tracking. Milestones are
 * celebrated once each (flagged in prefs, backfilled silently on first read
 * like the story chapters); finishing the route awards the journey badge
 * and starts the next journey.
 */
object FocusJourney {
    const val ROUTE_KM = 400

    val MILESTONES = listOf(
        Milestone(0, "Dhaka", "ঢাকা"),
        Milestone(100, "Cumilla", "কুমিল্লা"),
        Milestone(250, "Chattogram", "চট্টগ্রাম"),
        Milestone(400, "Cox's Bazar", "কক্সবাজার")
    )

    /** (journeys completed, km into the current journey, total km). */
    fun progress(ctx: Context): Triple<Int, Long, Long> {
        val total = Store.focusStats(ctx).totalMin
        return Triple((total / ROUTE_KM).toInt(), total % ROUTE_KM, total)
    }

    /** Milestones passed so far (excluding the Dhaka start): 3 per finished journey + in-journey ones. */
    fun passedCount(ctx: Context): Int {
        val (journeys, km, _) = progress(ctx)
        var n = journeys * (MILESTONES.size - 1)
        for (m in MILESTONES.drop(1).dropLast(1)) if (km >= m.km) n++
        return n
    }

    /** The milestone with this 0-based ordinal across all journeys. */
    fun milestoneAt(ordinal: Int): Milestone {
        val idx = (ordinal.coerceAtLeast(0) % (MILESTONES.size - 1)) + 1
        return MILESTONES[idx]
    }

    /** Milestones already celebrated. Backfills silently on first read. */
    fun seenCount(ctx: Context): Int {
        val p = Store.lock(ctx)
        if (!p.contains("journey_seen")) {
            val cur = passedCount(ctx)
            p.edit().putInt("journey_seen", cur).apply()
            return cur
        }
        return p.getInt("journey_seen", 0).coerceAtLeast(0)
    }

    /** Milestones passed since the user was last told (0 when none are new). */
    fun newMilestones(ctx: Context): Int =
        (passedCount(ctx) - seenCount(ctx)).coerceAtLeast(0)

    fun markSeen(ctx: Context) {
        Store.lock(ctx).edit().putInt("journey_seen", passedCount(ctx)).apply()
    }

    /** The next milestone ahead in the current journey (always one — km is always under 400). */
    fun nextMilestone(ctx: Context): Milestone? {
        val (_, km, _) = progress(ctx)
        return MILESTONES.firstOrNull { it.km > km }
    }
}

/** The route drawn as a horizontal path: milestone dots, a filled track, and the current position. */
class JourneyView(ctx: Context) : View(ctx) {
    var km: Long = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = context.dp(18).toFloat()
        val lineY = h * 0.40f
        val left = pad
        val right = w - pad
        fun xFor(mkm: Int): Float = left + (right - left) * mkm / FocusJourney.ROUTE_KM.toFloat()

        // track
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(4).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = C.border
        canvas.drawLine(left, lineY, right, lineY, paint)
        // filled portion
        val curX = left + (right - left) * (km.coerceIn(0L, FocusJourney.ROUTE_KM.toLong()) / FocusJourney.ROUTE_KM.toFloat())
        paint.color = C.primary
        canvas.drawLine(left, lineY, curX, lineY, paint)

        // milestone dots + labels
        textPaint.textSize = context.dp(11).toFloat()
        val dotR = context.dp(7).toFloat()
        for ((i, m) in FocusJourney.MILESTONES.withIndex()) {
            val x = xFor(m.km)
            val passed = km >= m.km
            paint.style = Paint.Style.FILL
            paint.color = if (passed) C.mint else C.card2
            canvas.drawCircle(x, lineY, dotR, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2).toFloat()
            paint.color = if (passed) C.primary else C.muted
            canvas.drawCircle(x, lineY, dotR, paint)
            textPaint.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                FocusJourney.MILESTONES.size - 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            textPaint.color = if (passed) C.text else C.muted
            val lx = when (i) {
                0 -> pad
                FocusJourney.MILESTONES.size - 1 -> w - pad
                else -> x
            }
            canvas.drawText(L.t(m.nameEn, m.nameBn), lx, lineY + context.dp(26), textPaint)
        }

        // current position marker
        paint.style = Paint.Style.FILL
        paint.color = C.primary
        canvas.drawCircle(curX, lineY, context.dp(10).toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawCircle(curX, lineY, context.dp(4).toFloat(), paint)
    }
}

// =====================================================================
// RECORD BOARD
// =====================================================================

/** One personal best: a label, the value, and when it was set (null = no record yet). */
data class Record(val labelEn: String, val labelBn: String, val value: String, val dateMs: Long?)

/**
 * Record board: personal bests, all derived from existing stored data (no
 * separate counters) and recomputed on every Statistics build, so records
 * update automatically. Streak records scan the stored 90 days.
 */
object Records {
    /** "yyyyMMdd" key → the timestamp of that day's noon. */
    private fun dayKeyToMs(key: String): Long {
        return try {
            val c = Calendar.getInstance()
            c.set(key.substring(0, 4).toInt(), key.substring(4, 6).toInt() - 1, key.substring(6, 8).toInt(), 12, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.timeInMillis
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /** Longest run of consecutive days in the stored 90 days: (length, end day key).
     * [skipDay] marks days that neither count nor break the run (routine days with no tasks). */
    private fun longestRun(hasDay: (Int) -> Boolean, skipDay: ((Int) -> Boolean)? = null): Pair<Int, String?> {
        var best = 0
        var bestEnd: String? = null
        var run = 0
        var runStart = 0 // days-ago of the most recent day in the current run
        for (i in 0 until 90) {
            if (skipDay != null && skipDay(i)) continue
            if (hasDay(i)) {
                if (run == 0) runStart = i
                run++
                if (run > best) {
                    best = run
                    bestEnd = Store.dayKey(runStart)
                }
            } else {
                run = 0
            }
        }
        return Pair(best, bestEnd)
    }

    fun all(ctx: Context): List<Record> {
        val out = ArrayList<Record>()
        val sessions = Store.sessions(ctx).filter { it.ok }
        val map = Store.focusDayMap(ctx)
        val none = "—"

        // longest single lock
        val longest = sessions.maxByOrNull { it.dur }
        out.add(
            Record(
                "Longest single lock", "সবচেয়ে লম্বা লক",
                if (longest != null) fmtDur(longest.dur) else none, longest?.end
            )
        )

        // most focus minutes in one day
        val bestDay = map.maxByOrNull { it.value }
        val bestDayMin = bestDay?.value ?: 0
        out.add(
            Record(
                "Most focus in one day", "একদিনে সবচেয়ে বেশি ফোকাস",
                if (bestDayMin > 0) fmtDur(bestDayMin * 60000L) else none,
                if (bestDayMin > 0) dayKeyToMs(bestDay!!.key) else null
            )
        )

        // most locks completed in one day
        val byDay = sessions.groupBy { Store.dayKeyFor(it.end) }
        val bestLocks = byDay.maxByOrNull { it.value.size }
        out.add(
            Record(
                "Most locks in one day", "একদিনে সবচেয়ে বেশি লক",
                if (bestLocks != null) L.num(bestLocks.value.size.toString()) else none,
                if (bestLocks != null) dayKeyToMs(bestLocks.key) else null
            )
        )

        // longest focus streak (stored 90 days; protection tokens count as complete days)
        val (focusBest, focusEnd) = longestRun(
            hasDay = { i ->
                (map[Store.dayKey(i)] ?: 0) > 0 || StreakProtection.isFocusProtected(ctx, Store.dayKey(i))
            }
        )
        out.add(
            Record(
                "Longest focus streak", "সবচেয়ে লম্বা ফোকাস স্ট্রিক",
                if (focusBest > 0) L.num(focusBest.toString()) + " " + L.t("days", "দিন") else none,
                focusEnd?.let { dayKeyToMs(it) }
            )
        )

        // longest routine streak (days with no tasks neither count nor break it)
        val (routineBest, routineEnd) = longestRun(
            hasDay = { i ->
                val key = Store.dayKey(i)
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, -i)
                val tasks = Routine.tasksForDay(ctx, dayIdx(c))
                tasks.isNotEmpty() && (tasks.all { it.id in Routine.doneIds(ctx, key) } ||
                    StreakProtection.isRoutineProtected(ctx, key))
            },
            skipDay = { i ->
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, -i)
                Routine.tasksForDay(ctx, dayIdx(c)).isEmpty()
            }
        )
        out.add(
            Record(
                "Longest routine streak", "সবচেয়ে লম্বা রুটিন স্ট্রিক",
                if (routineBest > 0) L.num(routineBest.toString()) + " " + L.t("days", "দিন") else none,
                routineEnd?.let { dayKeyToMs(it) }
            )
        )

        return out
    }
}

// =====================================================================
// THEME UNLOCKS
// =====================================================================

/**
 * Theme unlocks: 4 more accent colours in the Settings picker that unlock by
 * progress (indices 8..11 in C.allAccents(), after the 5 base + 3 wheel-only
 * accents). Conditions are checked live from the focus streak — no stored
 * unlock flags. The first two (crimson, lime) were level-gated; levels were
 * removed in build 66, so they are unlocked outright.
 */
object ThemeUnlocks {
    /** The first unlock-accent index (base 0..4, wheel 5..7, unlock 8..11). */
    const val FIRST = 8

    fun isUnlocked(ctx: Context, idx: Int): Boolean {
        if (idx < FIRST) return LuckyWheel.isThemeUnlocked(ctx, idx)
        return when (idx) {
            8 -> true // was level 3: levels removed in build 66
            9 -> true // was level 5: levels removed in build 66
            10 -> Store.focusStats(ctx).streak >= 7
            11 -> Store.focusStats(ctx).streak >= 30
            else -> false
        }
    }

    /** The unlock condition for one accent ("" when already unlocked). */
    fun condition(idx: Int): String = when (idx) {
        8 -> ""
        9 -> ""
        10 -> L.t("Unlocks at a 7-day focus streak", "৭ দিনের ফোকাস স্ট্রিকে খুলবে")
        11 -> L.t("Unlocks at a 30-day focus streak", "৩০ দিনের ফোকাস স্ট্রিকে খুলবে")
        else -> ""
    }

    /** The still-locked conditions in picker order, for the hint line under the swatches. */
    fun hintLine(): String = (FIRST..11).map { condition(it) }.filter { it.isNotEmpty() }.joinToString(" · ")
}

// =====================================================================
// FUN FACTS
// =====================================================================

/**
 * Fun facts: one random fact after each completed lock, in the session-complete
 * dialog after the quote. 20 short original facts, bilingual. The previous
 * lock's fact is never repeated twice in a row.
 */
object FunFacts {
    private val ALL = listOf(
        "Honey never spoils — jars thousands of years old are still edible." to "মধু কখনো নষ্ট হয় না — হাজার বছরের পুরনো মধুও খাওয়া যায়।",
        "Octopuses have three hearts." to "অক্টোপাসের তিনটি হৃদপিণ্ড আছে।",
        "Bananas are berries, but strawberries are not." to "কলা আসলে বেরি, কিন্তু স্ট্রবেরি বেরি নয়।",
        "The Moon moves away from Earth about 3.8 cm every year." to "চাঁদ প্রতি বছর পৃথিবী থেকে প্রায় ৩.৮ সেন্টিমিটার দূরে সরে যাচ্ছে।",
        "A day on Venus is longer than a year on Venus." to "শুক্র গ্রহে এক দিন তার এক বছরের চেয়েও লম্বা।",
        "Sharks existed before trees." to "গাছের আগেও হাঙর ছিল।",
        "Hot water can freeze faster than cold water." to "গরম পানি ঠান্ডা পানির চেয়ে দ্রুত বরফ হতে পারে।",
        "Your brain uses about 20% of your body's energy." to "তোমার মস্তিষ্ক শরীরের প্রায় ২০% শক্তি ব্যবহার করে।",
        "Wombat poop is cube-shaped." to "ওমব্যাটের মল ঘনক আকৃতির হয়।",
        "There are more possible chess games than atoms in the observable universe." to "দাবার সম্ভাব্য খেলার সংখ্যা মহাবিশ্বের পরমাণুর চেয়েও বেশি।",
        "The first computer bug was a real moth found in a computer in 1947." to "প্রথম কম্পিউটার বাগ ছিল ১৯৪৭ সালে কম্পিউটারে পাওয়া এক আসল মথ।",
        "Sea otters hold hands while sleeping so they don't drift apart." to "সামুদ্রিক ভোঁদড় ঘুমানোর সময় হাত ধরে থাকে, যাতে ভেসে না যায়।",
        "A group of flamingos is called a \"flamboyance\"." to "একদল ফ্ল্যামিঙ্গোকে ইংরেজিতে \"ফ্ল্যামবয়্যান্স\" বলে।",
        "The Eiffel Tower grows about 15 cm taller in summer heat." to "গরমে আইফেল টাওয়ার প্রায় ১৫ সেন্টিমিটার লম্বা হয়ে যায়।",
        "Sound travels about 4 times faster in water than in air." to "শব্দ বাতাসের চেয়ে পানিতে প্রায় ৪ গুণ দ্রুত চলে।",
        "The human nose can detect about a trillion different smells." to "মানুষের নাক প্রায় এক ট্রিলিয়ন রকম গন্ধ চিনতে পারে।",
        "Cows have best friends and get stressed when separated." to "গরুরও প্রিয় বন্ধু থাকে, আলাদা হলে তারা চিন্তিত হয়।",
        "The Great Wall of China is held together in places by sticky rice." to "চীনের মহাপ্রাচীরের কিছু অংশ আঠালো ভাত দিয়ে জোড়া লাগানো।",
        "Butterflies taste with their feet." to "প্রজাপতি পা দিয়ে স্বাদ নেয়।",
        "Saturn would float in water — it is less dense than water." to "শনি গ্রহ পানিতে ভাসবে — এটা পানির চেয়ে হালকা।"
    )

    fun random(ctx: Context): String {
        val p = Store.lock(ctx)
        val last = p.getInt("fact_last", -1)
        var idx = Random.nextInt(ALL.size)
        if (ALL.size > 1) {
            var guard = 0
            while (idx == last && guard < 10) {
                idx = Random.nextInt(ALL.size)
                guard++
            }
        }
        p.edit().putInt("fact_last", idx).apply()
        val f = ALL[idx]
        return L.t(f.first, f.second)
    }
}
