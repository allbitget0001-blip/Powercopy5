package com.powerlock.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

const val ACTION_ROUTINE_REMIND = "com.powerlock.app.ROUTINE_REMIND"
const val ACTION_ROUTINE_OVERDUE = "com.powerlock.app.ROUTINE_OVERDUE"
const val ACTION_ROUTINE_REPORT = "com.powerlock.app.ROUTINE_REPORT"

/**
 * Daily routine: repeating tasks per weekday, with a consequence lock.
 *
 * Each task has a time and a grace window (default 30 min). A reminder fires
 * at the task's time. If the window passes without a check-off, the task is
 * marked missed and — if no lock is already running — a normal lock session
 * (source "routine") starts for the penalty length. Checking the overdue
 * task off during the penalty lock ends the lock early: do the task, unlock.
 * Multiple missed tasks share one penalty lock; a task never triggers twice
 * in a day. A task checked off late still counts for the day's streak.
 *
 * Storage (settings prefs): "routine_tasks" is a JSON object of weekday
 * index (0 = Mon .. 6 = Sun) -> task arrays; "routine_done" and
 * "routine_missed" are JSON objects of dayKey -> task-id arrays (90 days).
 * Each task carries a "verify" field ("manual" in v1) so a later build can
 * add sensor verification (steps, activity, location) per task.
 */
object Routine {
    private const val REQ_REPORT = 400001
    const val REPORT_MIN = 21 * 60 // 9 PM: the end-of-day summary

    // ----- task storage -----

    private fun tasksObj(ctx: Context): JSONObject =
        try {
            JSONObject(Store.settings(ctx).getString("routine_tasks", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }

    /** Tasks for one weekday (0 = Mon .. 6 = Sun), sorted by time. */
    fun tasksForDay(ctx: Context, day: Int): List<RoutineTask> {
        val out = ArrayList<RoutineTask>()
        try {
            val arr = tasksObj(ctx).optJSONArray(day.toString()) ?: return out
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    out.add(
                        RoutineTask(
                            o.getLong("id"),
                            o.getString("t"),
                            o.getInt("m"),
                            o.optInt("g", 30),
                            o.optString("v", "manual")
                        )
                    )
                } catch (_: Exception) {
                    // one malformed entry: skip it, keep the rest
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "routine read failed", e)
        }
        return out.sortedBy { it.timeMin }
    }

    fun tasksToday(ctx: Context): List<RoutineTask> =
        tasksForDay(ctx, dayIdx(Calendar.getInstance()))

    private fun tasksAgo(ctx: Context, ago: Int): List<RoutineTask> {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -ago)
        return tasksForDay(ctx, dayIdx(c))
    }

    /** The tasks of the weekday a dayKey falls on (only today/yesterday are ever asked). */
    private fun tasksForDayKey(ctx: Context, dayKey: String): List<RoutineTask> =
        when (dayKey) {
            Store.dayKey(0) -> tasksToday(ctx)
            Store.dayKey(1) -> tasksAgo(ctx, 1)
            else -> emptyList()
        }

    private fun putTasks(ctx: Context, day: Int, list: List<RoutineTask>) {
        // a corrupt stored object must never be overwritten with a fresh one
        // — that would wipe every weekday's tasks at once
        if (JsonGuard.isCorrupt(Store.settings(ctx), "routine_tasks")) {
            Log.e(TAG, "routine tasks corrupt, keeping stored value")
            return
        }
        val obj = tasksObj(ctx)
        val arr = JSONArray()
        for (t in list.sortedBy { it.timeMin }) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("t", t.title)
            o.put("m", t.timeMin)
            o.put("g", t.graceMin)
            o.put("v", t.verify)
            arr.put(o)
        }
        if (arr.length() == 0) obj.remove(day.toString()) else obj.put(day.toString(), arr)
        Store.settings(ctx).edit().putString("routine_tasks", obj.toString()).apply()
    }

    /** Saves one weekday's tasks and re-arms the alarms. */
    fun saveTasks(ctx: Context, day: Int, list: List<RoutineTask>) {
        putTasks(ctx, day, list)
        // today's snapshot follows the live list (past days keep theirs)
        if (day == dayIdx(Calendar.getInstance())) {
            saveSnapshot(ctx, Store.dayKey(0), list.map { it.id }.toSet())
        }
        rescheduleAlarms(ctx)
    }

    fun hasAny(ctx: Context): Boolean {
        val obj = tasksObj(ctx)
        val keys = obj.keys()
        while (keys.hasNext()) {
            if ((obj.optJSONArray(keys.next())?.length() ?: 0) > 0) return true
        }
        return false
    }

    // ----- done / missed records (date-keyed) -----

    private fun idMap(ctx: Context, key: String): JSONObject =
        try {
            JSONObject(Store.settings(ctx).getString(key, "{}"))
        } catch (e: Exception) {
            JSONObject()
        }

    private fun idsFor(ctx: Context, key: String, dayKey: String): Set<Long> {
        val out = HashSet<Long>()
        try {
            val arr = idMap(ctx, key).optJSONArray(dayKey) ?: return out
            for (i in 0 until arr.length()) {
                try {
                    out.add(arr.getLong(i))
                } catch (_: Exception) {
                    // one malformed entry: skip it, keep the rest
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "routine ids read failed", e)
        }
        return out
    }

    private fun saveIds(ctx: Context, key: String, dayKey: String, ids: Set<Long>) {
        // a corrupt stored map must never be overwritten with a fresh one
        if (JsonGuard.isCorrupt(Store.settings(ctx), key)) {
            Log.e(TAG, "routine $key corrupt, keeping stored value")
            return
        }
        val obj = idMap(ctx, key)
        if (ids.isEmpty()) {
            obj.remove(dayKey)
        } else {
            val arr = JSONArray()
            for (id in ids) arr.put(id)
            obj.put(dayKey, arr)
        }
        // prune days older than 90 days (the streak never looks further back)
        val keep = (0 until 90).map { Store.dayKey(it) }.toSet()
        val keys = obj.keys()
        val drop = ArrayList<String>()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in keep) drop.add(k)
        }
        for (k in drop) obj.remove(k)
        Store.settings(ctx).edit().putString(key, obj.toString()).apply()
    }

    // ----- day snapshots: the task-id set a day is judged against -----
    // A past day must be judged against the tasks that existed THEN, not
    // against the list as it looks now — otherwise adding a task to Monday's
    // list retroactively breaks last Monday's streak day. The snapshot is
    // taken the first time a day is evaluated and never changes afterwards;
    // today always uses the live list (tasks can still be added and done),
    // and its snapshot is refreshed whenever today's list is saved.
    // A missing key means "no snapshot yet"; an empty array means "no tasks".

    private fun snapshotIds(ctx: Context, dayKey: String): Set<Long>? {
        val obj = idMap(ctx, "routine_snapshot")
        if (!obj.has(dayKey)) return null
        val out = HashSet<Long>()
        try {
            val arr = obj.optJSONArray(dayKey) ?: return out
            for (i in 0 until arr.length()) {
                try {
                    out.add(arr.getLong(i))
                } catch (_: Exception) {
                    // one malformed entry: skip it, keep the rest
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "routine snapshot read failed", e)
        }
        return out
    }

    private fun saveSnapshot(ctx: Context, dayKey: String, ids: Set<Long>) {
        // a corrupt stored map must never be overwritten with a fresh one
        if (JsonGuard.isCorrupt(Store.settings(ctx), "routine_snapshot")) {
            Log.e(TAG, "routine snapshot corrupt, keeping stored value")
            return
        }
        val obj = idMap(ctx, "routine_snapshot")
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        obj.put(dayKey, arr)
        // prune days older than 90 days (the streak never looks further back)
        val keep = (0 until 90).map { Store.dayKey(it) }.toSet()
        val keys = obj.keys()
        val drop = ArrayList<String>()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in keep) drop.add(k)
        }
        for (k in drop) obj.remove(k)
        Store.settings(ctx).edit().putString("routine_snapshot", obj.toString()).apply()
    }

    fun doneIds(ctx: Context, dayKey: String): Set<Long> = idsFor(ctx, "routine_done", dayKey)
    fun missedIds(ctx: Context, dayKey: String): Set<Long> = idsFor(ctx, "routine_missed", dayKey)

    /** Tap on a task: check it off (or un-check it). Checking the overdue task
     * that caused a penalty lock ends the lock early — do the task, unlock. */
    fun toggleDone(ctx: Context, id: Long) {
        val day = Store.dayKey(0)
        val done = doneIds(ctx, day).toMutableSet()
        val wasDone = id in done
        if (wasDone) done.remove(id) else done.add(id)
        saveIds(ctx, "routine_done", day, done)
        if (!wasDone) {
            // a late check-off still counts for the day: it is no longer missed
            val missed = missedIds(ctx, day).toMutableSet()
            if (missed.remove(id)) saveIds(ctx, "routine_missed", day, missed)
            // done during a penalty lock → the task is done, so the penalty ends.
            // (If the penalty task was deleted from the routine, any check-off
            // ends it — otherwise the lock could never end early.)
            if (isPenaltyLock(ctx)) {
                val penId = Store.lock(ctx).getLong("pen_task_id", -1L)
                val penExists = tasksToday(ctx).any { it.id == penId }
                if (id == penId || !penExists) Store.finalizePenalty(ctx)
            }
            // the whole day's routine is done → a bonus wheel spin (once a day)
            LuckyWheel.onRoutineComplete(ctx)
        }
        rescheduleAlarms(ctx)
    }

    // ----- penalty lock -----

    /** Penalty length in minutes (default 30, clamped 1–120). */
    fun penaltyMin(ctx: Context): Int = Store.settings(ctx).getInt("routine_penalty", 30).coerceIn(1, 120)

    fun setPenaltyMin(ctx: Context, min: Int) {
        Store.settings(ctx).edit().putInt("routine_penalty", min.coerceIn(1, 120)).apply()
    }

    /** True while the running session is a routine penalty lock. */
    fun isPenaltyLock(ctx: Context): Boolean =
        Store.isActive(ctx) && (Store.lock(ctx).getString("src", "manual") ?: "manual") == "routine"

    /** The title of the task that caused the running penalty lock, or null. */
    fun penaltyTaskTitle(ctx: Context): String? =
        if (isPenaltyLock(ctx)) Store.lock(ctx).getString("pen_task", null) else null

    /** The overdue check: run from the Home tick, the service heartbeat, the
     * Watchdog and the window-end alarms. Marks overdue tasks missed and starts
     * ONE penalty lock for the first overdue task — never while another lock
     * is running, and never a second lock for the same task in a day. */
    fun sweep(ctx: Context) {
        try {
            L.load(ctx)
            Store.finalizeIfExpired(ctx)
            val tasks = tasksToday(ctx)
            if (tasks.isEmpty()) return
            val day = Store.dayKey(0)
            val done = doneIds(ctx, day)
            val missed = missedIds(ctx, day).toMutableSet()
            var changed = false
            val now = System.currentTimeMillis()
            for (t in tasks) {
                if (t.id in done || t.id in missed) continue
                if (now < todayAt(t.timeMin + t.graceMin)) continue
                missed.add(t.id)
                changed = true
                if (Store.isActive(ctx)) continue // a lock is running: mark missed, let it stand
                startPenalty(ctx, t)
            }
            if (changed) saveIds(ctx, "routine_missed", day, missed)
        } catch (e: Exception) {
            Log.e(TAG, "routine sweep failed", e)
        }
    }

    private fun startPenalty(ctx: Context, task: RoutineTask): Boolean {
        val min = penaltyMin(ctx)
        if (min <= 0) return false
        val ok = Store.startSession(ctx, min * 60_000L, "routine")
        if (!ok) return false
        Store.lock(ctx).edit()
            .putLong("pen_task_id", task.id)
            .putString("pen_task", task.title)
            .apply()
        Reminders.show(
            ctx, 30,
            L.t("Routine task overdue", "রুটিনের কাজ বাকি"),
            L.t(
                "${task.title} is not done — the phone is locked for $min minutes. Finish the task and check it off to unlock early.",
                "${task.title} শেষ হয়নি — ফোন ${L.num(min.toString())} মিনিটের জন্য লক হয়েছে। কাজ শেষ করে টিক দিলে আগেই খুলবে।"
            )
        )
        return true
    }

    // ----- streak + summary -----

    /** Consecutive days where every routine task was checked off. Days with no
     * tasks neither count nor break the streak. Today still in progress does
     * not break it either — the count starts from yesterday then. */
    fun streak(ctx: Context): Int {
        val today = tasksToday(ctx)
        val todayDone = today.isNotEmpty() && today.all { it.id in doneIds(ctx, Store.dayKey(0)) }
        var i = if (today.isNotEmpty() && !todayDone) 1 else 0
        var streak = 0
        while (i < 90) {
            val dayKey = Store.dayKey(i)
            // today is judged against the live list; a past day against its
            // snapshot (taken the first time the day is evaluated, so later
            // edits to that weekday's list cannot rewrite history)
            val ids = if (i == 0) {
                tasksAgo(ctx, 0).map { it.id }.toSet()
            } else {
                snapshotIds(ctx, dayKey) ?: run {
                    val cur = tasksAgo(ctx, i).map { it.id }.toSet()
                    saveSnapshot(ctx, dayKey, cur)
                    cur
                }
            }
            if (ids.isEmpty()) {
                i++
                continue
            }
            val done = doneIds(ctx, dayKey)
            if (ids.all { it in done } || StreakProtection.isRoutineProtected(ctx, dayKey)) {
                streak++
                i++
            } else break
        }
        return streak
    }

    /** (done, total) for today's routine. */
    fun todaySummary(ctx: Context): Pair<Int, Int> {
        val tasks = tasksToday(ctx)
        val done = doneIds(ctx, Store.dayKey(0))
        return Pair(tasks.count { it.id in done }, tasks.size)
    }

    // ----- templates -----

    data class RoutineTemplate(
        val nameEn: String,
        val nameBn: String,
        val tasks: List<Triple<String, String, Int>> // titleEn, titleBn, timeMin (grace 30)
    )

    fun templates(): List<RoutineTemplate> = listOf(
        RoutineTemplate(
            "Student", "ছাত্র",
            listOf(
                Triple("Wake up", "ঘুম থেকে ওঠা", 360),
                Triple("Study", "পড়াশোনা", 420),
                Triple("Lunch", "দুপুরের খাবার", 780),
                Triple("Exercise", "ব্যায়াম", 960),
                Triple("Evening study", "সন্ধ্যার পড়াশোনা", 1200),
                Triple("Sleep", "ঘুমাতে যাওয়া", 1350)
            )
        ),
        RoutineTemplate(
            "Office", "অফিস",
            listOf(
                Triple("Wake up", "ঘুম থেকে ওঠা", 420),
                Triple("Start work", "কাজ শুরু", 510),
                Triple("Lunch", "দুপুরের খাবার", 780),
                Triple("Finish work", "কাজ শেষ", 1080),
                Triple("Exercise", "ব্যায়াম", 1140),
                Triple("Sleep", "ঘুমাতে যাওয়া", 1380)
            )
        ),
        RoutineTemplate(
            "General", "সাধারণ",
            listOf(
                Triple("Wake up", "ঘুম থেকে ওঠা", 390),
                Triple("Morning tasks", "সকালের কাজ", 480),
                Triple("Lunch", "দুপুরের খাবার", 780),
                Triple("Exercise", "ব্যায়াম", 1020),
                Triple("Family time", "পরিবারের সময়", 1260),
                Triple("Sleep", "ঘুমাতে যাওয়া", 1350)
            )
        )
    )

    /** Replaces the whole routine (all weekdays) with the template's tasks.
     * A task that already exists with the same title and time keeps its id,
     * so today's check-offs and the streak survive applying a template. */
    fun applyTemplate(ctx: Context, tpl: RoutineTemplate) {
        for (day in 0..6) {
            val existing = tasksForDay(ctx, day)
            val list = tpl.tasks.map { t ->
                val title = L.t(t.first, t.second)
                val kept = existing.find { (it.title == t.first || it.title == t.second) && it.timeMin == t.third }
                if (kept != null) RoutineTask(kept.id, title, t.third, 30, kept.verify)
                else RoutineTask(Store.newId(), title, t.third, 30, "manual")
            }
            putTasks(ctx, day, list)
        }
        // today's snapshot follows the new list (past days keep theirs)
        saveSnapshot(ctx, Store.dayKey(0), tasksToday(ctx).map { it.id }.toSet())
        rescheduleAlarms(ctx)
    }

    // ----- alarms -----

    private fun todayAt(minOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, minOfDay / 60)
        c.set(Calendar.MINUTE, minOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** [todayAt] for a past day: [ago] days back (minOfDay may exceed 1440 —
     * a window crossing midnight rolls into the next day, as in [todayAt]). */
    private fun dayAt(ago: Int, minOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -ago)
        c.set(Calendar.HOUR_OF_DAY, minOfDay / 60)
        c.set(Calendar.MINUTE, minOfDay % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun remindPending(ctx: Context, t: RoutineTask, dayKey: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_ROUTINE_REMIND)
            .putExtra("task_id", t.id).putExtra("day", dayKey)
        // full-range code: the distinct intent actions keep remind and
        // overdue PendingIntents apart for the same task
        return PendingIntent.getBroadcast(
            ctx, (t.id % Int.MAX_VALUE).toInt(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun overduePending(ctx: Context, t: RoutineTask, dayKey: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_ROUTINE_OVERDUE)
            .putExtra("task_id", t.id).putExtra("day", dayKey)
        return PendingIntent.getBroadcast(
            ctx, (t.id % Int.MAX_VALUE).toInt(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun reportPending(ctx: Context): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_ROUTINE_REPORT)
        return PendingIntent.getBroadcast(
            ctx, REQ_REPORT, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Cancels all routine alarms and re-arms today's upcoming ones + the evening report. Idempotent. */
    fun rescheduleAlarms(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // cancel today's + yesterday's (a task's window may cross midnight)
            for (ago in 0..1) {
                val dayKey = Store.dayKey(ago)
                val c = Calendar.getInstance()
                c.add(Calendar.DAY_OF_YEAR, -ago)
                for (t in tasksForDay(ctx, dayIdx(c))) {
                    am.cancel(remindPending(ctx, t, dayKey))
                    am.cancel(overduePending(ctx, t, dayKey))
                }
            }
            val now = System.currentTimeMillis()
            val today = Store.dayKey(0)
            val done = doneIds(ctx, today)
            for (t in tasksToday(ctx)) {
                if (t.id in done) continue
                val atTime = todayAt(t.timeMin)
                if (atTime > now) setAlarm(am, atTime, remindPending(ctx, t, today))
                val atOverdue = todayAt(t.timeMin + t.graceMin)
                if (atOverdue > now) setAlarm(am, atOverdue, overduePending(ctx, t, today))
            }
            // yesterday's overdue alarms whose window crosses midnight: the
            // cancel above removed them, and only the overdue alarm can
            // still be pending (the remind time is already past) — re-arm
            // the ones still in the future so the miss is still recorded
            val yDayKey = Store.dayKey(1)
            val yDone = doneIds(ctx, yDayKey)
            val yCal = Calendar.getInstance()
            yCal.add(Calendar.DAY_OF_YEAR, -1)
            for (t in tasksForDay(ctx, dayIdx(yCal))) {
                if (t.id in yDone) continue
                val atOverdue = dayAt(1, t.timeMin + t.graceMin)
                if (atOverdue > now) setAlarm(am, atOverdue, overduePending(ctx, t, yDayKey))
            }
            rescheduleReport(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "routine reschedule failed", e)
        }
    }

    private fun rescheduleReport(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = reportPending(ctx)
            am.cancel(pi)
            if (!hasAny(ctx)) return
            val at = todayAt(REPORT_MIN)
            val fireAt = if (at > System.currentTimeMillis()) at else at + 24 * 60 * 60 * 1000L
            setAlarm(am, fireAt, pi)
        } catch (e: Exception) {
            Log.e(TAG, "routine report reschedule failed", e)
        }
    }

    /** Reminder: fires at the task's time (skipped when done or deleted). */
    fun onRemind(ctx: Context, taskId: Long, dayKey: String) {
        try {
            L.load(ctx)
            val t = tasksForDayKey(ctx, dayKey).find { it.id == taskId } ?: return
            if (taskId in doneIds(ctx, dayKey)) return
            Reminders.show(
                ctx, 20000 + ((taskId / 1000) % 1000).toInt(), // routine range 20000–20999
                L.t("Routine time", "রুটিনের সময়"),
                t.title
            )
        } catch (e: Exception) {
            Log.e(TAG, "routine remind failed", e)
        }
    }

    /** Window-end alarm: the task is overdue — mark it missed and, if no lock
     * is running, start the penalty lock. */
    fun onOverdue(ctx: Context, taskId: Long, dayKey: String) {
        try {
            L.load(ctx)
            val t = tasksForDayKey(ctx, dayKey).find { it.id == taskId } ?: return
            if (taskId in doneIds(ctx, dayKey)) return
            val missed = missedIds(ctx, dayKey).toMutableSet()
            if (taskId in missed) return
            missed.add(taskId)
            saveIds(ctx, "routine_missed", dayKey, missed)
            // a stale alarm (delayed past midnight by Doze) records the miss
            // for yesterday but must not start a penalty lock today for it
            if (dayKey != Store.dayKey(0)) return
            if (Store.isActive(ctx)) return // a lock is running: mark missed, let it stand
            startPenalty(ctx, t)
        } catch (e: Exception) {
            Log.e(TAG, "routine overdue failed", e)
        }
    }

    /** Evening summary (9 PM): how many of today's tasks got done + the streak. */
    fun onReport(ctx: Context) {
        try {
            L.load(ctx)
            val (done, total) = todaySummary(ctx)
            if (total > 0) {
                val s = streak(ctx)
                val text = L.t(
                    "$done of $total tasks done today",
                    "আজ ${L.num(total.toString())}টি কাজের মধ্যে ${L.num(done.toString())}টি শেষ"
                ) + if (s > 0) " · " + L.t("$s-day streak", "${L.num(s.toString())} দিনের স্ট্রিক") else ""
                Reminders.show(ctx, 31, L.t("Today's routine", "আজকের রুটিন"), text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "routine report failed", e)
        }
        rescheduleReport(ctx)
    }
}
