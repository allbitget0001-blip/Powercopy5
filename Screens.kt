package com.powerlock.app

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

// =====================================================================
// HOME
// =====================================================================
class HomeScreen(private val a: MainActivity) {
    private lateinit var ring: RingView
    private lateinit var pill: TextView
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var previewText: TextView
    private lateinit var schedPill: TextView
    private lateinit var lastSummaryText: TextView
    private var summaryShown = false
    private var lastSummarySig = ""
    private lateinit var screenTimeText: TextView
    private var cachedUsageSec = -1L
    private var cachedUsageAt = 0L
    private var cachedUsageDay = ""
    private lateinit var scoreText: TextView
    private var cachedScore: ScoreBreakdown? = null
    private var cachedScoreAt = 0L
    private lateinit var treeView: TreeView
    private lateinit var treeLabel: TextView
    private var treeSig = ""
    private lateinit var animalsRow: LinearLayout
    private var animalsSig = ""
    private lateinit var petView: PetView
    private lateinit var petNameText: TextView
    private lateinit var petMoodText: TextView
    private lateinit var petBar: BarView
    private var petSig = ""
    private lateinit var storySubText: TextView
    private var storySig = ""
    private lateinit var wheelSpinsText: TextView
    private lateinit var wheelBtn: TextView
    private var wheelSig = ""
    private lateinit var profileRow: LinearLayout
    private lateinit var profileNameText: TextView
    private lateinit var profileSubText: TextView
    private var profileSig = ""
    private lateinit var routineBox: LinearLayout
    private lateinit var routineStreakText: TextView
    private var routineSig = ""
    private var lastRoutineSweep = 0L
    private lateinit var noteBox: LinearLayout
    private lateinit var noteEdit: EditText
    private var noteSig = ""
    private lateinit var capsuleBox: LinearLayout
    private var capsuleSig = ""
    private var lastCapsuleCheck = 0L
    private var badgeShown = false
    private lateinit var startBtn: LinearLayout
    private lateinit var stopBtn: LinearLayout
    private lateinit var extendBtn: LinearLayout
    private lateinit var warnBox: LinearLayout
    private val chips = ArrayList<TextView>()
    private val quick = listOf(5, 10, 15, 30, 60)
    private val modeChips = ArrayList<TextView>()
    private val modes = listOf(UNLOCK_TIME, UNLOCK_LOC, UNLOCK_EITHER)
    private lateinit var destRow: LinearLayout
    private lateinit var destNameText: TextView
    private lateinit var destRadiusText: TextView
    private var warnSig = ""
    private var lastStatus: PStatus? = null
    private var lastActive: Boolean? = null
    private var lastSched: Boolean? = null

    private fun quickLabel(m: Int): String = L.num(L.t("$m min", "$m মিনিট"))

    fun build(): View {
        val (sv, col) = a.page()

        // ---- header ----
        val header = LinearLayout(a)
        header.gravity = Gravity.CENTER_VERTICAL
        val tile = LinearLayout(a)
        tile.gravity = Gravity.CENTER
        tile.background = rounded(Color.parseColor("#14392A"), a.dp(16).toFloat())
        tile.addView(IconView(a, Ic.SHIELD, C.primary), lp(a.dp(30), a.dp(30)))
        header.addView(tile, lp(a.dp(52), a.dp(52)))
        val names = LinearLayout(a)
        names.orientation = LinearLayout.VERTICAL
        names.addView(a.tv("PowerLock 2", 24f, C.text, 1))
        val sub = a.tv(L.t("PREMIUM EDITION", "প্রিমিয়াম এডিশন"), 12f, C.muted, 1)
        sub.letterSpacing = 0.1f
        names.addView(sub)
        header.addView(names, lp(0, WRAP, l = a.dp(14), weight = 1f))
        pill = a.tv("", 13f, C.primary, 1)
        pill.letterSpacing = 0.05f
        pill.setPadding(a.dp(14), a.dp(8), a.dp(14), a.dp(8))
        header.addView(pill)
        col.addView(header)

        warnBox = LinearLayout(a)
        warnBox.orientation = LinearLayout.VERTICAL
        col.addView(warnBox, lp(MATCH, WRAP, t = a.dp(14)))

        // ---- status card ----
        val sc = a.card(20)
        sc.gravity = Gravity.CENTER_HORIZONTAL
        sc.background = rounded(C.card, a.dp(28).toFloat())
        val lbl = a.tv(L.t("POWER LOCK STATUS", "পাওয়ার লক স্ট্যাটাস"), 12f, C.muted, 1)
        lbl.letterSpacing = 0.12f
        lbl.gravity = Gravity.CENTER
        sc.addView(lbl)
        ring = RingView(a)
        sc.addView(ring, lp(a.dp(170), a.dp(170), t = a.dp(16)))
        titleText = a.tv("", 26f, C.text, 1)
        titleText.gravity = Gravity.CENTER
        sc.addView(titleText, lp(MATCH, WRAP, t = a.dp(16)))
        subText = a.tv("", 15f, C.muted)
        subText.gravity = Gravity.CENTER
        sc.addView(subText, lp(MATCH, WRAP, t = a.dp(6)))
        startBtn = a.bigButton(Ic.LOCK, L.t("Start Power Lock", "পাওয়ার লক শুরু"), C.primary, C.onPrimary)
        startBtn.setOnClickListener { onStart() }
        sc.addView(startBtn, lp(MATCH, a.dp(60), t = a.dp(20)))
        stopBtn = a.bigButton(Ic.LOCK, L.t("Stop Power Lock", "পাওয়ার লক বন্ধ"), Color.TRANSPARENT, C.danger, C.danger)
        stopBtn.setOnClickListener { onStop() }
        sc.addView(stopBtn, lp(MATCH, a.dp(60), t = a.dp(20)))
        extendBtn = a.bigButton(Ic.CLOCK, L.t("Add time", "সময় বাড়ান"), C.card2, C.primary, C.primary)
        extendBtn.setOnClickListener { a.extendFlow { tick() } }
        sc.addView(extendBtn, lp(MATCH, a.dp(60), t = a.dp(12)))
        col.addView(sc, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- focus tree: grows while a lock runs, withers if stopped early ----
        val treeCard = a.card(20)
        treeCard.addView(a.tv(L.t("Focus tree", "ফোকাস গাছ"), 18f, C.text, 1))
        treeView = TreeView(a)
        treeCard.addView(treeView, lp(MATCH, a.dp(170), t = a.dp(8)))
        treeLabel = a.tv("", 13f, C.muted)
        treeLabel.gravity = Gravity.CENTER
        treeCard.addView(treeLabel, lp(MATCH, WRAP, t = a.dp(6)))
        // garden animals living by the tree (small, newest first)
        animalsRow = LinearLayout(a)
        animalsRow.gravity = Gravity.CENTER
        treeCard.addView(animalsRow, lp(MATCH, WRAP, t = a.dp(4)))
        // tap the tree card to pick a species (won from the lucky wheel)
        treeCard.setOnClickListener { speciesDialog() }
        treeCard.pressFeedback()
        col.addView(treeCard, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- focus pet: happier with every completed lock, sadder when one breaks ----
        val petCard = a.card(20)
        petNameText = a.tv("", 18f, C.text, 1)
        petCard.addView(petNameText)
        petView = PetView(a)
        petCard.addView(petView, lp(MATCH, a.dp(140), t = a.dp(4)))
        petMoodText = a.tv("", 13f, C.muted)
        petMoodText.gravity = Gravity.CENTER
        petCard.addView(petMoodText, lp(MATCH, WRAP, t = a.dp(4)))
        petBar = BarView(a)
        petCard.addView(petBar, lp(MATCH, a.dp(10), t = a.dp(8)))
        // tap the pet to rename it
        petCard.setOnClickListener { renamePetDialog() }
        petCard.pressFeedback()
        col.addView(petCard, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- story: one chapter unlocks per completed lock ----
        val storyCard = a.card(20)
        storyCard.addView(a.tv(L.t("Story — The Lantern of the Quiet Forest", "গল্প — নীরব বনের লণ্ঠন"), 18f, C.text, 1))
        storySubText = a.tv("", 13f, C.muted)
        storyCard.addView(storySubText, lp(MATCH, WRAP, t = a.dp(6)))
        storyCard.setOnClickListener { Story.showReader(a) }
        storyCard.pressFeedback()
        col.addView(storyCard, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- lucky wheel: one spin per completed lock, fun rewards only ----
        val wheelCard = a.card(20)
        wheelCard.addView(a.tv(L.t("Lucky wheel", "লাকি হুইল"), 18f, C.text, 1))
        wheelSpinsText = a.tv("", 13f, C.muted)
        wheelCard.addView(wheelSpinsText, lp(MATCH, WRAP, t = a.dp(6)))
        wheelBtn = a.primaryBtn(L.t("Spin the wheel", "চাকা ঘোরাও"))
        wheelBtn.setOnClickListener { LuckyWheel.showWheel(a) { tick() } }
        wheelCard.addView(wheelBtn, lp(MATCH, a.dp(48), t = a.dp(12)))
        col.addView(wheelCard, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- daily routine: repeating tasks; miss one and the phone locks ----
        val routine = a.card(20)
        routine.addView(a.tv(L.t("Daily routine", "দৈনিক রুটিন"), 18f, C.text, 1))
        routineStreakText = a.tv("", 13f, C.mint)
        routine.addView(routineStreakText, lp(MATCH, WRAP, t = a.dp(4)))
        routineBox = LinearLayout(a)
        routineBox.orientation = LinearLayout.VERTICAL
        routine.addView(routineBox, lp(MATCH, WRAP, t = a.dp(6)))
        val routineEdit = a.outlineBtn(L.t("Edit routine", "রুটিন বদলান"), C.primary)
        routineEdit.setOnClickListener { a.go(1) }
        routine.addView(routineEdit, lp(MATCH, a.dp(48), t = a.dp(12)))
        col.addView(routine, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- focus note: jot a thought during a lock without breaking it ----
        val note = a.card(20)
        note.addView(a.tv(L.t("Focus note", "ফোকাস নোট"), 18f, C.text, 1))
        note.addView(a.tv(L.t("A thought during a lock? Write it down in seconds.", "লকের সময় কোনো চিন্তা? কয়েক সেকেন্ডে লিখে রাখুন।"), 13f, C.muted), lp(MATCH, WRAP, t = a.dp(4)))
        val noteRow = LinearLayout(a)
        noteRow.gravity = Gravity.CENTER_VERTICAL
        noteEdit = a.edit(L.t("Write a note…", "নোট লিখুন…"), false)
        noteEdit.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        noteRow.addView(noteEdit, lp(0, WRAP, weight = 1f, r = a.dp(10)))
        val noteSave = a.outlineBtn(L.t("Save", "সেভ"), C.primary)
        noteSave.setOnClickListener { saveNote() }
        noteRow.addView(noteSave, lp(WRAP, a.dp(48)))
        note.addView(noteRow, lp(MATCH, WRAP, t = a.dp(10)))
        noteBox = LinearLayout(a)
        noteBox.orientation = LinearLayout.VERTICAL
        note.addView(noteBox, lp(MATCH, WRAP, t = a.dp(6)))
        col.addView(note, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- time capsule: a letter to your future self, sealed until a date ----
        val capsule = a.card(20)
        capsule.addView(a.tv(L.t("Time capsule", "টাইম ক্যাপসুল"), 18f, C.text, 1))
        capsule.addView(
            a.tv(L.t("Write to your future self — the letter opens on its date.", "ভবিষ্যতের নিজেকে চিঠি লিখুন — তারিখ এলে চিঠি খুলবে।"), 13f, C.muted),
            lp(MATCH, WRAP, t = a.dp(4))
        )
        capsuleBox = LinearLayout(a)
        capsuleBox.orientation = LinearLayout.VERTICAL
        capsule.addView(capsuleBox, lp(MATCH, WRAP, t = a.dp(6)))
        val capsuleAdd = a.outlineBtn(L.t("+ Write a letter", "+ চিঠি লিখুন"), C.primary)
        capsuleAdd.setOnClickListener { createCapsuleDialog() }
        capsule.addView(capsuleAdd, lp(MATCH, a.dp(48), t = a.dp(12)))
        col.addView(capsule, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- mini-games: five one-minute games for a break ----
        val games = a.card(20)
        games.addView(a.tv(L.t("Mini-games", "মিনি-গেম"), 18f, C.text, 1))
        games.addView(
            a.tv(L.t("Quick one-minute games for a break.", "বিরতির জন্য এক মিনিটের ছোট খেলা।"), 13f, C.muted),
            lp(MATCH, WRAP, t = a.dp(4))
        )
        fun gameRow(title: String, sub: String, open: () -> Unit) {
            val chev = a.tv("›", 22f, C.muted, 1)
            games.addView(a.row(title, sub, chev, open))
        }
        gameRow(L.t("Memory cards", "মেমোরি কার্ড"), L.t("Match all 6 pairs", "৬ জোড়া মিলান")) { MiniGames.showMemory(a) }
        gameRow(L.t("Colour-word", "রঙ-শব্দ"), L.t("Tap the colour, not the word", "শব্দ নয়, রঙটা চাপুন")) { MiniGames.showStroop(a) }
        gameRow(L.t("Math quiz", "গণিত কুইজ"), L.t("10 quick sums", "১০টা দ্রুত অঙ্ক")) { MiniGames.showMath(a) }
        gameRow(L.t("Reaction game", "প্রতিক্রিয়া খেলা"), L.t("How fast can you tap?", "কত দ্রুত চাপতে পারো?")) { MiniGames.showReaction(a) }
        gameRow(L.t("Daily riddle", "দিনের ধাঁধা"), L.t("One riddle a day", "প্রতিদিন একটা ধাঁধা")) { MiniGames.showRiddle(a) }
        col.addView(games, lp(MATCH, WRAP, t = a.dp(16)))

        // ---- duration ----
        col.addView(a.heading(L.t("Select Lock Duration", "লক সময় বাছুন")), lp(MATCH, WRAP, t = a.dp(26), b = a.dp(4)))
        val row1 = LinearLayout(a)
        val row2 = LinearLayout(a)
        for ((i, m) in quick.withIndex()) {
            val c = a.chip(quickLabel(m), false)
            c.setOnClickListener { pick(m) }
            chips.add(c)
            val rm = if (i == 2) 0 else a.dp(10)
            val target = if (i < 3) row1 else row2
            target.addView(c, lp(0, a.dp(56), r = rm, t = a.dp(10), weight = 1f))
        }
        val custom = a.chip(L.t("Custom", "কাস্টম"), false)
        custom.setOnClickListener { customDialog() }
        chips.add(custom)
        row2.addView(custom, lp(0, a.dp(56), t = a.dp(10), weight = 1f))
        col.addView(row1)
        col.addView(row2)
        previewText = a.tv("", 13f, C.muted)
        col.addView(previewText, lp(MATCH, WRAP, t = a.dp(12)))
        lastSummaryText = a.tv("", 13f, C.mint)
        col.addView(lastSummaryText, lp(MATCH, WRAP, t = a.dp(6)))
        // live screen time today: stored totals + the app open right now
        screenTimeText = a.tv("", 13f, C.muted)
        col.addView(screenTimeText, lp(MATCH, WRAP, t = a.dp(4)))
        // focus score today (0–100): tap to see the breakdown in Statistics
        scoreText = a.tv("", 13f, C.primary, 1)
        scoreText.setOnClickListener { a.go(2) }
        scoreText.pressFeedback()
        col.addView(scoreText, lp(MATCH, WRAP, t = a.dp(4)))

        // ---- unlock condition: time only, location only, or either one ----
        col.addView(a.heading(L.t("When does the lock open?", "লক খুলবে কখন?")), lp(MATCH, WRAP, t = a.dp(26), b = a.dp(4)))
        val modeRow = LinearLayout(a)
        val modeLabels = listOf(
            L.t("Time only", "শুধু সময়"),
            L.t("Location only", "শুধু লোকেশন"),
            L.t("Either one", "যেকোনো একটা")
        )
        for ((i, m) in modes.withIndex()) {
            val ch = a.chip(modeLabels[i], false)
            ch.setOnClickListener { pickMode(m) }
            modeChips.add(ch)
            modeRow.addView(ch, lp(0, a.dp(56), r = if (i < 2) a.dp(10) else 0, t = a.dp(10), weight = 1f))
        }
        col.addView(modeRow)
        // destination row (only for the location modes): name opens the picker, radius is tappable
        destRow = LinearLayout(a)
        destRow.orientation = LinearLayout.HORIZONTAL
        destRow.gravity = Gravity.CENTER_VERTICAL
        destRow.background = rounded(C.card2, a.dp(18).toFloat())
        destRow.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
        destRow.addView(IconView(a, Ic.PIN, C.primary), lp(a.dp(26), a.dp(26), r = a.dp(12)))
        val destCol = LinearLayout(a)
        destCol.orientation = LinearLayout.VERTICAL
        destNameText = a.tv("", 16f, C.text, 1)
        destCol.addView(destNameText)
        destRadiusText = a.tv("", 12f, C.primary)
        destRadiusText.setOnClickListener { pickRadius() }
        destCol.addView(destRadiusText)
        destRow.addView(destCol, lp(0, WRAP, weight = 1f))
        destRow.addView(a.tv("›", 22f, C.muted, 1))
        destRow.setOnClickListener { pickDestination() }
        destRow.pressFeedback()
        col.addView(destRow, lp(MATCH, WRAP, t = a.dp(10)))

        // ---- lock profile: extra apps blocked for this session ----
        profileRow = LinearLayout(a)
        profileRow.orientation = LinearLayout.HORIZONTAL
        profileRow.gravity = Gravity.CENTER_VERTICAL
        profileRow.background = rounded(C.card2, a.dp(18).toFloat())
        profileRow.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
        profileRow.addView(IconView(a, Ic.SHIELD, C.primary), lp(a.dp(26), a.dp(26), r = a.dp(12)))
        val profCol = LinearLayout(a)
        profCol.orientation = LinearLayout.VERTICAL
        profileNameText = a.tv("", 16f, C.text, 1)
        profCol.addView(profileNameText)
        profileSubText = a.tv("", 12f, C.primary)
        profCol.addView(profileSubText)
        profileRow.addView(profCol, lp(0, WRAP, weight = 1f))
        profileRow.addView(a.tv("›", 22f, C.muted, 1))
        profileRow.setOnClickListener { pickProfile() }
        profileRow.pressFeedback()
        col.addView(profileRow, lp(MATCH, WRAP, t = a.dp(10)))

        // ---- presets ----
        col.addView(a.presetsCard { refreshChips(true); tick() })

        // ---- schedule card ----
        val scd = a.card(20)
        scd.addView(a.tv(L.t("Schedule", "সময়সূচি"), 22f, C.text, 1))
        val r1 = LinearLayout(a)
        r1.gravity = Gravity.CENTER_VERTICAL
        r1.setPadding(0, a.dp(18), 0, a.dp(18))
        r1.addView(IconView(a, Ic.CAL, C.primary), lp(a.dp(28), a.dp(28), r = a.dp(14)))
        r1.addView(a.tv(L.t("Weekly Schedule", "সাপ্তাহিক সময়সূচি"), 17f, C.text), lp(0, WRAP, weight = 1f))
        schedPill = a.tv("", 14f, C.primary, 1)
        schedPill.setPadding(a.dp(16), a.dp(8), a.dp(16), a.dp(8))
        r1.addView(schedPill)
        r1.setOnClickListener { a.go(1) }
        scd.addView(r1)
        val dv = View(a)
        dv.setBackgroundColor(C.border)
        scd.addView(dv, lp(MATCH, a.dp(1)))
        val r2 = LinearLayout(a)
        r2.gravity = Gravity.CENTER_VERTICAL
        r2.setPadding(0, a.dp(18), 0, a.dp(2))
        r2.addView(a.tv(L.t("Statistics", "পরিসংখ্যান"), 17f, C.muted), lp(0, WRAP, weight = 1f))
        r2.addView(a.tv(L.t("View details →", "বিস্তারিত →"), 16f, C.primary, 1))
        r2.setOnClickListener { a.go(2) }
        scd.addView(r2)
        col.addView(scd, lp(MATCH, WRAP, t = a.dp(22)))

        refreshChips()
        refreshModeChips()
        refreshProfileRow()
        tick()
        // home wallpaper: the picked photo sits behind the content, dimmed so
        // the text stays readable; no photo (or a broken file) = plain page
        val wall = Wallpaper.loadBitmap(a)
        if (wall == null) return sv
        val wrap = FrameLayout(a)
        val iv = ImageView(a)
        iv.setImageBitmap(wall)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        wrap.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        val scrim = View(a)
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0))
        wrap.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        wrap.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
        return wrap
    }

    private fun refreshChips(animate: Boolean = false) {
        val active = Store.isActive(a)
        // location-only mode has no duration: the chips stay visible but dimmed
        val locOnly = unlockMode() == UNLOCK_LOC
        for ((i, c) in chips.withIndex()) {
            val sel = if (i < quick.size) quick[i] == a.selectedMin else !quick.contains(a.selectedMin)
            if (animate) a.animateChip(c, sel) else a.styleChip(c, sel)
            c.alpha = if (active || locOnly) 0.4f else 1f
        }
    }

    private fun pick(m: Int) {
        if (Store.isActive(a)) return
        if (unlockMode() == UNLOCK_LOC) return
        a.selectedMin = m
        refreshChips(true)
        tick()
    }

    // ---------- unlock condition (time / location / either) ----------

    private fun unlockMode(): String = Store.settings(a).getString("unlock_mode", UNLOCK_TIME) ?: UNLOCK_TIME

    private fun setUnlockMode(m: String) {
        Store.settings(a).edit().putString("unlock_mode", m).apply()
    }

    /** The chosen unlock destination (settings), or null when none was picked yet. */
    private fun unlockDest(): UnlockTarget? {
        val raw = Store.settings(a).getString("unlock_dest", null) ?: return null
        return try {
            val o = JSONObject(raw)
            UnlockTarget(o.getDouble("la"), o.getDouble("lo"), o.getInt("r"), o.getString("n"))
        } catch (e: Exception) {
            Log.e(TAG, "unlock dest read failed", e)
            null
        }
    }

    private fun saveUnlockDest(t: UnlockTarget?) {
        val e = Store.settings(a).edit()
        if (t == null) {
            e.remove("unlock_dest").apply()
            return
        }
        val o = JSONObject()
        o.put("la", t.lat)
        o.put("lo", t.lng)
        o.put("r", t.radius)
        o.put("n", t.name)
        e.putString("unlock_dest", o.toString()).apply()
    }

    private fun refreshModeChips(animate: Boolean = false) {
        val active = Store.isActive(a)
        val mode = unlockMode()
        for ((i, c) in modeChips.withIndex()) {
            val sel = modes[i] == mode
            if (animate) a.animateChip(c, sel) else a.styleChip(c, sel)
            c.alpha = if (active) 0.4f else 1f
        }
        refreshDestRow()
    }

    private fun refreshDestRow() {
        val show = unlockMode() != UNLOCK_TIME
        destRow.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val d = unlockDest()
        destNameText.text = d?.name ?: L.t("Choose a destination", "গন্তব্য বাছুন")
        destRadiusText.text = if (d != null) {
            fmtDistM(d.radius.toFloat()) + " " + L.t("radius · tap to change", "ব্যাসার্ধ · বদলাতে চাপুন")
        } else {
            L.t("Tap to choose", "বাছতে চাপুন")
        }
        destRow.alpha = if (Store.isActive(a)) 0.4f else 1f
    }

    private fun pickMode(m: String) {
        if (Store.isActive(a)) return
        setUnlockMode(m)
        refreshModeChips(true)
        refreshChips()
        tick()
    }

    private fun pickDestination() {
        if (Store.isActive(a)) return
        val places = Extra.places(a)
        val items = ArrayList<String>()
        for (pl in places) items.add(pl.name + "  ·  " + fmtDistM(pl.radius.toFloat()))
        items.add(L.t("My current location", "আমার বর্তমান অবস্থান"))
        items.add(L.t("Open map to add a place", "ম্যাপ খুলে জায়গা যোগ করুন"))
        a.dlg()
            .setTitle(L.t("Unlock destination", "গন্তব্য বাছুন"))
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < places.size -> {
                        val pl = places[which]
                        saveUnlockDest(UnlockTarget(pl.lat, pl.lng, pl.radius, pl.name))
                        refreshDestRow()
                        tick()
                    }
                    which == places.size -> pickCurrentLocation()
                    else -> a.startActivity(Intent(a, MapActivity::class.java))
                }
            }
            .show()
    }

    private fun pickCurrentLocation() {
        if (!Perm.locationOn(a)) {
            a.requestLocationPerm()
            return
        }
        a.toast(L.t("Finding your location…", "আপনার অবস্থান খোঁজা হচ্ছে…"))
        Extra.currentLocation(a) { loc ->
            // the fix can take up to 20 s: if the user pressed Back meanwhile,
            // the activity is dead and tick() must not show dialogs on it
            if (a.isFinishing || a.isDestroyed) return@currentLocation
            if (loc == null) {
                a.toast(L.t("Could not get the location. Turn on GPS and try again outside.", "অবস্থান পাওয়া যায়নি। GPS চালু করে বাইরে আবার চেষ্টা করুন।"))
            } else {
                saveUnlockDest(UnlockTarget(loc.latitude, loc.longitude, 200, L.t("Current location", "বর্তমান অবস্থান")))
                refreshDestRow()
                tick()
            }
        }
    }

    private fun pickRadius() {
        if (Store.isActive(a)) return
        val d = unlockDest() ?: return
        val radii = intArrayOf(100, 200, 500, 1000, 5000, 10000)
        val labels = Array(radii.size) { fmtDistM(radii[it].toFloat()) }
        a.dlg()
            .setTitle(L.t("Radius", "ব্যাসার্ধ"))
            .setItems(labels) { _, which ->
                saveUnlockDest(d.copy(radius = radii[which]))
                refreshDestRow()
                tick()
            }
            .show()
    }

    private fun nextText(now: Long): String {
        val n = Scheduler.nextStart(a, now) ?: return ""
        val c = Calendar.getInstance()
        c.timeInMillis = n.second
        return L.t("Next: ", "পরবর্তী: ") + L.dayLong(dayIdx(c)) + " " + fmtMin(n.first.startMin) + " – " + fmtMin(n.first.endMin)
    }

    private var pillColor = 0
    private val pillBg = GradientDrawable()

    private fun showView(v: View, show: Boolean) {
        val want = if (show) View.VISIBLE else View.GONE
        if (v.visibility == want) return
        v.visibility = want
        if (show) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(Motion.MED).setInterpolator(Motion.EASE).start()
        }
    }

    private fun applyStatus(st: PStatus) {
        if (st == lastStatus) return
        val first = lastStatus == null
        lastStatus = st
        val color = when (st) {
            PStatus.ACTIVE -> C.primary
            PStatus.SCHEDULED -> Color.parseColor("#5AC8FA")
            PStatus.COMPLETED -> C.mint
            PStatus.READY -> C.muted
        }
        val label = when (st) {
            PStatus.ACTIVE -> L.t("PROTECTED", "সুরক্ষিত")
            PStatus.SCHEDULED -> L.t("SCHEDULED", "নির্ধারিত")
            PStatus.COMPLETED -> L.t("COMPLETED", "সম্পন্ন")
            PStatus.READY -> L.t("READY", "প্রস্তুত")
        }
        val from = if (pillColor == 0) color else pillColor
        pillColor = color
        if (first) {
            pillBg.cornerRadius = a.dp(20).toFloat()
            pillBg.setColor(tint(color, 40))
            pill.background = pillBg
            pill.text = label
            pill.setTextColor(color)
            return
        }
        pill.animate().cancel()
        pill.animate().alpha(0f).setDuration(Motion.FAST / 2).withEndAction {
            pill.text = label
            pill.animate().alpha(1f).setDuration(Motion.FAST).start()
        }.start()
        val ev = ArgbEvaluator()
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = Motion.MED
        an.addUpdateListener {
            val c = ev.evaluate(it.animatedFraction, from, color) as Int
            pill.setTextColor(c)
            pillBg.setColor(tint(c, 40))
        }
        an.start()
    }

    private fun updateSchedPill() {
        val on = Store.schedules(a).any { it.enabled }
        if (lastSched == on) return
        lastSched = on
        if (on) {
            schedPill.text = L.t("Enabled", "চালু")
            schedPill.setTextColor(C.primary)
            schedPill.background = rounded(tint(C.primary, 40), a.dp(20).toFloat())
        } else {
            schedPill.text = L.t("Off", "বন্ধ")
            schedPill.setTextColor(C.muted)
            schedPill.background = rounded(C.card2, a.dp(20).toFloat())
        }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        // backup arrival check (the service listener is the main path)
        if (Store.unlockTarget(a) != null) PlaceWatcher.checkUnlockLastKnown(a)
        // routine overdue check (throttled: the window-end alarms are the main path)
        if (now - lastRoutineSweep > 30_000) {
            lastRoutineSweep = now
            Routine.sweep(a)
        }
        val active = Store.isActive(a)
        val st = statusOf(a, active, now)
        applyStatus(st)
        updateSchedPill()
        val p = Store.lock(a)
        var total = p.getLong("total", 1)
        if (total < 1) total = 1
        val strict = active && p.getBoolean("s_strict", false)
        if (active) {
            val mode = Store.unlockMode(a)
            val target = Store.unlockTarget(a)
            if (mode == UNLOCK_LOC && target != null) {
                // no countdown: the lock ends when the user arrives
                ring.active = true
                ring.icon = Ic.PIN
                ring.main = L.t("On the way", "যাত্রায়")
                ring.mainColor = C.text
                ring.sub = if (target.name.length > 14) target.name.take(13) + "…" else target.name
                ring.animateTo(0f)
                titleText.setTextAnimated(L.t("Protection is Active", "প্রটেকশন সক্রিয়"))
                subText.setTextAnimated(L.t("Opens when you arrive at ${target.name}", "লক খুলবে: ${target.name} পৌঁছালে"))
                previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") +
                    L.t("opens at ${target.name}", "${target.name} পৌঁছালে খুলবে")
            } else {
                val left = Store.remainingMs(a)
                ring.active = true
                ring.icon = Ic.LOCK
                ring.main = fmtHms(left)
                ring.mainColor = C.text
                ring.sub = L.t("remaining", "বাকি")
                var frac = left.toFloat() / total.toFloat()
                if (frac < 0f) frac = 0f
                if (frac > 1f) frac = 1f
                ring.animateTo(frac)
                titleText.setTextAnimated(L.t("Protection is Active", "প্রটেকশন সক্রিয়"))
                val endAt = fmtClock(now + left)
                if (mode == UNLOCK_EITHER && target != null) {
                    subText.setTextAnimated(
                        if (strict) L.t("Strict mode · locked until $endAt or arrival at ${target.name}", "স্ট্রিক্ট মোড · $endAt পর্যন্ত অথবা ${target.name} পৌঁছালে")
                        else L.t("Ends at $endAt or at ${target.name}", "শেষ হবে $endAt অথবা ${target.name} পৌঁছালে")
                    )
                    previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total) +
                        L.t(" or at ${target.name}", " অথবা ${target.name} পৌঁছালে")
                } else {
                    subText.setTextAnimated(
                        if (strict) L.t("Strict mode · locked until $endAt", "স্ট্রিক্ট মোড · $endAt পর্যন্ত লক")
                        else L.t("Ends at $endAt", "শেষ হবে $endAt")
                    )
                    previewText.text = L.t("Session in progress · ", "সেশন চলছে · ") + fmtDur(total)
                }
            }
        } else {
            val sel = a.selectedMin * 60_000L
            ring.active = false
            ring.icon = Ic.SHIELD
            ring.main = if (st == PStatus.COMPLETED) L.t("DONE", "সম্পন্ন") else L.t("OFF", "বন্ধ")
            ring.mainColor = C.primary
            ring.sub = ""
            ring.animateTo(0f)
            titleText.setTextAnimated(
                if (st == PStatus.COMPLETED) L.t("Session Completed", "সেশন সম্পন্ন")
                else L.t("Protection is Off", "প্রটেকশন বন্ধ আছে")
            )
            subText.setTextAnimated(when (st) {
                PStatus.SCHEDULED -> nextText(now)
                PStatus.COMPLETED -> L.t("You can start a new session", "নতুন সেশন শুরু করতে পারেন")
                else -> L.t("Start protection to activate PowerLock", "পাওয়ার লক চালু করতে প্রটেকশন শুরু করুন")
            })
            val ends = fmtClock(now + sel)
            val mode = unlockMode()
            val dest = unlockDest()
            previewText.text = when {
                mode == UNLOCK_LOC && dest != null -> L.t("Opens when you arrive at ${dest.name}", "লক খুলবে: ${dest.name} পৌঁছালে")
                mode == UNLOCK_LOC -> L.t("Choose a destination below", "নিচে গন্তব্য বাছুন")
                mode == UNLOCK_EITHER && dest != null -> L.t("Duration ${fmtDur(sel)} · ends at $ends or at ${dest.name}", "সময় ${fmtDur(sel)} · শেষ হবে $ends অথবা ${dest.name} পৌঁছালে")
                mode == UNLOCK_EITHER -> L.t("Duration ${fmtDur(sel)} · ends at $ends · choose a destination", "সময় ${fmtDur(sel)} · শেষ হবে $ends · গন্তব্য বাছুন")
                else -> L.t("Duration ${fmtDur(sel)} · ends at $ends", "সময় ${fmtDur(sel)} · শেষ হবে $ends")
            }
        }
        ring.invalidate()
        showView(startBtn, !active)
        showView(stopBtn, active && !strict)
        // location-only sessions have no timer to extend
        showView(extendBtn, active && Store.unlockMode(a) != UNLOCK_LOC)
        if (lastActive != active) {
            lastActive = active
            refreshChips()
            refreshModeChips()
            refreshProfileRow()
        }
        updateWarnings()
        updateLastSummary()
        updateScreenTime()
        updateFocusScore()
        updateTree()
        updateGardenAnimals()
        updatePet()
        updateStory()
        updateWheel()
        StreakProtection.check(a) // once a day: spend a token to save a breaking streak
        updateNotes()
        updateCapsules()
        // capsule due check (throttled: a capsule may open while the app is open)
        if (now - lastCapsuleCheck > 60_000) {
            lastCapsuleCheck = now
            TimeCapsules.checkDue(a)
        }
        updateRoutine()
        maybeShowTreeOutcome()
        maybeShowSummary()
        maybeShowPetReaction()
        maybeShowBadge()
        maybeCelebrateRoutine()
    }

    /** Live screen time today: the stored day total (re-read at most every 10 s)
     * plus the app open right now. Screen-off time never counts. */
    private fun updateScreenTime() {
        val now = System.currentTimeMillis()
        val day = Store.dayKey(0)
        if (cachedUsageSec < 0 || cachedUsageDay != day || now - cachedUsageAt > 10_000) {
            cachedUsageSec = Extra.usageDayTotal(a, day)
            cachedUsageDay = day
            cachedUsageAt = now
        }
        var total = cachedUsageSec
        val (livePkg, liveSince, liveDay) = Extra.liveSlice(a)
        if (livePkg != null && liveDay == day) {
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) {
                total += ((SystemClock.uptimeMillis() - liveSince) / 1000).coerceAtLeast(0L)
            }
        }
        screenTimeText.text = L.t("Screen time today: ", "আজকের স্ক্রিন টাইম: ") + fmtDur(total * 1000L)
    }

    /** Today's focus score (0–100), recomputed at most every 10 s. */
    private fun updateFocusScore() {
        val now = System.currentTimeMillis()
        if (cachedScore == null || now - cachedScoreAt > 10_000) {
            cachedScore = FocusScore.today(a)
            cachedScoreAt = now
            FocusScore.recordToday(a, cachedScore!!)
        }
        val b = cachedScore ?: return
        scoreText.text = L.t("Focus score today: ", "আজকের ফোকাস স্কোর: ") + L.num("${b.total}/100")
    }

    // ---------- lock profile (extra apps for this session) ----------

    /** The profile chosen for the next lock (-1 = none). A deleted profile reads as none. */
    private fun selectedProfileId(): Long {
        val id = Store.settings(a).getLong("lock_profile", -1L)
        if (id <= 0) return -1L
        return if (Extra.lockProfiles(a).any { it.id == id }) id else -1L
    }

    private fun refreshProfileRow() {
        val active = Store.isActive(a)
        val sessionName = Store.sessionProfileName(a)
        val chosen = if (selectedProfileId() > 0) Extra.lockProfiles(a).find { it.id == selectedProfileId() } else null
        val sig = "$active:$sessionName:${chosen?.id ?: -1}:${Extra.lockProfiles(a).size}"
        if (sig == profileSig) return
        profileSig = sig
        profileRow.alpha = if (active) 0.4f else 1f
        when {
            active && sessionName != null -> {
                profileNameText.text = L.t("Profile: ", "প্রোফাইল: ") + sessionName
                profileSubText.text = L.t("This session", "এই সেশনে")
            }
            chosen != null -> {
                profileNameText.text = L.t("Profile: ", "প্রোফাইল: ") + chosen.name
                profileSubText.text = L.num(chosen.pkgs.size.toString()) + " " + L.t("extra apps · tap to change", "টি অতিরিক্ত অ্যাপ · বদলাতে চাপুন")
            }
            Extra.lockProfiles(a).isEmpty() -> {
                profileNameText.text = L.t("Profile: none", "প্রোফাইল: কোনোটা নয়")
                profileSubText.text = L.t("Create one in the Protection tab", "সুরক্ষা ট্যাবে প্রোফাইল বানান")
            }
            else -> {
                profileNameText.text = L.t("Profile: none", "প্রোফাইল: কোনোটা নয়")
                profileSubText.text = L.t("Tap to choose", "বাছতে চাপুন")
            }
        }
    }

    private fun pickProfile() {
        if (Store.isActive(a)) return
        val profiles = Extra.lockProfiles(a)
        if (profiles.isEmpty()) {
            a.toast(L.t("Create a profile in the Protection tab first", "আগে সুরক্ষা ট্যাবে একটা প্রোফাইল বানান"))
            a.go(4)
            return
        }
        val items = ArrayList<String>()
        items.add(L.t("No profile", "কোনো প্রোফাইল নয়"))
        for (p in profiles) items.add(p.name + "  ·  " + L.num(p.pkgs.size.toString()) + " " + L.t("apps", "টি অ্যাপ"))
        a.dlg()
            .setTitle(L.t("Lock profile", "লক প্রোফাইল"))
            .setItems(items.toTypedArray()) { _, which ->
                val id = if (which == 0) -1L else profiles[which - 1].id
                Store.settings(a).edit().putLong("lock_profile", id).apply()
                profileSig = ""
                refreshProfileRow()
                tick()
            }
            .show()
    }

    /** One line under the duration preview: what the last completed lock achieved. */
    private fun updateLastSummary() {
        val s = Store.lastSummary(a)
        val sig = if (s == null) "" else "${s.endMs}:${s.durMs}:${s.blocks}"
        if (sig == lastSummarySig) return
        lastSummarySig = sig
        if (s == null) {
            lastSummaryText.text = ""
            return
        }
        val blocksTxt = if (s.blocks > 0) {
            L.num(s.blocks.toString()) + " " + L.t("blocks", "বার ব্লক")
        } else {
            L.t("no blocks needed", "কোনো ব্লক লাগেনি")
        }
        lastSummaryText.text = L.t("Last session: ", "গত সেশন: ") + fmtDur(s.durMs) + " · " + blocksTxt
    }

    /** The focus tree: live while a lock runs, else the garden's newest tree. */
    private fun updateTree() {
        val active = Store.isActive(a)
        if (active && FocusTree.isPlanted(a)) {
            val frac = FocusTree.currentFraction(a)
            val stage = FocusTree.stageFor(frac)
            val sp = TreeSpecies.selected(a)
            treeView.frac = frac
            treeView.alive = true
            if (treeView.species != sp) treeView.species = sp
            treeView.invalidate()
            // label every tick: the % keeps counting inside a stage
            treeSig = "live:$stage:$sp"
            treeLabel.text = FocusTree.stageName(stage) + " · " +
                L.num((frac * 100).toInt().toString()) + "%"
            return
        }
        val last = FocusTree.garden(a).firstOrNull()
        if (last == null) {
            val sig = "empty"
            if (sig != treeSig) {
                treeSig = sig
                treeView.frac = 0f
                treeView.alive = true
                treeView.species = TreeSpecies.selected(a)
                treeView.invalidate()
                treeLabel.text = L.t("Start a lock and a seed will be planted", "লক শুরু করলে বীজ রোপণ হবে")
            }
            return
        }
        val sig = "garden:${last.endMs}"
        if (sig != treeSig) {
            treeSig = sig
            treeView.frac = last.stage * 0.25f
            treeView.alive = last.alive
            treeView.species = last.species
            treeView.invalidate()
            treeLabel.text = FocusTree.stageName(last.stage) + " · " + fmtDate(last.endMs) +
                if (last.alive) "" else " · " + L.t("withered", "শুকিয়ে গেছে")
        }
    }

    /** The garden animals living by the tree (small icons, newest first). */
    private fun updateGardenAnimals() {
        val animals = GardenAnimals.unlocked(a)
        val sig = animals.joinToString(",") { it.id }
        if (sig == animalsSig) return
        animalsSig = sig
        animalsRow.removeAllViews()
        for (an in animals) {
            val v = AnimalView(a, an.id)
            v.setOnClickListener { a.toast(L.t(an.nameEn, an.nameBn)) }
            animalsRow.addView(v, lp(a.dp(44), a.dp(44), r = a.dp(6)))
        }
        animalsRow.visibility = if (animals.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The focus pet: face follows the mood, bar follows happiness. */
    private fun updatePet() {
        val happy = FocusPet.happiness(a)
        val mood = FocusPet.mood(a)
        val name = FocusPet.name(a)
        val sig = "$name:$happy:$mood"
        if (sig == petSig) return
        petSig = sig
        petNameText.text = name
        petView.mood = mood
        petView.invalidate()
        petBar.frac = happy / 100f
        petMoodText.text = FocusPet.moodLabel(a) + " · " +
            L.num(happy.toString()) + "/100 · " +
            L.t("tap to rename", "নাম বদলাতে চাপুন")
    }

    /** Rename the pet (a short name, up to 20 characters). */
    private fun renamePetDialog() {
        val et = a.edit(L.t("Pet name", "পোষা প্রাণীর নাম"), false)
        et.setText(FocusPet.name(a))
        et.setSelection(et.text.length)
        a.dlg()
            .setTitle(L.t("Name your pet", "পোষা প্রাণীর নাম দিন"))
            .setView(et)
            .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    FocusPet.setName(a, n)
                    petSig = ""
                    updatePet()
                }
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Pick a tree species (won from the lucky wheel; classic is always open). */
    private fun speciesDialog() {
        val all = TreeSpecies.ALL
        val unlocked = TreeSpecies.unlocked(a).map { it.id }.toSet()
        val items = all.map { sp ->
            L.t(sp.nameEn, sp.nameBn) +
                if (sp.id in unlocked) "" else " (" + L.t("locked", "তালাবদ্ধ") + ")"
        }.toTypedArray()
        a.dlg()
            .setTitle(L.t("Tree species", "গাছের প্রজাতি"))
            .setItems(items) { _, which ->
                val sp = all[which]
                if (sp.id !in unlocked) {
                    a.toast(L.t("Win this species from the lucky wheel", "এই প্রজাতি লাকি হুইল থেকে জিতুন"))
                    return@setItems
                }
                TreeSpecies.select(a, sp.id)
                treeSig = ""
                tick()
            }
            .setNegativeButton(L.t("Close", "বন্ধ"), null)
            .show()
    }

    /** Story progress: chapters unlocked out of 10. */
    private fun updateStory() {
        val unlocked = Story.unlockedCount(a)
        val sig = "$unlocked"
        if (sig == storySig) return
        storySig = sig
        storySubText.text = L.num(unlocked.toString()) + "/" + L.num(Story.TOTAL.toString()) + " " +
            L.t("chapters unlocked · tap to read", "টি অধ্যায় খুলেছে · পড়তে চাপুন")
    }

    /** Lucky wheel spins available (the button dims when there are none). */
    private fun updateWheel() {
        val spins = LuckyWheel.spins(a)
        val sig = "$spins"
        if (sig == wheelSig) return
        wheelSig = sig
        wheelSpinsText.text = if (spins > 0) {
            L.num(spins.toString()) + " " + L.t("spin(s) available", "টি স্পিন আছে")
        } else {
            L.t("No spins — complete a lock to earn one!", "স্পিন নেই — একটা লক সম্পন্ন করে স্পিন পান!")
        }
        wheelBtn.alpha = if (spins > 0) 1f else 0.5f
    }

    /** One-time pet reaction after a lock ends: sad = toast, happy rides in the summary dialog. */
    private fun maybeShowPetReaction() {
        when (FocusPet.reactPending(a)) {
            "sad" -> {
                FocusPet.clearReact(a)
                a.toast(
                    FocusPet.name(a) + " " +
                        L.t("is sad — the lock was stopped early", "দুঃখী — লক আগেই বন্ধ করা হয়েছে")
                )
            }
            "happy" -> {
                // the summary dialog carries the happy line; if no summary is
                // pending (edge case), just drop the flag
                if (!Store.summaryPending(a)) FocusPet.clearReact(a)
            }
        }
    }

    /** One-time tree message after a lock ends: withered = toast, survived = in the summary dialog. */
    private fun maybeShowTreeOutcome() {
        when (FocusTree.outcomePending(a)) {
            "dead" -> {
                FocusTree.clearOutcome(a)
                a.toast(L.t("The tree withered", "গাছটা মরে গেল"))
            }
            "alive" -> {
                // the summary dialog carries the survived message; if no summary
                // is pending (edge case), just drop the flag
                if (!Store.summaryPending(a)) FocusTree.clearOutcome(a)
            }
        }
    }

    /** Shows the session-summary dialog once, the first time Home ticks after a lock completes. */
    private fun maybeShowSummary() {
        if (summaryShown) return
        if (!Store.summaryPending(a)) return
        val s = Store.lastSummary(a) ?: run {
            Store.clearSummaryPending(a)
            return
        }
        summaryShown = true
        Store.clearSummaryPending(a)
        val treeLine = if (FocusTree.outcomePending(a) == "alive") {
            FocusTree.clearOutcome(a)
            "\n\n" + L.t("Your tree survived!", "তোমার গাছ বেঁচে গেল!")
        } else ""
        val petLine = if (FocusPet.reactPending(a) == "happy") {
            FocusPet.clearReact(a)
            "\n\n" + FocusPet.name(a) + " " + L.t("is happy!", "খুশি!") + " · " +
                L.num(FocusPet.happiness(a).toString()) + "/100"
        } else ""
        val newChapters = Story.newUnlocks(a)
        val storyLine = if (newChapters > 0) {
            val unlocked = Story.unlockedCount(a)
            Story.markSeen(a, unlocked)
            if (newChapters == 1) {
                val ch = Story.CHAPTERS[(unlocked - 1).coerceAtLeast(0)]
                "\n\n" + L.t("New story chapter unlocked: ", "নতুন গল্পের অধ্যায় খুলেছে: ") +
                    L.t(ch.titleEn, ch.titleBn)
            } else {
                "\n\n" + L.t(
                    "$newChapters new story chapters unlocked!",
                    "${L.num(newChapters.toString())}টি নতুন গল্পের অধ্যায় খুলেছে!"
                )
            }
        } else ""
        val newMiles = FocusJourney.newMilestones(a)
        val journeyLine = if (newMiles > 0) {
            val latest = FocusJourney.milestoneAt(FocusJourney.passedCount(a) - 1)
            FocusJourney.markSeen(a)
            if (latest.km == FocusJourney.ROUTE_KM) {
                val (journeysDone, _, _) = FocusJourney.progress(a)
                "\n\n" + L.t(
                    "Journey complete! Dhaka to Cox's Bazar — $journeysDone journey(s) done!",
                    "যাত্রা সম্পূর্ণ! ঢাকা থেকে কক্সবাজার পৌঁছে গেছো — ${L.num(journeysDone.toString())}টি যাত্রা শেষ!"
                )
            } else if (newMiles == 1) {
                "\n\n" + L.t("Milestone reached: ", "মাইলফলক: ") + L.t(latest.nameEn, latest.nameBn)
            } else {
                "\n\n" + L.t(
                    "$newMiles milestones passed! Latest: ",
                    "${L.num(newMiles.toString())}টি মাইলফলক পার করেছো! সর্বশেষ: "
                ) + L.t(latest.nameEn, latest.nameBn)
            }
        } else ""
        val blocksTxt = if (s.blocks > 0) {
            L.num(s.blocks.toString()) + " " + L.t("times an app was blocked", "বার অ্যাপ ব্লক হয়েছে")
        } else {
            L.t("No app needed blocking", "কোনো অ্যাপ ব্লক করতে হয়নি")
        }
        val quotePair = Quotes.randomPair()
        val message =
            L.t("Focus time: ", "ফোকাস সময়: ") + fmtDur(s.durMs) + "\n" + blocksTxt +
                treeLine + petLine + storyLine + journeyLine + "\n\n" +
                L.t(quotePair.first, quotePair.second) +
                "\n\n" + L.t("Fun fact: ", "মজার তথ্য: ") + FunFacts.random(a)
        // custom view: the message + a "listen" button for the quote
        val msgBox = LinearLayout(a)
        msgBox.orientation = LinearLayout.VERTICAL
        msgBox.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        msgBox.addView(a.tv(message, 15f, C.text))
        val actRow = LinearLayout(a)
        actRow.gravity = Gravity.CENTER_VERTICAL
        val listenBtn = a.outlineBtn(L.t("Listen", "শোনাও"), C.primary)
        listenBtn.setOnClickListener { VoiceQuotes.speak(a, quotePair.first, quotePair.second) }
        actRow.addView(listenBtn, lp(0, a.dp(48), weight = 1f))
        msgBox.addView(actRow, lp(MATCH, WRAP, t = a.dp(14)))
        val scroll = ScrollView(a)
        scroll.addView(msgBox)
        val builder = a.dlg()
            .setTitle(L.t("Session completed!", "সেশন সম্পন্ন!"))
            .setView(scroll)
        if (LuckyWheel.spins(a) > 0) {
            builder.setNeutralButton(L.t("Spin the wheel", "চাকা ঘোরাও")) { _, _ ->
                LuckyWheel.showWheel(a) { tick() }
            }
        }
        builder
            .setPositiveButton(L.t("Great!", "ভালো হয়েছে!"), null)
            .show()
    }

    /** Focus notes, newest first. Rebuilds only when the notes change. */
    private fun updateNotes() {
        val notes = Store.focusNotes(a).asReversed()
        val sig = notes.joinToString("|") { "${it.id}:${it.text.hashCode()}" }
        if (sig == noteSig) return
        noteSig = sig
        noteBox.removeAllViews()
        if (notes.isEmpty()) {
            noteBox.addView(
                a.tv(L.t("No notes yet. A thought during a lock goes here — it takes seconds.", "এখনো কোনো নোট নেই। লকের সময়ের চিন্তা এখানে লিখুন — কয়েক সেকেন্ড লাগে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(4))
            )
            return
        }
        for (n in notes) {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(8))
            val text = a.tv(n.text, 15f, C.text)
            text.maxLines = 2
            text.ellipsize = TextUtils.TruncateAt.END
            r.addView(text)
            val meta = fmtDate(n.time) + " · " + fmtClock(n.time) +
                if (n.duringLock) "  ·  " + L.t("During lock", "লকের সময়") else ""
            r.addView(a.tv(meta, 12f, if (n.duringLock) C.mint else C.muted), lp(MATCH, WRAP, t = a.dp(2)))
            r.pressFeedback()
            r.setOnClickListener { viewNoteDialog(n) }
            r.setOnLongClickListener {
                deleteNoteDialog(n)
                true
            }
            noteBox.addView(r)
        }
    }

    private fun saveNote() {
        val text = noteEdit.text.toString().trim()
        if (text.isEmpty()) {
            a.toast(L.t("Write something first", "আগে কিছু লিখুন"))
            return
        }
        Store.addFocusNote(a, text, Store.isActive(a))
        noteEdit.setText("")
        updateNotes()
    }

    private fun viewNoteDialog(n: FocusNote) {
        a.dlg()
            .setTitle(fmtDate(n.time) + " · " + fmtClock(n.time))
            .setMessage(n.text)
            .setPositiveButton(L.t("Close", "বন্ধ করুন"), null)
            .show()
    }

    private fun deleteNoteDialog(n: FocusNote) {
        a.dlg()
            .setTitle(L.t("Delete this note?", "এই নোট মুছে ফেলবেন?"))
            .setMessage(if (n.text.length > 80) n.text.take(77) + "…" else n.text)
            .setPositiveButton(L.t("Delete", "মুছে ফেলুন")) { _, _ ->
                Store.deleteFocusNote(a, n.id)
                updateNotes()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    // ---- time capsule: sealed letters list + create/view/delete ----

    private fun updateCapsules() {
        val list = TimeCapsules.all(a).asReversed()
        val sig = Store.dayKey(0) + "|" + list.joinToString("|") { "${it.id}:${it.open}:${it.opened}" }
        if (sig == capsuleSig) return
        capsuleSig = sig
        capsuleBox.removeAllViews()
        if (list.isEmpty()) {
            capsuleBox.addView(
                a.tv(L.t("No letters yet. Write one to your future self — it opens on its date.", "এখনো কোনো চিঠি নেই। ভবিষ্যতের নিজেকে একটা চিঠি লিখুন — তারিখ এলে খুলবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(4))
            )
            return
        }
        val now = System.currentTimeMillis()
        for (c in list) {
            val due = TimeCapsules.isDue(c, now)
            val r = LinearLayout(a)
            r.orientation = LinearLayout.VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(8))
            val title = if (due) {
                L.t("Open now!", "এখন খুলুন!") + " · " + fmtDate(c.open)
            } else {
                L.t("Opens: ", "খুলবে: ") + fmtDate(c.open)
            }
            r.addView(a.tv(title, 15f, if (due) C.mint else C.text, if (due) 1 else 0))
            val sub = if (c.opened) L.t("Opened", "খোলা হয়েছে") else TimeCapsules.countdown(c, now)
            r.addView(a.tv(sub, 12f, C.muted), lp(MATCH, WRAP, t = a.dp(2)))
            r.pressFeedback()
            r.setOnClickListener {
                if (due) {
                    TimeCapsules.markOpened(a, c.id)
                    viewCapsuleDialog(c)
                    updateCapsules()
                } else {
                    // sealed: show only the countdown, never the content
                    a.dlg()
                        .setTitle(L.t("Still sealed", "এখনো সিল করা"))
                        .setMessage(
                            L.t("This letter opens on ", "এই চিঠি খুলবে ") + fmtDate(c.open) +
                                "\n" + TimeCapsules.countdown(c, System.currentTimeMillis())
                        )
                        .setPositiveButton(L.t("OK", "ঠিক আছে"), null)
                        .show()
                }
            }
            r.setOnLongClickListener {
                deleteCapsuleDialog(c)
                true
            }
            capsuleBox.addView(r)
        }
    }

    private fun viewCapsuleDialog(c: TimeCapsule) {
        a.dlg()
            .setTitle(L.t("A letter from your past self", "তোমার অতীতের নিজের চিঠি"))
            .setMessage(L.t("Written: ", "লেখা: ") + fmtDate(c.created) + "\n\n" + c.text)
            .setPositiveButton(L.t("Close", "বন্ধ করুন"), null)
            .show()
    }

    private fun deleteCapsuleDialog(c: TimeCapsule) {
        a.dlg()
            .setTitle(L.t("Delete this letter?", "এই চিঠি মুছে ফেলবেন?"))
            .setMessage(
                if (TimeCapsules.isDue(c)) L.t("This letter has already opened.", "এই চিঠি এরই মধ্যে খুলে গেছে।")
                else L.t("Opens: ", "খুলবে: ") + fmtDate(c.open)
            )
            .setPositiveButton(L.t("Delete", "মুছে ফেলুন")) { _, _ ->
                TimeCapsules.delete(a, c.id)
                updateCapsules()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Seals a new letter: the text + an open date (7/30/90 days, or a picked date). */
    private fun createCapsuleDialog() {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        val et = a.edit(L.t("Write to your future self…", "ভবিষ্যতের নিজেকে লিখুন…"), false)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.gravity = Gravity.START or Gravity.TOP
        et.minLines = 4
        box.addView(et, lp(MATCH, WRAP))
        box.addView(a.tv(L.t("Opens in", "খুলবে"), 13f, C.muted), lp(WRAP, WRAP, t = a.dp(14)))
        var openMs = Store.dayStart(System.currentTimeMillis(), 30)
        val dateVal = a.tv(fmtDate(openMs), 16f, C.primary, 1)
        val chips = ArrayList<TextView>()
        fun styleDateChips() {
            val now = System.currentTimeMillis()
            val days = listOf(7, 30, 90)
            for (i in chips.indices) {
                val sel = openMs == Store.dayStart(now, days[i])
                chips[i].background = rounded(if (sel) C.selFill else C.card2, a.dp(12).toFloat())
                chips[i].setTextColor(if (sel) C.mint else C.muted)
            }
            dateVal.setTextAnimated(fmtDate(openMs))
        }
        val chipRow = LinearLayout(a)
        for ((i, d) in listOf(7, 30, 90).withIndex()) {
            val ch = a.tv(L.num(d.toString()) + " " + L.t("days", "দিন"), 14f, C.muted, 1)
            ch.gravity = Gravity.CENTER
            ch.setPadding(0, a.dp(10), 0, a.dp(10))
            ch.setOnClickListener {
                openMs = Store.dayStart(System.currentTimeMillis(), d)
                styleDateChips()
            }
            ch.pressFeedback()
            chips.add(ch)
            chipRow.addView(ch, lp(0, WRAP, weight = 1f, r = if (i < 2) a.dp(8) else 0))
        }
        box.addView(chipRow, lp(MATCH, WRAP, t = a.dp(8)))
        val pickBtn = a.outlineBtn(L.t("Pick a date", "তারিখ বাছুন"), C.primary)
        pickBtn.setOnClickListener {
            val c = Calendar.getInstance()
            c.timeInMillis = openMs
            val dlg = DatePickerDialog(
                a,
                { _, y, m, d ->
                    val cal = Calendar.getInstance()
                    cal.set(y, m, d, 0, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    openMs = cal.timeInMillis
                    styleDateChips()
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            )
            dlg.datePicker.minDate = Store.dayStart(System.currentTimeMillis(), 1)
            dlg.show()
        }
        box.addView(pickBtn, lp(MATCH, a.dp(48), t = a.dp(10)))
        val dateRow = LinearLayout(a)
        dateRow.gravity = Gravity.CENTER_VERTICAL
        dateRow.addView(a.tv(L.t("Opens on ", "খুলবে "), 14f, C.text), lp(WRAP, WRAP))
        dateRow.addView(dateVal, lp(WRAP, WRAP, l = a.dp(4)))
        box.addView(dateRow, lp(MATCH, WRAP, t = a.dp(12)))
        styleDateChips()
        a.dlg()
            .setTitle(L.t("Time capsule", "টাইম ক্যাপসুল"))
            .setView(box)
            .setPositiveButton(L.t("Seal the letter", "চিঠি সিল করুন")) { _, _ ->
                if (TimeCapsules.add(a, et.text.toString(), openMs)) {
                    updateCapsules()
                    a.toast(L.t("Sealed! It opens on ", "সিল হয়ে গেছে! খুলবে ") + fmtDate(openMs))
                } else {
                    a.toast(L.t("Write something first", "আগে কিছু লিখুন"))
                }
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Today's routine tasks: tap to check off. Overdue tasks show in warn colour;
     * a running penalty lock names the task that caused it. */
    private fun updateRoutine() {
        val tasks = Routine.tasksToday(a)
        val day = Store.dayKey(0)
        val done = Routine.doneIds(a, day)
        val missed = Routine.missedIds(a, day)
        val pen = Routine.isPenaltyLock(a)
        val penTitle = Routine.penaltyTaskTitle(a)
        val sig = "$day#" + tasks.joinToString("|") { "${it.id}:${it.title}:${it.timeMin}:${it.graceMin}" } + "#" +
            tasks.joinToString("|") { if (it.id in done) "1" else if (it.id in missed) "2" else "0" } +
            "#$pen:$penTitle"
        if (sig == routineSig) return
        routineSig = sig
        val s = Routine.streak(a)
        routineStreakText.text = if (s > 0) L.num(s.toString()) + " " + L.t("day routine streak", "দিনের রুটিন স্ট্রিক") else ""
        routineStreakText.visibility = if (s > 0) View.VISIBLE else View.GONE
        routineBox.removeAllViews()
        if (tasks.isEmpty()) {
            routineBox.addView(
                a.tv(L.t("No routine yet. Build one in the Schedule tab — miss a task and the phone locks until you do it.", "এখনো রুটিন নেই। সময়সূচি ট্যাবে বানান — কাজ বাদ গেলে ফোন লক হবে, কাজ শেষ করলে খুলবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(4))
            )
            return
        }
        if (pen && penTitle != null) {
            routineBox.addView(
                a.tv(
                    L.t("Locked: \"$penTitle\" is overdue. Finish it and check it off to unlock.", "লক হয়েছে: \"$penTitle\" বাকি আছে। কাজ শেষ করে টিক দিলে লক খুলবে।"),
                    13f, C.warn, 1
                ),
                lp(MATCH, WRAP, t = a.dp(4), b = a.dp(4))
            )
        }
        for (t in tasks) {
            val isDone = t.id in done
            val isMissed = t.id in missed
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(8), 0, a.dp(8))
            val box = a.tv(if (isDone) "☑" else "☐", 20f, if (isDone) C.mint else if (isMissed) C.warn else C.muted, 1)
            r.addView(box, lp(WRAP, WRAP, r = a.dp(10)))
            val col2 = LinearLayout(a)
            col2.orientation = LinearLayout.VERTICAL
            val title = a.tv(t.title, 15f, if (isDone) C.muted else C.text)
            if (isDone) title.paintFlags = title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            col2.addView(title)
            val meta = fmtMin(t.timeMin) + if (isMissed && !isDone) "  ·  " + L.t("overdue", "বাকি আছে") else ""
            col2.addView(a.tv(meta, 12f, if (isMissed && !isDone) C.warn else C.primary))
            r.addView(col2, lp(0, WRAP, weight = 1f))
            r.pressFeedback()
            r.setOnClickListener {
                Routine.toggleDone(a, t.id)
                routineSig = ""
                updateRoutine()
                tick()
            }
            routineBox.addView(r)
        }
    }

    /** Shows the "new badge" dialog once, the first time Home ticks after one is earned. */
    private fun maybeShowBadge() {
        if (badgeShown) return
        val b = Badges.pending(a) ?: return
        badgeShown = true
        Badges.clearPending(a)
        a.dlg()
            .setTitle(L.t("New badge!", "নতুন ব্যাজ!"))
            .setMessage(L.t(b.titleEn, b.titleBn) + "\n" + L.t(b.descEn, b.descBn))
            .setPositiveButton(L.t("Great!", "ভালো হয়েছে!"), null)
            .show()
    }

    /** Confetti when the day's routine is fully complete (once a day, never after locks). */
    private fun maybeCelebrateRoutine() {
        if (!Confetti.shouldCelebrate(a)) return
        Confetti.markShown(a)
        Confetti.show(a)
    }

    private fun warnCard(icon: String, title: String, sub: String, onClick: () -> Unit): View {
        val c = a.card(12)
        c.background = rounded(Color.parseColor("#2A2415"), a.dp(18).toFloat(), C.warn, a.dp(1))
        val r = LinearLayout(a)
        r.gravity = Gravity.CENTER_VERTICAL
        val ic = a.tv(icon, 22f, C.warn, 1)
        ic.gravity = Gravity.CENTER
        r.addView(ic, lp(a.dp(28), WRAP, r = a.dp(12)))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.addView(a.tv(title, 14f, C.warn, 1))
        col.addView(a.tv(sub, 12f, C.muted))
        r.addView(col, lp(0, WRAP, weight = 1f))
        c.addView(r)
        c.setOnClickListener { onClick() }
        return c
    }

    private fun updateWarnings() {
        val acc = Perm.accessibilityOn(a)
        val bat = Perm.batteryOk(a)
        val adminActive = DeviceAdmin.isActive(a)
        if (adminActive) DeviceAdmin.noteActive(a)
        // warn only while a lock runs: the admin was on, now it is off, so
        // the app could be uninstalled mid-lock
        val adminOff = Store.isActive(a) && DeviceAdmin.wasActive(a) && !adminActive
        val pen = Routine.isPenaltyLock(a)
        val penTitle = Routine.penaltyTaskTitle(a)
        val sig = "$acc|$bat|$adminOff|$pen:$penTitle|${L.bn}"
        if (sig == warnSig) return
        warnSig = sig
        warnBox.removeAllViews()
        if (pen) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Routine task overdue", "রুটিনের কাজ বাকি"),
                    (penTitle ?: "") + " — " + L.t("finish it and check it off below to unlock early.", "শেষ করে নিচে টিক দিলে লক আগেই খুলবে।")
                ) { },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
        if (!acc) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Accessibility is off", "Accessibility বন্ধ আছে"),
                    L.t("Protection cannot work. Tap to enable.", "প্রটেকশন কাজ করবে না। চালু করতে চাপুন।")
                ) { a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
        if (!bat) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Battery optimization is on", "ব্যাটারি অপটিমাইজেশন চালু"),
                    L.t("The system may stop PowerLock 2. Tap to allow.", "সিস্টেম PowerLock 2 বন্ধ করে দিতে পারে। অনুমতি দিতে চাপুন।")
                ) {
                    try {
                        a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: Exception) {
                        Log.e(TAG, "battery settings", e)
                    }
                },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
        if (adminOff) {
            warnBox.addView(
                warnCard(
                    "!",
                    L.t("Device admin is off", "ডিভাইস অ্যাডমিন বন্ধ হয়ে গেছে"),
                    L.t("Uninstall protection is off — the app can now be uninstalled during this lock. Tap to turn it back on.", "আনইনস্টল সুরক্ষা বন্ধ — এই লক চলাকালীন অ্যাপ আনইনস্টল করা যাবে। আবার চালু করতে চাপুন।")
                ) {
                    try {
                        a.startActivity(DeviceAdmin.enableIntent(a))
                    } catch (e: Exception) {
                        Log.e(TAG, "admin enable", e)
                    }
                },
                lp(MATCH, WRAP, b = a.dp(8))
            )
        }
    }

    private fun onStart() {
        if (Store.isActive(a)) return
        if (!Perm.accessibilityOn(a)) {
            a.toast(L.t("Enable Accessibility first", "আগে Accessibility চালু করুন"))
            return
        }
        val mode = unlockMode()
        if (mode == UNLOCK_TIME) {
            startTimeSession()
            return
        }
        // location modes need a destination + location permission
        val dest = unlockDest()
        if (dest == null) {
            a.toast(L.t("Choose a destination first", "আগে গন্তব্য বাছুন"))
            pickDestination()
            return
        }
        if (!Perm.locationOn(a)) {
            a.dlg()
                .setTitle(L.t("Location permission needed", "লোকেশন পারমিশন দরকার"))
                .setMessage(L.t("Location unlock needs location permission, otherwise the lock cannot open on arrival.", "লোকেশনে লক খুলতে লোকেশন পারমিশন লাগবে, না হলে পৌঁছালে লক খুলবে না।"))
                .setPositiveButton(L.t("Allow", "অনুমতি দিন")) { _, _ -> a.requestLocationPerm() }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
            return
        }
        // already at the destination? warn in the confirm dialog (the lock would open at once)
        Extra.currentLocation(a) { loc ->
            // the fix can take up to 20 s: if the user pressed Back meanwhile,
            // the activity is dead and the confirm dialog must not be shown
            if (a.isFinishing || a.isDestroyed) return@currentLocation
            var alreadyThere = false
            if (loc != null) {
                val res = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, dest.lat, dest.lng, res)
                alreadyThere = res[0] <= dest.radius
            }
            startLocationSession(mode, dest, alreadyThere)
        }
    }

    private fun startTimeSession() {
        val ms = a.selectedMin * 60_000L
        val ends = fmtClock(System.currentTimeMillis() + ms)
        val d = fmtDur(ms)
        val strictOn = Store.settings(a).getBoolean("strict", false)
        val profileId = selectedProfileId()
        val prof = if (profileId > 0) Extra.lockProfiles(a).find { it.id == profileId } else null
        val profTxt = if (prof != null) {
            L.t(
                "\n\nProfile: ${prof.name} — ${prof.pkgs.size} extra apps will be blocked.",
                "\n\nপ্রোফাইল: ${prof.name} — আরও ${L.num(prof.pkgs.size.toString())}টি অ্যাপ ব্লক হবে।"
            )
        } else ""
        val extra = if (strictOn) {
            L.t(
                "\n\nStrict mode is ON: you will NOT be able to stop early.",
                "\n\nস্ট্রিক্ট মোড চালু: সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না।"
            )
        } else {
            L.t(
                "\n\nStopping early needs a confirmation code.",
                "\n\nআগে বন্ধ করতে কনফার্মেশন কোড লাগবে।"
            )
        }
        a.dlg()
            .setTitle(L.t("Start protection?", "প্রটেকশন শুরু করবেন?"))
            .setMessage(
                L.t(
                    "The power menu will be blocked for $d, until $ends.",
                    "$d ধরে পাওয়ার মেনু ব্লক থাকবে, $ends পর্যন্ত।"
                ) + profTxt + extra
            )
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                if (Store.startSession(a, ms, "manual", null, UNLOCK_TIME, profileId)) {
                    a.toast(L.t("Protection started", "প্রটেকশন শুরু হয়েছে"))
                } else {
                    a.toast(L.t("A lock is already running", "একটা লক ইতিমধ্যে চলছে"))
                }
                tick()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun startLocationSession(mode: String, dest: UnlockTarget, alreadyThere: Boolean) {
        val ms = a.selectedMin * 60_000L
        val ends = fmtClock(System.currentTimeMillis() + ms)
        val d = fmtDur(ms)
        val strictOn = Store.settings(a).getBoolean("strict", false)
        val body = when (mode) {
            UNLOCK_LOC -> L.t(
                "The power menu will stay blocked until you arrive at ${dest.name} (within ${fmtDistM(dest.radius.toFloat())}).",
                "${dest.name} পৌঁছানো পর্যন্ত (ব্যাসার্ধ ${fmtDistM(dest.radius.toFloat())}) পাওয়ার মেনু ব্লক থাকবে।"
            )
            else -> L.t(
                "The power menu will be blocked for $d, until $ends — or it opens early when you arrive at ${dest.name}.",
                "$d ধরে পাওয়ার মেনু ব্লক থাকবে, $ends পর্যন্ত — অথবা ${dest.name} পৌঁছালে আগেই খুলবে।"
            )
        }
        val extra = if (strictOn) {
            when (mode) {
                UNLOCK_LOC -> L.t("\n\nStrict mode is ON: only arriving at ${dest.name} can open it.", "\n\nস্ট্রিক্ট মোড চালু: ${dest.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না।")
                else -> L.t("\n\nStrict mode is ON: only the timer or arriving at ${dest.name} can open it.", "\n\nস্ট্রিক্ট মোড চালু: সময় শেষ হওয়া বা ${dest.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না।")
            }
        } else {
            L.t("\n\nStopping early needs a confirmation code.", "\n\nআগে বন্ধ করতে কনফার্মেশন কোড লাগবে।")
        }
        val there = if (alreadyThere) L.t("\n\nYou are already at ${dest.name} — the lock will open right away.", "\n\nতুমি ইতিমধ্যে ${dest.name}-এ আছো — লক শুরু করলেই খুলে যাবে।") else ""
        val bgWarn = if (!Perm.backgroundLocationOn(a)) L.t("\n\n\"Allow all the time\" is off: arrival may not be detected with the screen off.", "\n\n\"সবসময় অনুমতি\" বন্ধ: স্ক্রিন বন্ধ থাকলে পৌঁছানো ধরা নাও পড়তে পারে।") else ""
        val profileId = selectedProfileId()
        val prof = if (profileId > 0) Extra.lockProfiles(a).find { it.id == profileId } else null
        val profTxt = if (prof != null) {
            L.t(
                "\n\nProfile: ${prof.name} — ${prof.pkgs.size} extra apps will be blocked.",
                "\n\nপ্রোফাইল: ${prof.name} — আরও ${prof.pkgs.size}টি অ্যাপ ব্লক হবে।"
            )
        } else ""
        a.dlg()
            .setTitle(L.t("Start protection?", "প্রটেকশন শুরু করবেন?"))
            .setMessage(L.num(body + extra + there + bgWarn + profTxt))
            .setPositiveButton(L.t("Start", "শুরু করুন")) { _, _ ->
                // location-only: the 15-day cap is only a safety valve, arrival ends it
                val dur = if (mode == UNLOCK_LOC) MAX_MIN * 60_000L else ms
                if (Store.startSession(a, dur, "manual", dest, mode, profileId)) {
                    PlaceWatcher.sync(a) // start watching for the arrival right away
                    PlaceWatcher.checkUnlockNow(a)
                    a.toast(L.t("Protection started", "প্রটেকশন শুরু হয়েছে"))
                } else {
                    a.toast(L.t("A lock is already running", "একটা লক ইতিমধ্যে চলছে"))
                }
                tick()
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    private fun onStop() {
        if (!Store.isActive(a)) return
        if (Store.isStrict(a)) {
            val mode = Store.unlockMode(a)
            val target = Store.unlockTarget(a)
            a.toast(
                when {
                    mode == UNLOCK_LOC && target != null -> L.t("Strict mode: only arriving at ${target.name} can open it", "স্ট্রিক্ট মোড: ${target.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না")
                    mode == UNLOCK_EITHER && target != null -> L.t("Strict mode: only the timer or arriving at ${target.name} can open it", "স্ট্রিক্ট মোড: সময় শেষ হওয়া বা ${target.name} পৌঁছানো ছাড়া বন্ধ করা যাবে না")
                    else -> L.t("Strict mode: cannot stop before the timer ends", "স্ট্রিক্ট মোড: সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না")
                }
            )
            return
        }
        val code = (100000..999999).random().toString()
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        box.addView(a.tv(L.t("Type this code to stop:", "বন্ধ করতে এই কোডটি লিখুন:"), 14f, C.muted))
        val codeView = a.tv(L.num(code), 28f, C.mint, 1)
        codeView.gravity = Gravity.CENTER
        codeView.letterSpacing = 0.2f
        box.addView(codeView, lp(MATCH, WRAP, t = a.dp(8)))
        val et = a.edit(L.t("Code", "কোড"), true)
        box.addView(et, lp(MATCH, WRAP, t = a.dp(8)))
        val d = a.dlg()
            .setTitle(L.t("Stop protection?", "প্রটেকশন বন্ধ করবেন?"))
            .setView(box)
            .setPositiveButton(L.t("Stop", "বন্ধ করুন"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (et.text.toString().trim() == code) {
                Store.stopSession(a)
                d.dismiss()
                a.toast(L.t("Protection stopped", "প্রটেকশন বন্ধ হয়েছে"))
                tick()
            } else {
                et.error = L.t("Wrong code", "ভুল কোড")
            }
        }
    }

    private fun customDialog() {
        if (Store.isActive(a)) return
        if (unlockMode() == UNLOCK_LOC) return
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        val r = LinearLayout(a)
        val dEt = a.edit(L.t("Days", "দিন"), true)
        val hEt = a.edit(L.t("Hours", "ঘণ্টা"), true)
        val mEt = a.edit(L.t("Minutes", "মিনিট"), true)
        r.addView(dEt, lp(0, WRAP, r = a.dp(8), weight = 1f))
        r.addView(hEt, lp(0, WRAP, r = a.dp(8), weight = 1f))
        r.addView(mEt, lp(0, WRAP, weight = 1f))
        box.addView(r)
        box.addView(a.tv(L.t("Maximum 15 days", "সর্বোচ্চ ১৫ দিন"), 12f, C.muted), lp(WRAP, WRAP, t = a.dp(8)))
        val d = a.dlg()
            .setTitle(L.t("Custom duration", "কাস্টম সময়"))
            .setView(box)
            .setPositiveButton(L.t("Set", "ঠিক আছে"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val total = (dEt.text.toString().toLongOrNull() ?: 0L) * 1440L +
                    (hEt.text.toString().toLongOrNull() ?: 0L) * 60L +
                    (mEt.text.toString().toLongOrNull() ?: 0L)
            if (total < 1L || total > MAX_MIN.toLong()) {
                mEt.error = L.t("1 minute to 15 days", "১ মিনিট থেকে ১৫ দিন")
            } else {
                a.selectedMin = total.toInt()
                d.dismiss()
                refreshChips(true)
                tick()
            }
        }
    }
}

// =====================================================================
// WEEKLY SCHEDULE
// =====================================================================
class ScheduleScreen(private val a: MainActivity) {
    private lateinit var list: LinearLayout
    private lateinit var routineList: LinearLayout
    private val routineDayChips = ArrayList<TextView>()
    private var routineDay = dayIdx(Calendar.getInstance()) // today by default

    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Weekly Schedule", "সাপ্তাহিক সময়সূচি"), 24f, C.text, 1))
        col.addView(
            a.tv(
                L.t("Protection starts automatically at the chosen times.", "নির্ধারিত সময়ে প্রটেকশন নিজে থেকে শুরু হবে।"),
                13f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(4), b = a.dp(16))
        )
        val add = a.primaryBtn("＋  " + L.t("Add schedule", "সময়সূচি যোগ করুন"))
        add.setOnClickListener { editor(null) }
        col.addView(add, lp(MATCH, a.dp(52)))
        list = LinearLayout(a)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, lp(MATCH, WRAP, t = a.dp(14)))
        refresh()

        // ---- daily routine: repeating tasks per weekday ----
        col.addView(a.tv(L.t("Daily routine", "দৈনিক রুটিন"), 24f, C.text, 1), lp(MATCH, WRAP, t = a.dp(26)))
        col.addView(
            a.tv(
                L.t("Tasks that repeat every day. Miss one and the phone locks until you do it.", "প্রতিদিনের কাজ। একটা বাদ গেলে ফোন লক হবে — কাজ শেষ করলে খুলবে।"),
                13f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(4), b = a.dp(12))
        )
        val dayRow = LinearLayout(a)
        for (i in 0..6) {
            val d = a.chip(L.dayShort(i), i == routineDay)
            d.setPadding(0, a.dp(10), 0, a.dp(10))
            d.setOnClickListener {
                routineDay = i
                for ((j, c) in routineDayChips.withIndex()) a.animateChip(c, j == i)
                refreshRoutine()
            }
            routineDayChips.add(d)
            dayRow.addView(d, lp(0, WRAP, r = if (i < 6) a.dp(4) else 0, weight = 1f))
        }
        col.addView(dayRow)
        val rAdd = a.primaryBtn("＋  " + L.t("Add routine task", "রুটিনের কাজ যোগ করুন"))
        rAdd.setOnClickListener { routineEditor(null) }
        col.addView(rAdd, lp(MATCH, a.dp(52), t = a.dp(12)))
        val rTpl = a.outlineBtn(L.t("Use a template", "টেমপ্লেট ব্যবহার করুন"), C.primary)
        rTpl.setOnClickListener { templateDialog() }
        col.addView(rTpl, lp(MATCH, a.dp(48), t = a.dp(8)))
        routineList = LinearLayout(a)
        routineList.orientation = LinearLayout.VERTICAL
        col.addView(routineList, lp(MATCH, WRAP, t = a.dp(14)))
        refreshRoutine()
        return sv
    }

    /** The selected weekday's routine tasks. */
    private fun refreshRoutine() {
        routineList.removeAllViews()
        val items = Routine.tasksForDay(a, routineDay)
        if (items.isEmpty()) {
            val c = a.card(24)
            c.gravity = Gravity.CENTER_HORIZONTAL
            val t = a.tv(L.t("No tasks on ${L.dayLong(routineDay)}", "${L.dayLong(routineDay)}-এ কোনো কাজ নেই"), 15f, C.muted, 1)
            t.gravity = Gravity.CENTER
            c.addView(t, lp(MATCH, WRAP, t = a.dp(8)))
            routineList.addView(c)
            return
        }
        for (t in items) {
            val c = a.card()
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            val col2 = LinearLayout(a)
            col2.orientation = LinearLayout.VERTICAL
            col2.addView(a.tv(t.title, 17f, C.text, 1))
            col2.addView(
                a.tv(fmtMin(t.timeMin) + "  ·  " + L.t("${t.graceMin} min grace", "${L.num(t.graceMin.toString())} মিনিট ছাড়"), 13f, C.primary),
                lp(MATCH, WRAP, t = a.dp(4))
            )
            r.addView(col2, lp(0, WRAP, weight = 1f))
            val del = a.tv("🗑", 20f)
            del.setPadding(a.dp(16), a.dp(4), a.dp(4), a.dp(4))
            del.setOnClickListener {
                a.dlg()
                    .setTitle(L.t("Delete this task?", "এই কাজ মুছে ফেলবেন?"))
                    .setMessage(t.title)
                    .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
                        Routine.saveTasks(a, routineDay, Routine.tasksForDay(a, routineDay).filter { it.id != t.id })
                        refreshRoutine()
                    }
                    .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                    .show()
            }
            r.addView(del)
            c.addView(r)
            c.setOnClickListener { routineEditor(t) }
            routineList.addView(c, lp(MATCH, WRAP, b = a.dp(12)))
        }
    }

    /** Add or edit one routine task for the selected weekday. */
    private fun routineEditor(existing: RoutineTask?) {
        var timeMin = existing?.timeMin ?: (8 * 60)
        var grace = existing?.graceMin ?: 30
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0)
        val et = a.edit(L.t("Task (e.g. Exercise)", "কাজ (যেমন ব্যায়াম)"), false)
        if (existing != null) et.setText(existing.title)
        box.addView(et)
        val timeView = a.tv("", 18f, C.text, 1)
        timeView.gravity = Gravity.CENTER
        timeView.background = withRipple(rounded(C.card2, a.dp(12).toFloat()))
        timeView.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(14))
        fun renderTime() {
            timeView.text = L.t("Time  ", "সময়  ") + fmtMin(timeMin)
        }
        timeView.setOnClickListener {
            TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                timeMin = h * 60 + m
                renderTime()
            }, timeMin / 60, timeMin % 60, false).show()
        }
        box.addView(timeView, lp(MATCH, WRAP, t = a.dp(14)))
        renderTime()
        box.addView(
            a.tv(L.t("Grace time — how long after the time before the phone locks", "ছাড় — সময়ের কত পরে ফোন লক হবে"), 13f, C.muted),
            lp(MATCH, WRAP, t = a.dp(14))
        )
        val graceRow = LinearLayout(a)
        val graceChips = ArrayList<TextView>()
        val graceOpts = listOf(15, 30, 60)
        for (g in graceOpts) {
            val ch = a.chip(L.num(g.toString()) + " " + L.t("min", "মিনিট"), g == grace)
            ch.setOnClickListener {
                grace = g
                for ((i, c) in graceChips.withIndex()) a.animateChip(c, graceOpts[i] == g)
            }
            graceChips.add(ch)
            graceRow.addView(ch, lp(0, WRAP, r = a.dp(8), t = a.dp(8), weight = 1f))
        }
        box.addView(graceRow)
        val d = a.dlg()
            .setTitle(if (existing == null) L.t("New routine task", "নতুন রুটিনের কাজ") else L.t("Edit routine task", "রুটিনের কাজ বদলান"))
            .setView(box)
            .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = et.text.toString().trim()
            if (title.isEmpty()) {
                et.error = L.t("Write a task name", "কাজের নাম লিখুন")
            } else {
                val all = Routine.tasksForDay(a, routineDay)
                val updated = if (existing == null) {
                    all + RoutineTask(Store.newId(), title.take(40), timeMin, grace, "manual")
                } else {
                    all.map { if (it.id == existing.id) it.copy(title = title.take(40), timeMin = timeMin, graceMin = grace) else it }
                }
                Routine.saveTasks(a, routineDay, updated)
                d.dismiss()
                refreshRoutine()
            }
        }
    }

    /** One-tap presets (student / office / general) — replaces the whole routine. */
    private fun templateDialog() {
        val tpls = Routine.templates()
        val items = tpls.map {
            L.t(it.nameEn, it.nameBn) + "  ·  " + L.num(it.tasks.size.toString()) + " " + L.t("tasks", "টি কাজ")
        }.toTypedArray()
        a.dlg()
            .setTitle(L.t("Routine template", "রুটিন টেমপ্লেট"))
            .setItems(items) { _, which ->
                val tpl = tpls[which]
                a.dlg()
                    .setTitle(L.t(tpl.nameEn, tpl.nameBn))
                    .setMessage(L.t("This replaces your whole routine (all days) with the template's tasks.", "এটা তোমার পুরো রুটিন (সব দিন) টেমপ্লেটের কাজ দিয়ে বদলে দেবে।"))
                    .setPositiveButton(L.t("Apply", "প্রয়োগ করুন")) { _, _ ->
                        Routine.applyTemplate(a, tpl)
                        refreshRoutine()
                        a.toast(L.t("Routine applied", "রুটিন প্রয়োগ হয়েছে"))
                    }
                    .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                    .show()
            }
            .show()
    }

    private fun refresh() {
        list.removeAllViews()
        val items = Store.schedules(a)
        if (items.isEmpty()) {
            val c = a.card(24)
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(IconView(a, Ic.CAL, C.primary), lp(a.dp(56), a.dp(56)))
            val t = a.tv(L.t("No schedules yet", "এখনো কোনো সময়সূচি নেই"), 15f, C.muted, 1)
            t.gravity = Gravity.CENTER
            c.addView(t, lp(MATCH, WRAP, t = a.dp(8)))
            list.addView(c)
            return
        }
        for (s in items.sortedBy { it.startMin }) {
            list.addView(scheduleCard(s), lp(MATCH, WRAP, b = a.dp(12)))
        }
    }

    private fun scheduleCard(s: Schedule): View {
        val c = a.card()
        val days = LinearLayout(a)
        for (i in 0..6) {
            val on = ((s.days shr i) and 1) == 1
            val d = a.tv(L.dayShort(i), 12f, if (on) C.onPrimary else C.muted, 1)
            d.gravity = Gravity.CENTER
            d.background = rounded(if (on) C.primary else C.card2, a.dp(14).toFloat())
            days.addView(d, lp(0, a.dp(32), r = if (i < 6) a.dp(6) else 0, weight = 1f))
        }
        c.addView(days)
        c.addView(a.tv(fmtMin(s.startMin) + "  →  " + fmtMin(s.endMin), 20f, C.text, 1), lp(MATCH, WRAP, t = a.dp(12)))
        if (s.endMin <= s.startMin) {
            c.addView(a.tv(L.t("Overnight · ends next day", "রাত পেরিয়ে · পরের দিন শেষ"), 12f, C.mint))
        }
        val bottom = LinearLayout(a)
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(
            a.tv(if (s.enabled) L.t("Enabled", "চালু") else L.t("Disabled", "বন্ধ"), 13f, C.muted),
            lp(0, WRAP, weight = 1f)
        )
        bottom.addView(a.toggle(s.enabled) { on ->
            val all = Store.schedules(a).map { if (it.id == s.id) it.copy(enabled = on) else it }
            Store.saveSchedules(a, all)
            Scheduler.onAlarm(a)
            list.post { refresh() }
        })
        val del = a.tv("🗑", 20f)
        del.setPadding(a.dp(16), a.dp(4), a.dp(4), a.dp(4))
        del.setOnClickListener {
            a.dlg()
                .setTitle(L.t("Delete schedule?", "সময়সূচি মুছবেন?"))
                .setPositiveButton(L.t("Delete", "মুছুন")) { _, _ ->
                    Store.saveSchedules(a, Store.schedules(a).filter { it.id != s.id })
                    Scheduler.onAlarm(a)
                    refresh()
                }
                .setNegativeButton(L.t("Cancel", "বাতিল"), null)
                .show()
        }
        bottom.addView(del)
        c.addView(bottom, lp(MATCH, WRAP, t = a.dp(8)))
        c.setOnClickListener { editor(s) }
        return c
    }

    private fun editor(existing: Schedule?) {
        var days = existing?.days ?: 31
        var sMin = existing?.startMin ?: (22 * 60)
        var eMin = existing?.endMin ?: (6 * 60)

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(20), a.dp(8), a.dp(20), 0)

        val dayRow = LinearLayout(a)
        for (i in 0..6) {
            val d = a.chip(L.dayShort(i), ((days shr i) and 1) == 1)
            d.setPadding(0, a.dp(10), 0, a.dp(10))
            d.setOnClickListener {
                days = days xor (1 shl i)
                a.animateChip(d, ((days shr i) and 1) == 1)
            }
            dayRow.addView(d, lp(0, WRAP, r = if (i < 6) a.dp(4) else 0, weight = 1f))
        }
        box.addView(dayRow)

        val startView = a.tv("", 18f, C.text, 1)
        val endView = a.tv("", 18f, C.text, 1)
        val note = a.tv("", 12f, C.mint)
        for (v in listOf(startView, endView)) {
            v.gravity = Gravity.CENTER
            v.background = withRipple(rounded(C.card2, a.dp(12).toFloat()))
            v.setPadding(a.dp(8), a.dp(14), a.dp(8), a.dp(14))
        }
        fun render() {
            startView.text = L.t("Start  ", "শুরু  ") + fmtMin(sMin)
            endView.text = L.t("End  ", "শেষ  ") + fmtMin(eMin)
            note.text = if (eMin <= sMin) L.t("Overnight: ends the next day", "রাত পেরিয়ে পরের দিন শেষ হবে") else ""
        }
        startView.setOnClickListener {
            TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                sMin = h * 60 + m
                render()
            }, sMin / 60, sMin % 60, false).show()
        }
        endView.setOnClickListener {
            TimePickerDialog(a, android.R.style.Theme_DeviceDefault_Dialog, { _, h, m ->
                eMin = h * 60 + m
                render()
            }, eMin / 60, eMin % 60, false).show()
        }
        val tr = LinearLayout(a)
        tr.addView(startView, lp(0, WRAP, r = a.dp(8), weight = 1f))
        tr.addView(endView, lp(0, WRAP, weight = 1f))
        box.addView(tr, lp(MATCH, WRAP, t = a.dp(14)))
        box.addView(note, lp(MATCH, WRAP, t = a.dp(8)))
        render()

        val d = a.dlg()
            .setTitle(if (existing == null) L.t("New schedule", "নতুন সময়সূচি") else L.t("Edit schedule", "সময়সূচি পরিবর্তন"))
            .setView(box)
            .setPositiveButton(L.t("Save", "সংরক্ষণ"), null)
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val all = Store.schedules(a)
            if (days == 0) {
                a.toast(L.t("Select at least one day", "কমপক্ষে একটি দিন বাছুন"))
            } else if (sMin == eMin) {
                a.toast(L.t("Start and end must be different", "শুরু ও শেষের সময় আলাদা হতে হবে"))
            } else if (all.any { it.id != existing?.id && it.days == days && it.startMin == sMin && it.endMin == eMin }) {
                a.toast(L.t("This schedule already exists", "এই সময়সূচি আগেই আছে"))
            } else {
                val updated: List<Schedule> = if (existing == null) {
                    all + Schedule(Store.newId(), days, sMin, eMin, true)
                } else {
                    all.map { if (it.id == existing.id) Schedule(it.id, days, sMin, eMin, it.enabled) else it }
                }
                Store.saveSchedules(a, updated)
                Scheduler.onAlarm(a)
                d.dismiss()
                refresh()
            }
        }
    }
}

// =====================================================================
// STATISTICS
// =====================================================================
class StatsScreen(private val a: MainActivity) {
    private var calOffset = 0 // months back from the current month (0..-3)
    private lateinit var calGrid: LinearLayout
    private lateinit var calLabel: TextView
    private lateinit var calDetail: TextView

    fun build(): View {
        Store.finalizeIfExpired(a)
        val (sv, col) = a.page()
        val all = Store.sessions(a)
        val now = System.currentTimeMillis()
        val today = Store.dayStart(now, 0)
        val week = Store.dayStart(now, -6)
        val month = Store.monthStart(now)
        val end = Long.MAX_VALUE
        val completed = all.count { it.ok }
        var sum = 0L
        var longest = 0L
        for (s in all) {
            sum += s.dur
            if (s.dur > longest) longest = s.dur
        }
        val avg = if (all.isEmpty()) 0L else sum / all.size

        col.addView(a.tv(L.t("Statistics", "পরিসংখ্যান"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))

        // focus card: today's stats as a shareable image
        val shareBtn = a.outlineBtn(L.t("Share focus card", "ফোকাস কার্ড শেয়ার করুন"), C.primary)
        shareBtn.setOnClickListener { FocusCard.share(a) }
        col.addView(shareBtn, lp(MATCH, a.dp(48), b = a.dp(14)))

        fun statCard(icon: Ic, label: String, value: String): View {
            val c = a.card(14)
            c.addView(IconView(a, icon, C.primary), lp(a.dp(24), a.dp(24)))
            c.addView(a.tv(value, 19f, C.text, 1), lp(MATCH, WRAP, t = a.dp(6)))
            c.addView(a.tv(label, 12f, C.muted))
            return c
        }

        fun pair(x: View, y: View) {
            val r = LinearLayout(a)
            r.addView(x, lp(0, MATCH, r = a.dp(10), weight = 1f))
            r.addView(y, lp(0, MATCH, weight = 1f))
            col.addView(r, lp(MATCH, WRAP, b = a.dp(10)))
        }

        // streak + total focus (completed locks only)
        val fs = Store.focusStats(a)
        pair(
            statCard(Ic.CHECK, L.t("Day streak", "দিনের স্ট্রিক"), L.num(fs.streak.toString())),
            statCard(Ic.CLOCK, L.t("Total focus", "মোট ফোকাস"), fmtDur(fs.totalMin * 60000L))
        )

        pair(
            statCard(Ic.CLOCK, L.t("Today", "আজ"), fmtDur(Store.sumRange(all, today, end))),
            statCard(Ic.CAL, L.t("Last 7 days", "গত ৭ দিন"), fmtDur(Store.sumRange(all, week, end)))
        )
        pair(
            statCard(Ic.CAL, L.t("This month", "এই মাস"), fmtDur(Store.sumRange(all, month, end))),
            statCard(Ic.CHECK, L.t("Completed sessions", "সম্পন্ন সেশন"), L.num(completed.toString()))
        )
        pair(
            statCard(Ic.BARS, L.t("Average session", "গড় সেশন"), fmtDur(avg)),
            statCard(Ic.SHIELD, L.t("Longest session", "সবচেয়ে বড় সেশন"), fmtDur(longest))
        )

        // chart
        val chartCard = a.card()
        chartCard.addView(a.section(L.t("Last 7 days", "গত ৭ দিন")))
        val chart = BarChartView(a)
        val vals = ArrayList<Long>()
        val labs = ArrayList<String>()
        for (off in -6..0) {
            val from = Store.dayStart(now, off)
            val to = Store.dayStart(now, off + 1)
            vals.add(Store.sumRange(all, from, to))
            val c = Calendar.getInstance()
            c.timeInMillis = from
            labs.add(L.dayLong(dayIdx(c)))
        }
        chart.values = vals
        chart.labels = labs
        chartCard.addView(chart, lp(MATCH, a.dp(150), t = a.dp(12)))
        col.addView(chartCard, lp(MATCH, WRAP, t = a.dp(4), b = a.dp(14)))

        // focus minutes per day (completed locks only), last 7 days
        val focus7 = a.card()
        focus7.addView(a.section(L.t("Focus — last 7 days", "ফোকাস — গত ৭ দিন")))
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val label = if (i == 0) L.t("Today", "আজ") else L.dayLong(dayIdx(c))
            val min = fs.last7[6 - i]
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(if (min > 0) fmtDur(min * 60000L) else "—", 14f, if (min > 0) C.mint else C.muted, 1))
            focus7.addView(r)
        }
        col.addView(focus7, lp(MATCH, WRAP, b = a.dp(14)))

        // focus score: today's total with the visible breakdown + 7-day list
        val scoreWeek = FocusScore.week(a)
        val scoreToday = scoreWeek[6]
        FocusScore.recordToday(a, scoreToday)
        val scoreCard = a.card()
        scoreCard.addView(a.section(L.t("Focus score", "ফোকাস স্কোর")))
        val scoreBig = LinearLayout(a)
        scoreBig.gravity = Gravity.CENTER_VERTICAL
        scoreBig.addView(a.tv(L.num(scoreToday.total.toString()), 34f, C.mint, 1))
        scoreBig.addView(a.tv(L.t(" / 100 today", " / ১০০ আজ"), 16f, C.muted), lp(WRAP, WRAP, l = a.dp(4)))
        scoreCard.addView(scoreBig, lp(MATCH, WRAP, t = a.dp(8)))
        fun scoreRow(label: String, pts: Int, max: Int) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(L.num("$pts/$max"), 14f, C.mint, 1))
            scoreCard.addView(r)
        }
        scoreRow(
            L.t("Focus ${scoreToday.focusMin} min (goal ${FocusScore.GOAL_MIN})", "ফোকাস ${L.num(scoreToday.focusMin.toString())} মিনিট (লক্ষ্য ${L.num(FocusScore.GOAL_MIN.toString())})"),
            scoreToday.focusPts, 40
        )
        scoreRow(
            L.t("Completed locks: ${scoreToday.locks}", "সম্পন্ন লক: ${L.num(scoreToday.locks.toString())}টি"),
            scoreToday.locksPts, 20
        )
        scoreRow(
            L.t("Streak: ${scoreToday.streak} days", "স্ট্রিক: ${L.num(scoreToday.streak.toString())} দিন"),
            scoreToday.streakPts, 20
        )
        scoreRow(
            L.t("Temptation attempts: ${scoreToday.attempts}", "প্রলোভন: ${L.num(scoreToday.attempts.toString())} বার"),
            scoreToday.calmPts, 20
        )
        // 7-day scores: stored totals when recorded, else rebuilt from data
        scoreCard.addView(a.tv(L.t("Last 7 days", "গত ৭ দিন"), 13f, C.muted, 1), lp(MATCH, WRAP, t = a.dp(12)))
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val label = if (i == 0) L.t("Today", "আজ") else L.dayLong(dayIdx(c))
            val total = if (i == 0) scoreToday.total
                else FocusScore.storedTotal(a, i) ?: scoreWeek[6 - i].total
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(L.num(total.toString()), 14f, if (total > 0) C.mint else C.muted, 1))
            scoreCard.addView(r)
        }
        col.addView(scoreCard, lp(MATCH, WRAP, b = a.dp(14)))

        // focus journey: lifetime focus minutes walk Dhaka → Cox's Bazar (1 min = 1 km)
        val (journeys, journeyKm, _) = FocusJourney.progress(a)
        val journeyCard = a.card()
        journeyCard.addView(a.section(L.t("Focus journey", "ফোকাস যাত্রা")))
        val journeyView = JourneyView(a)
        journeyView.km = journeyKm
        journeyCard.addView(journeyView, lp(MATCH, a.dp(84), t = a.dp(8)))
        val nextMs = FocusJourney.nextMilestone(a)
        val journeyText = StringBuilder()
        journeyText.append(L.num(journeyKm.toString()))
        journeyText.append(" / ")
        journeyText.append(L.num(FocusJourney.ROUTE_KM.toString()))
        journeyText.append(" " + L.t("km", "কিমি"))
        if (nextMs != null) {
            val toGo = nextMs.km - journeyKm
            journeyText.append(" · " + L.t("next: ", "পরের মাইলফলক: "))
            journeyText.append(L.t(nextMs.nameEn, nextMs.nameBn))
            journeyText.append(" (" + L.num(toGo.toString()) + " " + L.t("km to go", "কিমি বাকি") + ")")
        }
        if (journeys > 0) {
            journeyText.append("\n" + L.t("Journeys completed: ", "সম্পন্ন যাত্রা: ") + L.num(journeys.toString()))
        }
        journeyCard.addView(a.tv(journeyText.toString(), 14f, C.text), lp(MATCH, WRAP, t = a.dp(8)))
        col.addView(journeyCard, lp(MATCH, WRAP, b = a.dp(14)))

        // record board: personal bests, recomputed from stored data on every build
        val recordCard = a.card()
        recordCard.addView(a.section(L.t("Records", "রেকর্ড")))
        for (rec in Records.all(a)) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(L.t(rec.labelEn, rec.labelBn), 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(rec.value, 14f, if (rec.dateMs != null) C.mint else C.muted, 1))
            recordCard.addView(r)
            if (rec.dateMs != null) {
                recordCard.addView(
                    a.tv(fmtDate(rec.dateMs), 12f, C.muted),
                    lp(MATCH, WRAP, b = a.dp(2))
                )
            }
        }
        col.addView(recordCard, lp(MATCH, WRAP, b = a.dp(14)))

        // routine: streak + today's progress + the last 7 days
        val (rDone, rTotal) = Routine.todaySummary(a)
        val rStreak = Routine.streak(a)
        val routineCard = a.card()
        routineCard.addView(a.section(L.t("Daily routine", "দৈনিক রুটিন")))
        val rBig = LinearLayout(a)
        rBig.gravity = Gravity.CENTER_VERTICAL
        rBig.addView(a.tv(L.num(rStreak.toString()), 34f, C.mint, 1))
        rBig.addView(a.tv(L.t(" day streak", " দিনের স্ট্রিক"), 16f, C.muted), lp(WRAP, WRAP, l = a.dp(4)))
        routineCard.addView(rBig, lp(MATCH, WRAP, t = a.dp(8)))
        // streak protection tokens (won from the lucky wheel)
        val tokens = StreakProtection.tokens(a)
        val tokenTxt = if (tokens == 1) L.t("1 token", "১টি টোকেন")
        else L.num(tokens.toString()) + " " + L.t("tokens", "টি টোকেন")
        routineCard.addView(
            a.tv(
                L.t("Streak protection: ", "স্ট্রিক সুরক্ষা: ") + tokenTxt + " · " +
                    L.t("a token saves your streak when a day is missed", "একদিন মিস হলে টোকেন স্ট্রিক বাঁচায়"),
                13f, if (tokens > 0) C.mint else C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(6))
        )
        routineCard.addView(
            a.tv(
                if (rTotal > 0) L.t("Today: $rDone of $rTotal tasks done", "আজ: ${L.num(rTotal.toString())}টি কাজের মধ্যে ${L.num(rDone.toString())}টি শেষ")
                else L.t("No routine tasks today — build one in the Schedule tab.", "আজ কোনো রুটিনের কাজ নেই — সময়সূচি ট্যাবে বানান।"),
                14f, C.text
            ),
            lp(MATCH, WRAP, t = a.dp(6))
        )
        for (i in 6 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -i)
            val label = if (i == 0) L.t("Today", "আজ") else L.dayLong(dayIdx(c))
            val tasks = Routine.tasksForDay(a, dayIdx(c))
            val done = Routine.doneIds(a, Store.dayKey(i))
            val doneCount = tasks.count { it.id in done }
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            val txt = if (tasks.isEmpty()) "—" else L.num(doneCount.toString()) + "/" + L.num(tasks.size.toString())
            r.addView(a.tv(txt, 14f, if (tasks.isNotEmpty() && doneCount == tasks.size) C.mint else C.muted, 1))
            routineCard.addView(r)
        }
        col.addView(routineCard, lp(MATCH, WRAP, b = a.dp(14)))

        // this week vs last week: focus minutes + completed locks (focus_days, 90 days kept)
        val cmpCard = a.card()
        cmpCard.addView(a.section(L.t("This week vs last week", "এই সপ্তাহ বনাম গত সপ্তাহ")))
        val focusMap = Store.focusDayMap(a)
        val thisKeys = (0..6).map { Store.dayKey(it) }.toSet()
        val lastKeys = (7..13).map { Store.dayKey(it) }.toSet()
        val thisMin = thisKeys.sumOf { focusMap[it] ?: 0 }
        val lastMin = lastKeys.sumOf { focusMap[it] ?: 0 }
        val thisLocks = all.count { it.ok && Store.dayKeyFor(it.start) in thisKeys }
        val lastLocks = all.count { it.ok && Store.dayKeyFor(it.start) in lastKeys }
        fun cmpRow(label: String, thisV: String, lastV: String) {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, a.dp(6), 0, a.dp(6))
            r.addView(a.tv(label, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
            r.addView(a.tv(thisV, 14f, C.mint, 1), lp(WRAP, WRAP, r = a.dp(12)))
            r.addView(a.tv(L.t("last week: ", "গত সপ্তাহে: ") + lastV, 13f, C.muted))
            cmpCard.addView(r)
        }
        cmpRow(L.t("Focus", "ফোকাস"), fmtDur(thisMin * 60000L), fmtDur(lastMin * 60000L))
        cmpRow(L.t("Locks completed", "সম্পন্ন লক"), L.num(thisLocks.toString()), L.num(lastLocks.toString()))
        val cmpText = when {
            lastMin == 0 && thisMin == 0 -> L.t("No focus in either week yet.", "দুই সপ্তাহেই এখনো ফোকাস হয়নি।")
            lastMin == 0 -> L.t("No focus last week — this week is a fresh start!", "গত সপ্তাহে ফোকাস হয়নি — এই সপ্তাহ নতুন শুরু!")
            else -> {
                val pct = (thisMin - lastMin) * 100 / lastMin
                when {
                    pct == 0 -> L.t("Same focus as last week.", "গত সপ্তাহের সমান ফোকাস।")
                    pct > 0 -> L.num(L.t("$pct% more focus than last week", "গত সপ্তাহের চেয়ে $pct% বেশি ফোকাস"))
                    else -> L.num(L.t("${-pct}% less focus than last week", "গত সপ্তাহের চেয়ে ${-pct}% কম ফোকাস"))
                }
            }
        }
        cmpCard.addView(
            a.tv(cmpText, 14f, if (thisMin >= lastMin) C.mint else C.warn, 1),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        col.addView(cmpCard, lp(MATCH, WRAP, b = a.dp(14)))

        // milestone badges: earned in colour with the date, unearned greyed out
        val badgeCard = a.card()
        badgeCard.addView(a.section(L.t("Badges", "ব্যাজ")))
        val earned = Badges.earned(a)
        val badgeDefs = Badges.ALL
        for (rowIdx in badgeDefs.indices step 2) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            for (j in 0 until 2) {
                val def = badgeDefs.getOrNull(rowIdx + j)
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.background = rounded(C.card2, a.dp(14).toFloat())
                cell.setPadding(a.dp(10), a.dp(12), a.dp(10), a.dp(12))
                if (def != null) {
                    val whenMs = earned[def.id]
                    val got = whenMs != null
                    cell.alpha = if (got) 1f else 0.45f
                    cell.addView(IconView(a, def.icon, if (got) C.mint else C.muted), lp(a.dp(30), a.dp(30)))
                    val t = a.tv(L.t(def.titleEn, def.titleBn), 13f, if (got) C.text else C.muted, 1)
                    t.gravity = Gravity.CENTER
                    cell.addView(t, lp(MATCH, WRAP, t = a.dp(6)))
                    val d = a.tv(L.t(def.descEn, def.descBn), 11f, C.muted)
                    d.gravity = Gravity.CENTER
                    cell.addView(d, lp(MATCH, WRAP, t = a.dp(2)))
                    val s = a.tv(
                        if (got) fmtDate(whenMs ?: 0L) else L.t("Not earned yet", "এখনো পাননি"),
                        11f, if (got) C.primary else C.muted, 1
                    )
                    s.gravity = Gravity.CENTER
                    cell.addView(s, lp(MATCH, WRAP, t = a.dp(4)))
                }
                row.addView(cell, lp(0, MATCH, r = if (j == 0) a.dp(10) else 0, t = a.dp(10), weight = 1f))
            }
            badgeCard.addView(row)
        }
        col.addView(badgeCard, lp(MATCH, WRAP, b = a.dp(14)))

        // lock calendar: a dot on every day a lock was completed
        val calCard = a.card()
        calCard.addView(a.section(L.t("Lock calendar", "লক ক্যালেন্ডার")))
        val navRow = LinearLayout(a)
        navRow.gravity = Gravity.CENTER_VERTICAL
        val prevBtn = a.tv("‹", 28f, C.primary, 1)
        prevBtn.setPadding(a.dp(12), 0, a.dp(12), 0)
        prevBtn.setOnClickListener {
            if (calOffset > -3) {
                calOffset--
                buildCalendar()
            }
        }
        val nextBtn = a.tv("›", 28f, C.primary, 1)
        nextBtn.setPadding(a.dp(12), 0, a.dp(12), 0)
        nextBtn.setOnClickListener {
            if (calOffset < 0) {
                calOffset++
                buildCalendar()
            }
        }
        calLabel = a.tv("", 16f, C.text, 1)
        calLabel.gravity = Gravity.CENTER
        navRow.addView(prevBtn)
        navRow.addView(calLabel, lp(0, WRAP, weight = 1f))
        navRow.addView(nextBtn)
        calCard.addView(navRow, lp(MATCH, WRAP, t = a.dp(8)))
        // weekday header, Monday first
        val headRow = LinearLayout(a)
        for (i in 0 until 7) {
            val h = a.tv(L.dayShort(i), 12f, C.muted, 1)
            h.gravity = Gravity.CENTER
            headRow.addView(h, lp(0, WRAP, weight = 1f))
        }
        calCard.addView(headRow, lp(MATCH, WRAP, t = a.dp(10)))
        calGrid = LinearLayout(a)
        calGrid.orientation = LinearLayout.VERTICAL
        calCard.addView(calGrid, lp(MATCH, WRAP, t = a.dp(4)))
        calDetail = a.tv("", 13f, C.muted)
        calDetail.gravity = Gravity.CENTER
        calCard.addView(calDetail, lp(MATCH, WRAP, t = a.dp(10)))
        col.addView(calCard, lp(MATCH, WRAP, b = a.dp(14)))
        buildCalendar()

        // garden: every finished tree, newest first (dead ones withered and grey)
        val gardenCard = a.card()
        gardenCard.addView(a.section(L.t("Garden", "বাগান")))
        // garden animals: earned by growing trees, or won from the lucky wheel
        val animals = GardenAnimals.unlocked(a)
        if (animals.isEmpty()) {
            val next = GardenAnimals.nextByTrees(a)
            if (next != null) {
                gardenCard.addView(
                    a.tv(
                        L.t(
                            "Grow ${next.trees} trees to meet your first animal: ${next.nameEn}.",
                            "${L.num(next.trees.toString())}টি গাছ বড় করলে প্রথম প্রাণী আসবে: ${next.nameBn}।"
                        ),
                        13f, C.muted
                    ),
                    lp(MATCH, WRAP, t = a.dp(6))
                )
            }
        } else {
            val aRow = LinearLayout(a)
            aRow.gravity = Gravity.CENTER_VERTICAL
            for (an in animals) {
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.addView(AnimalView(a, an.id), lp(a.dp(56), a.dp(56)))
                val nl = a.tv(L.t(an.nameEn, an.nameBn), 11f, C.muted, 1)
                nl.gravity = Gravity.CENTER
                cell.addView(nl, lp(MATCH, WRAP, t = a.dp(2)))
                aRow.addView(cell, lp(0, WRAP, weight = 1f))
            }
            gardenCard.addView(aRow, lp(MATCH, WRAP, t = a.dp(8)))
        }
        val trees = FocusTree.garden(a)
        if (trees.isEmpty()) {
            gardenCard.addView(
                a.tv(L.t("No trees yet — complete a lock and your first tree will grow here.", "এখনো কোনো গাছ নেই — একটা লক সম্পন্ন করলে প্রথম গাছ এখানে জন্মাবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(6))
            )
        } else {
            var i = 0
            while (i < trees.size) {
                val row = LinearLayout(a)
                for (j in 0 until 3) {
                    val t = trees.getOrNull(i + j)
                    val cell = LinearLayout(a)
                    cell.orientation = LinearLayout.VERTICAL
                    cell.gravity = Gravity.CENTER_HORIZONTAL
                    if (t != null) {
                        val tv = TreeView(a)
                        tv.frac = t.stage * 0.25f
                        tv.alive = t.alive
                        tv.species = t.species
                        cell.addView(tv, lp(a.dp(96), a.dp(96)))
                        val dl = a.tv(fmtDate(t.endMs), 11f, C.muted, 1)
                        dl.gravity = Gravity.CENTER
                        cell.addView(dl, lp(MATCH, WRAP, t = a.dp(2)))
                        val ml = a.tv(fmtDur(t.durMin * 60000L), 11f, if (t.alive) C.primary else C.muted, 1)
                        ml.gravity = Gravity.CENTER
                        cell.addView(ml)
                        cell.pressFeedback()
                        cell.setOnClickListener {
                            a.dlg()
                                .setTitle(FocusTree.stageName(t.stage))
                                .setMessage(
                                    fmtDate(t.endMs) + "\n" +
                                        L.t("Lock time: ", "লকের সময়: ") + fmtDur(t.durMin * 60000L) + "\n" +
                                        if (t.alive) L.t("The tree survived", "গাছটা বেঁচে গেছে")
                                        else L.t("The tree withered — the lock was stopped early", "গাছটা শুকিয়ে গেছে — লক আগেই বন্ধ করা হয়েছিল")
                                )
                                .setPositiveButton(L.t("OK", "ঠিক আছে"), null)
                                .show()
                        }
                    }
                    row.addView(cell, lp(0, WRAP, weight = 1f))
                }
                gardenCard.addView(row, lp(MATCH, WRAP, t = a.dp(8)))
                i += 3
            }
        }
        col.addView(gardenCard, lp(MATCH, WRAP, b = a.dp(14)))

        // today's app usage (from the daily-limits tracker): most-used first.
        // Tapping a row opens its daily-limit setting.
        val usageCard = a.card()
        usageCard.addView(a.section(L.t("App usage today", "আজকের অ্যাপ ব্যবহার")))
        val usageToday = Extra.usageTodayAll(a)
        val usage7 = Extra.usage7DayTotal(a)
        if (usageToday.isEmpty()) {
            usageCard.addView(
                a.tv(L.t("No usage tracked yet today. Accessibility must be on to track app time.", "আজ এখনো কোনো ব্যবহার ট্র্যাক হয়নি। অ্যাপের সময় ট্র্যাক করতে Accessibility চালু থাকতে হবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(6))
            )
        } else {
            data class URow(val pkg: String, val label: String, val icon: Drawable?, val todaySec: Long, val weekSec: Long)
            val rows = ArrayList<URow>()
            val pm = a.packageManager
            for ((pkg, sec) in usageToday) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    rows.add(URow(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), sec, usage7[pkg] ?: 0L))
                } catch (e: Exception) {
                    // uninstalled: skip the row (its seconds still count in the totals)
                }
            }
            rows.sortByDescending { it.todaySec }
            val maxSec = rows.firstOrNull()?.todaySec ?: 1L
            for (row in rows.take(10)) {
                val r = LinearLayout(a)
                r.gravity = Gravity.CENTER_VERTICAL
                r.setPadding(0, a.dp(8), 0, a.dp(2))
                if (row.icon != null) {
                    val icon = ImageView(a)
                    icon.setImageDrawable(row.icon)
                    r.addView(icon, lp(a.dp(36), a.dp(36), r = a.dp(12)))
                } else {
                    r.addView(View(a), lp(a.dp(36), a.dp(36), r = a.dp(12)))
                }
                val labelCol = LinearLayout(a)
                labelCol.orientation = LinearLayout.VERTICAL
                labelCol.addView(a.tv(row.label, 15f, C.text))
                labelCol.addView(a.tv(L.t("7 days: ", "৭ দিনে: ") + fmtDur(row.weekSec * 1000L), 12f, C.muted))
                r.addView(labelCol, lp(0, WRAP, weight = 1f))
                r.addView(a.tv(fmtDur(row.todaySec * 1000L), 14f, C.text, 1))
                r.pressFeedback()
                r.setOnClickListener { a.limitChoiceDialog(row.pkg, row.label, Extra.dailyLimit(a, row.pkg)) { a.go(2) } }
                usageCard.addView(r, lp(MATCH, WRAP, t = a.dp(4)))
                // usage bar, relative to the most-used app
                val barWrap = LinearLayout(a)
                barWrap.orientation = LinearLayout.HORIZONTAL
                barWrap.background = rounded(C.card2, a.dp(3).toFloat())
                val frac = (row.todaySec.toFloat() / maxSec.toFloat()).coerceIn(0.02f, 1f)
                val fill = View(a)
                fill.background = rounded(C.primary, a.dp(3).toFloat())
                barWrap.addView(fill, lp(0, a.dp(6), weight = frac))
                barWrap.addView(View(a), lp(0, a.dp(6), weight = 1f - frac))
                usageCard.addView(barWrap, lp(MATCH, a.dp(6), t = a.dp(4)))
            }
            val yest = Extra.usageDayTotal(a, Store.dayKey(1))
            val weekTotal = usage7.values.sum()
            usageCard.addView(
                a.tv(
                    L.t("Yesterday total: ", "গতকাল মোট: ") + fmtDur(yest * 1000L) + " · " +
                        L.t("Last 7 days total: ", "গত ৭ দিন মোট: ") + fmtDur(weekTotal * 1000L),
                    12f, C.muted
                ),
                lp(MATCH, WRAP, t = a.dp(10))
            )
        }
        col.addView(usageCard, lp(MATCH, WRAP, b = a.dp(14)))

        // usage history: per-session open/close times, grouped by day, newest first.
        val histCard = a.card()
        histCard.addView(a.section(L.t("Usage history", "ব্যবহারের হিস্ট্রি")))
        val hist = Extra.historyEntries(a)
        if (hist.isEmpty()) {
            histCard.addView(
                a.tv(L.t("No history yet — recording starts from this version. Accessibility must be on to track app time.", "এখনো কোনো হিস্ট্রি নেই — এই ভার্সন থেকে রেকর্ড শুরু হয়েছে। অ্যাপের সময় ট্র্যাক করতে Accessibility চালু থাকতে হবে।"), 13f, C.muted),
                lp(MATCH, WRAP, t = a.dp(6))
            )
        } else {
            val pm = a.packageManager
            val labelCache = HashMap<String, String>()
            val iconCache = HashMap<String, Drawable?>()
            val byDay = LinkedHashMap<String, ArrayList<UsageSession>>()
            for (s in hist.take(200)) {
                byDay.getOrPut(Store.dayKeyFor(s.open)) { ArrayList() }.add(s)
            }
            val todayKey = Store.dayKey(0)
            val yestKey = Store.dayKey(1)
            for ((dayKey, sessions) in byDay) {
                val dayLabel = when (dayKey) {
                    todayKey -> L.t("Today", "আজ")
                    yestKey -> L.t("Yesterday", "গতকাল")
                    else -> fmtDate(sessions.first().open)
                }
                val dayTotalMs = sessions.sumOf { (it.close - it.open).coerceAtLeast(0L) }
                val header = LinearLayout(a)
                header.gravity = Gravity.CENTER_VERTICAL
                header.addView(a.tv(dayLabel, 14f, C.text, 1), lp(0, WRAP, weight = 1f))
                header.addView(a.tv(fmtDur(dayTotalMs), 13f, C.muted))
                histCard.addView(header, lp(MATCH, WRAP, t = a.dp(12)))
                for (s in sessions) {
                    val r = LinearLayout(a)
                    r.gravity = Gravity.CENTER_VERTICAL
                    r.setPadding(0, a.dp(6), 0, a.dp(6))
                    val icon = iconCache.getOrPut(s.pkg) {
                        try {
                            pm.getApplicationIcon(pm.getApplicationInfo(s.pkg, 0))
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (icon != null) {
                        val iv = ImageView(a)
                        iv.setImageDrawable(icon)
                        r.addView(iv, lp(a.dp(32), a.dp(32), r = a.dp(10)))
                    } else {
                        r.addView(View(a), lp(a.dp(32), a.dp(32), r = a.dp(10)))
                    }
                    val label = labelCache.getOrPut(s.pkg) {
                        try {
                            pm.getApplicationLabel(pm.getApplicationInfo(s.pkg, 0)).toString()
                        } catch (e: Exception) {
                            s.pkg
                        }
                    }
                    val labelCol = LinearLayout(a)
                    labelCol.orientation = LinearLayout.VERTICAL
                    labelCol.addView(a.tv(label, 14f, C.text))
                    labelCol.addView(a.tv(fmtClock(s.open) + " – " + fmtClock(s.close), 12f, C.muted))
                    r.addView(labelCol, lp(0, WRAP, weight = 1f))
                    r.addView(a.tv(fmtDur(s.close - s.open), 13f, C.text, 1))
                    histCard.addView(r, lp(MATCH, WRAP))
                }
            }
            if (hist.size > 200) {
                histCard.addView(
                    a.tv(L.t("Showing the latest 200 sessions", "সর্বশেষ ২০০টি সেশন দেখানো হচ্ছে"), 12f, C.muted),
                    lp(MATCH, WRAP, t = a.dp(8))
                )
            }
        }
        col.addView(histCard, lp(MATCH, WRAP, b = a.dp(14)))

        // temptation counter: blocked/limited apps the user tried to open
        col.addView(a.attemptsCard(), lp(MATCH, WRAP, b = a.dp(14)))

        // history
        val sessHistCard = a.card()
        sessHistCard.addView(a.section(L.t("Session history", "সেশন ইতিহাস")))
        if (all.isEmpty()) {
            val e = a.tv(L.t("No sessions yet", "এখনো কোনো সেশন নেই"), 14f, C.muted)
            e.gravity = Gravity.CENTER
            sessHistCard.addView(e, lp(MATCH, WRAP, t = a.dp(16), b = a.dp(8)))
        } else {
            for (s in all.reversed().take(30)) {
                val r = LinearLayout(a)
                r.gravity = Gravity.CENTER_VERTICAL
                r.setPadding(0, a.dp(10), 0, a.dp(10))
                val left = LinearLayout(a)
                left.orientation = LinearLayout.VERTICAL
                left.addView(a.tv(fmtDate(s.start) + " · " + fmtClock(s.start), 14f, C.text, 1))
                val src = when (s.src) {
                    "schedule" -> L.t("Scheduled", "সময়সূচি")
                    // legacy label: Pomodoro was removed in build 66; old sessions keep their label
                    "pomodoro" -> "Pomodoro"
                    else -> L.t("Manual", "ম্যানুয়াল")
                }
                val state = if (s.ok) L.t("Completed", "সম্পন্ন") else L.t("Stopped early", "আগেই বন্ধ")
                left.addView(a.tv("$src · $state", 12f, C.muted))
                r.addView(left, lp(0, WRAP, weight = 1f))
                r.addView(a.tv(fmtDur(s.dur), 15f, if (s.ok) C.mint else C.warn, 1))
                sessHistCard.addView(r)
            }
        }
        col.addView(sessHistCard)
        return sv
    }

    /** Rebuilds the month grid for the current [calOffset]. */
    private fun buildCalendar() {
        val focus = Store.focusDayMap(a)
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, calOffset)
        val year = c.get(Calendar.YEAR)
        val month = c.get(Calendar.MONTH)
        calLabel.text = L.month(month) + " " + L.num(year.toString())
        calGrid.removeAllViews()
        val first = Calendar.getInstance()
        first.set(year, month, 1)
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // blank cells before the 1st (Monday first)
        val todayKey = Store.dayKey(0)
        var day = 1
        while (day <= daysInMonth) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            for (col in 0 until 7) {
                val cell = LinearLayout(a)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER_HORIZONTAL
                cell.setPadding(0, a.dp(5), 0, a.dp(5))
                if ((day == 1 && col < lead) || day > daysInMonth) {
                    // blank cell
                } else {
                    val key = String.format(Locale.US, "%04d%02d%02d", year, month + 1, day)
                    val min = focus[key] ?: 0
                    val isToday = key == todayKey
                    if (isToday) cell.background = rounded(C.card2, a.dp(10).toFloat())
                    val num = a.tv(L.num(day.toString()), 14f, if (min > 0) C.mint else C.text, if (min > 0 || isToday) 1 else 0)
                    num.gravity = Gravity.CENTER
                    cell.addView(num, lp(MATCH, WRAP))
                    val dot = a.tv(if (min > 0) "●" else "", 8f, C.primary, 1)
                    dot.gravity = Gravity.CENTER
                    cell.addView(dot, lp(MATCH, WRAP))
                    val d = day
                    val m = min
                    cell.pressFeedback()
                    cell.setOnClickListener {
                        calDetail.text = if (m > 0) {
                            L.num(d.toString()) + " " + L.month(month) + ": " + fmtDur(m * 60000L) + " " + L.t("focus", "ফোকাস")
                        } else {
                            L.num(d.toString()) + " " + L.month(month) + ": " + L.t("no completed lock", "কোনো লক সম্পন্ন হয়নি")
                        }
                    }
                    day++
                }
                row.addView(cell, lp(0, WRAP, weight = 1f))
            }
            calGrid.addView(row)
        }
        calDetail.text = L.t("Tap a day to see its focus time", "ফোকাস সময় দেখতে দিনে চাপুন")
    }
}

// =====================================================================
// SETTINGS
// =====================================================================
class SettingsScreen(private val a: MainActivity) {

    /** One strict-protection option as its own glowing card. */
    private fun glowOption(title: String, sub: String, checked: Boolean, locked: Boolean, onChange: (Boolean) -> Unit): View {
        val glow = GlowBorderDrawable(a.dp(20).toFloat(), a.dp(8).toFloat(), a.dp(2).toFloat())
        glow.level = if (checked) 1f else 0f
        val box = LinearLayout(a)
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = glow
        box.setPadding(a.dp(22), a.dp(20), a.dp(22), a.dp(20))
        val texts = LinearLayout(a)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(a.tv(title, 16f, C.text, 1))
        texts.addView(a.tv(sub, 12f, C.muted), lp(MATCH, WRAP, t = a.dp(4)))
        box.addView(texts, lp(0, WRAP, r = a.dp(12), weight = 1f))

        var pulser: ValueAnimator? = null
        fun stopPulse() {
            pulser?.cancel()
            pulser = null
            glow.pulse = 1f
        }
        fun startPulse() {
            pulser?.cancel()
            val p = ValueAnimator.ofFloat(0.55f, 1f)
            p.duration = 1100
            p.repeatMode = ValueAnimator.REVERSE
            p.repeatCount = ValueAnimator.INFINITE
            p.addUpdateListener { glow.pulse = it.animatedValue as Float }
            p.start()
            pulser = p
        }
        val sw = a.toggle(checked) { v ->
            onChange(v)
            val la = ValueAnimator.ofFloat(glow.level, if (v) 1f else 0f)
            la.duration = 300
            la.addUpdateListener { glow.level = it.animatedValue as Float }
            la.start()
            if (v) startPulse() else stopPulse()
        }
        if (locked) sw.isEnabled = false
        box.addView(sw)
        if (checked) startPulse()
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                pulser?.cancel()
            }
        })
        return box
    }

    private fun launch(intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "open settings failed", e)
        }
    }

    private fun blockMessageDialog() {
        val et = a.edit(L.t("Your message", "তোমার মেসেজ"), false)
        et.setText(Extra.blockMessage(a))
        et.setSelection(et.text.length)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(4))
        body.addView(et, lp(MATCH, WRAP))
        body.addView(
            a.tv(L.t("Shown on the block screen when an app is blocked. Empty means the default message.", "অ্যাপ ব্লক হলে এই মেসেজ ব্লক স্ক্রিনে দেখাবে। খালি রাখলে আগের মেসেজই দেখাবে।"), 13f, C.muted),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        a.dlg()
            .setTitle(L.t("Block screen message", "ব্লক স্ক্রিনের মেসেজ"))
            .setView(body)
            .setPositiveButton(L.t("Save", "সংরক্ষণ")) { _, _ ->
                Extra.setBlockMessage(a, et.text.toString().trim())
                a.go(3)
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Pick the focus sound. Choosing one while a lock runs starts it right away. */
    private fun focusSoundDialog() {
        val choices = ArrayList<String>().apply {
            add(FocusSound.OFF)
            add(FocusSound.WHITE)
            add(FocusSound.BROWN)
            add(FocusSound.RAIN)
            add(FocusSound.MIX) // the user's own mix from the sound mixer
            if (LuckyWheel.isOceanUnlocked(a)) add(FocusSound.OCEAN) // wheel-only
        }
        val labels = choices.map { FocusSound.label(it) }.toTypedArray()
        val current = choices.indexOf(FocusSound.choice(a)).coerceAtLeast(0)
        a.dlg()
            .setTitle(L.t("Focus sounds", "ফোকাস সাউন্ড"))
            .setSingleChoiceItems(labels, current) { dlg, which ->
                val picked = choices[which]
                FocusSound.setChoice(a, picked)
                if (picked == FocusSound.OFF) {
                    FocusSound.stop()
                } else if (picked == FocusSound.MIX && SoundMixer.activeChannels(a).isEmpty()) {
                    a.toast(L.t("Turn on at least one sound in the mixer first", "আগে মিক্সারে অন্তত একটা সাউন্ড চালু করুন"))
                } else if (Store.isActive(a)) {
                    // explicit choice: start even on silent/vibrate
                    FocusSound.startExplicit(a)
                }
                dlg.dismiss()
                a.go(3)
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .show()
    }

    /** Sound mixer: per-channel volume sliders, a preview, and one saved mix ("My mix"). */
    private fun mixerDialog() {
        val vols = SoundMixer.volumes(a).toMutableMap()
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        box.addView(
            a.tv(L.t("Mix the focus sounds — each slider is one sound's volume.", "ফোকাস সাউন্ড মেশান — প্রতিটা স্লাইডার একটা সাউন্ডের ভলিউম।"), 13f, C.muted),
            lp(MATCH, WRAP, t = a.dp(4))
        )
        for (ch in SoundMixer.CHANNELS) {
            val locked = ch == FocusSound.OCEAN && !LuckyWheel.isOceanUnlocked(a)
            val head = LinearLayout(a)
            head.gravity = Gravity.CENTER_VERTICAL
            head.addView(
                a.tv(
                    FocusSound.label(ch) + if (locked) " · " + L.t("win it on the wheel", "চাকায় জিতুন") else "",
                    14f, if (locked) C.muted else C.text, 1
                ),
                lp(0, WRAP, weight = 1f)
            )
            val valTxt = a.tv(L.num((vols[ch] ?: 0).toString()) + "%", 14f, C.primary, 1)
            head.addView(valTxt)
            box.addView(head, lp(MATCH, WRAP, t = a.dp(14)))
            val seek = SeekBar(a)
            seek.max = 100
            seek.progress = vols[ch] ?: 0
            seek.isEnabled = !locked
            seek.progressTintList = ColorStateList.valueOf(C.primary)
            seek.progressBackgroundTintList = ColorStateList.valueOf(C.card2)
            seek.thumbTintList = ColorStateList.valueOf(C.mint)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    vols[ch] = p
                    valTxt.setTextAnimated(L.num(p.toString()) + "%")
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            box.addView(seek, lp(MATCH, WRAP, t = a.dp(4)))
        }
        // preview: plays the current (unsaved) slider values right away
        val previewRow = LinearLayout(a)
        previewRow.gravity = Gravity.CENTER_VERTICAL
        val playBtn = a.outlineBtn(L.t("Listen", "শুনুন"), C.primary)
        playBtn.setOnClickListener {
            if (SoundMixer.activeChannels(a, vols).isEmpty()) {
                a.toast(L.t("Turn on at least one sound first", "আগে অন্তত একটা সাউন্ড চালু করুন"))
            } else {
                FocusSound.previewMix(a, vols)
            }
        }
        previewRow.addView(playBtn, lp(0, a.dp(48), weight = 1f, r = a.dp(8)))
        val stopBtn = a.outlineBtn(L.t("Stop", "থামান"), C.muted)
        stopBtn.setOnClickListener {
            FocusSound.stop()
            FocusSound.sync(a)
        }
        previewRow.addView(stopBtn, lp(0, a.dp(48), weight = 1f))
        box.addView(previewRow, lp(MATCH, WRAP, t = a.dp(16)))
        a.dlg()
            .setTitle(L.t("Sound mixer", "সাউন্ড মিক্সার"))
            .setView(box)
            .setPositiveButton(L.t("Save my mix", "আমার মিক্স সেভ করুন")) { _, _ ->
                SoundMixer.save(a, vols)
                // a playing mix restarts with the saved volumes; a preview stops
                FocusSound.stop()
                FocusSound.sync(a)
                a.toast(L.t("Mix saved — pick \"My mix\" in the focus sounds", "মিক্স সেভ হয়েছে — ফোকাস সাউন্ডে \"আমার মিক্স\" বাছুন"))
                a.go(3)
            }
            .setNegativeButton(L.t("Cancel", "বাতিল"), null)
            .setOnDismissListener {
                // a preview must never keep playing after the dialog closes;
                // the lock's own sound (if any) is restored by sync()
                FocusSound.stop()
                FocusSound.sync(a)
            }
            .show()
    }

    /** Home background photo: pick from the gallery, or remove the current one. */
    private fun wallpaperDialog() {
        val items = ArrayList<String>()
        items.add(L.t("Pick a photo", "ছবি বাছুন"))
        if (Wallpaper.has(a)) items.add(L.t("Remove the photo", "ছবি সরান"))
        a.dlg()
            .setTitle(L.t("Home background photo", "হোমের ছবি"))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                    a.startActivityForResult(i, REQ_WALLPAPER)
                } else {
                    Wallpaper.remove(a)
                    a.crossfade { }
                }
            }
            .show()
    }

    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Settings", "সেটিংস"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))

        // language: card with two chips (বাংলা | English)
        val lang = a.card()
        lang.addView(a.section(L.t("Language", "ভাষা")))
        val lr = LinearLayout(a)
        val bnChip = a.chip("বাংলা", L.bn)
        val enChip = a.chip("English", !L.bn)
        fun pickLang(code: String) {
            if (Store.settings(a).getString("lang", "bn") == code) return
            a.animateChip(bnChip, code == "bn")
            a.animateChip(enChip, code == "en")
            Store.settings(a).edit().putString("lang", code).apply()
            lr.postDelayed({
                a.crossfade {
                    L.load(a)
                    Store.kickService(a)
                }
            }, 240)
        }
        bnChip.setOnClickListener { pickLang("bn") }
        enChip.setOnClickListener { pickLang("en") }
        lr.addView(bnChip, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        lr.addView(enChip, lp(0, WRAP, t = a.dp(10), weight = 1f))
        lang.addView(lr)
        col.addView(lang, lp(MATCH, WRAP, b = a.dp(12)))

        // theme: card with two chips (এমারেল্ড ডার্ক | অ্যামোলেড ব্ল্যাক)
        val theme = a.card()
        theme.addView(a.section(L.t("Theme", "থিম")))
        val tr = LinearLayout(a)
        val cur = Store.settings(a).getInt("theme", 0)
        val t0 = a.chip(L.t("Emerald Dark", "এমারেল্ড ডার্ক"), cur == 0)
        val t1 = a.chip(L.t("AMOLED Black", "অ্যামোলেড ব্ল্যাক"), cur == 1)
        fun pickTheme(i: Int) {
            if (Store.settings(a).getInt("theme", 0) == i) return
            a.animateChip(t0, i == 0)
            a.animateChip(t1, i == 1)
            Store.settings(a).edit().putInt("theme", i).apply()
            tr.postDelayed({ a.crossfade { C.load(a) } }, 240)
        }
        t0.setOnClickListener { pickTheme(0) }
        t1.setOnClickListener { pickTheme(1) }
        tr.addView(t0, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        tr.addView(t1, lp(0, WRAP, t = a.dp(10), weight = 1f))
        theme.addView(tr)
        // accent colour: 5 base swatches + 3 wheel-only + 4 progress-locked ones
        val acLabel = a.tv(L.t("Accent colour", "অ্যাকসেন্ট রঙ"), 13f, C.muted, 1)
        theme.addView(acLabel, lp(MATCH, WRAP, t = a.dp(16)))
        val allAccents = C.allAccents()
        val acRow1 = LinearLayout(a)
        acRow1.gravity = Gravity.CENTER_VERTICAL
        val acRow2 = LinearLayout(a)
        acRow2.gravity = Gravity.CENTER_VERTICAL
        val acRow3 = LinearLayout(a)
        acRow3.gravity = Gravity.CENTER_VERTICAL
        val swatches = ArrayList<View>()
        fun styleSwatches() {
            val sel = Store.settings(a).getInt("accent", 0)
            for ((i, sw) in swatches.withIndex()) {
                val col = Color.parseColor(allAccents[i][0])
                sw.background = if (i == sel) rounded(col, a.dp(20).toFloat(), C.text, a.dp(2))
                else rounded(col, a.dp(20).toFloat())
                sw.alpha = if (ThemeUnlocks.isUnlocked(a, i)) 1f else 0.35f
            }
        }
        fun pickAccent(i: Int) {
            if (!ThemeUnlocks.isUnlocked(a, i)) {
                if (i >= ThemeUnlocks.FIRST) {
                    val name = C.UNLOCK_ACCENT_NAMES[i - ThemeUnlocks.FIRST]
                    a.toast(L.t(name.first, name.second) + " — " + ThemeUnlocks.condition(i))
                } else {
                    a.toast(L.t("Win this colour from the lucky wheel", "এই রঙ লাকি হুইল থেকে জিতুন"))
                }
                return
            }
            if (Store.settings(a).getInt("accent", 0) == i) return
            Store.settings(a).edit().putInt("accent", i).apply()
            styleSwatches()
            acRow1.postDelayed({ a.crossfade { C.load(a) } }, 240)
        }
        for (i in allAccents.indices) {
            val sw = View(a)
            sw.setOnClickListener { pickAccent(i) }
            sw.pressFeedback()
            swatches.add(sw)
            val row = when {
                i < C.ACCENTS.size -> acRow1
                i < C.ACCENTS.size + C.WHEEL_ACCENTS.size -> acRow2
                else -> acRow3
            }
            val last = when {
                i < C.ACCENTS.size -> i == C.ACCENTS.size - 1
                i < C.ACCENTS.size + C.WHEEL_ACCENTS.size -> i == C.ACCENTS.size + C.WHEEL_ACCENTS.size - 1
                else -> i == allAccents.size - 1
            }
            row.addView(sw, lp(a.dp(40), a.dp(40), r = if (last) 0 else a.dp(12), t = a.dp(10)))
        }
        styleSwatches()
        theme.addView(acRow1)
        theme.addView(acRow2)
        theme.addView(acRow3)
        theme.addView(
            a.tv(ThemeUnlocks.hintLine(), 12f, C.muted),
            lp(MATCH, WRAP, t = a.dp(8))
        )
        col.addView(theme, lp(MATCH, WRAP, b = a.dp(12)))

        // morning reminder: a daily notification to start the first lock
        val morn = a.card()
        morn.addView(a.section(L.t("Morning reminder", "সকালের রিমাইন্ডার")))
        val mornSw = a.toggle(Reminders.morningOn(a)) { v ->
            Reminders.setMorning(a, v, Reminders.morningMin(a))
        }
        morn.addView(
            a.row(
                L.t("Remind me every morning", "প্রতি সকালে মনে করিয়ে দিন"),
                L.t("A notification to start today's first lock.", "আজকের প্রথম লক শুরু করার নোটিফিকেশন।"),
                mornSw
            )
        )
        val mornTimeText = a.tv(fmtMin(Reminders.morningMin(a)), 16f, C.primary, 1)
        morn.addView(a.row(L.t("Reminder time", "রিমাইন্ডারের সময়"), null, mornTimeText) {
            val cur = Reminders.morningMin(a)
            TimePickerDialog(a, { _, h, m ->
                val min = h * 60 + m
                Reminders.setMorning(a, Reminders.morningOn(a), min)
                mornTimeText.text = fmtMin(min)
            }, cur / 60, cur % 60, false).show()
        })
        col.addView(morn, lp(MATCH, WRAP, b = a.dp(12)))

        // routine: how long the penalty lock lasts when a task is missed
        val routine = a.card()
        routine.addView(a.section(L.t("Routine", "রুটিন")))
        routine.addView(
            a.tv(
                L.t("Miss a routine task and the phone locks for this long. Finish the task and check it off to unlock early.", "রুটিনের কাজ বাদ গেলে ফোন এতক্ষণ লক হবে। কাজ শেষ করে টিক দিলে আগেই খুলবে।"),
                13f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(8))
        )
        val penRow = LinearLayout(a)
        val penChips = ArrayList<TextView>()
        val penOpts = listOf(15, 30, 60)
        for (m in penOpts) {
            val ch = a.chip(L.num(m.toString()) + " " + L.t("min", "মিনিট"), Routine.penaltyMin(a) == m)
            ch.setOnClickListener {
                Routine.setPenaltyMin(a, m)
                for ((i, c) in penChips.withIndex()) a.animateChip(c, penOpts[i] == m)
            }
            penChips.add(ch)
            penRow.addView(ch, lp(0, WRAP, r = a.dp(8), t = a.dp(10), weight = 1f))
        }
        routine.addView(penRow)
        col.addView(routine, lp(MATCH, WRAP, b = a.dp(12)))

        // strict protection
        val strictCard = a.card()
        strictCard.addView(a.section(L.t("Strict protection", "কঠোর সুরক্ষা")))
        val locked = Store.isActive(a)
        val prefs = Store.settings(a)
        strictCard.addView(
            glowOption(
                L.t("Strict mode", "স্ট্রিক্ট মোড"),
                L.t("No early stop. Locked until the timer ends.", "আগে বন্ধ করা যাবে না। সময় শেষ না হওয়া পর্যন্ত লক।"),
                prefs.getBoolean("strict", false),
                locked
            ) { v -> prefs.edit().putBoolean("strict", v).apply() },
            lp(MATCH, WRAP, t = a.dp(8))
        )
        strictCard.addView(
            glowOption(
                L.t("Protect app settings", "অ্যাপ সেটিংস সুরক্ষা"),
                L.t("Block App info and Accessibility pages while the timer runs.", "টাইমার চলাকালীন App info ও Accessibility পাতা খোলা যাবে না।"),
                prefs.getBoolean("guard", true),
                locked
            ) { v -> prefs.edit().putBoolean("guard", v).apply() },
            lp(MATCH, WRAP, t = a.dp(2))
        )
        val note = if (locked) L.t("Locked while a session is active.", "সেশন চলাকালীন বদলানো যাবে না।")
        else L.t("Changes apply from the next session.", "পরিবর্তন পরের সেশন থেকে কার্যকর হবে।")
        strictCard.addView(a.tv(note, 12f, if (locked) C.warn else C.muted), lp(MATCH, WRAP, t = a.dp(8)))
        col.addView(strictCard, lp(MATCH, WRAP, b = a.dp(12)))

        // device admin: while active, Android blocks uninstalling the app
        if (DeviceAdmin.isActive(a)) DeviceAdmin.noteActive(a)
        val adminCard = a.card()
        adminCard.addView(a.section(L.t("Device admin", "ডিভাইস অ্যাডমিন")))
        val adminOn = DeviceAdmin.isActive(a)
        if (adminOn) {
            adminCard.addView(
                a.tv(L.t("✔ Active — PowerLock 2 cannot be uninstalled right now.", "✔ চালু আছে — এখন PowerLock 2 আনইনস্টল করা যাবে না।"), 14f, C.mint, 1),
                lp(MATCH, WRAP, t = a.dp(8))
            )
            adminCard.addView(
                a.tv(
                    L.t("To turn it off: system Settings → Security → Device admin apps. That deliberate step is the whole point — it stops impulsive uninstalls during a lock.", "বন্ধ করতে: সিস্টেম সেটিংস → নিরাপত্তা → ডিভাইস অ্যাডমিন অ্যাপ। এই ভেবেচিন্তে করা ধাপটাই সুরক্ষা — লক চলাকালীন হঠাৎ আনইনস্টল ঠেকায়।"),
                    13f, C.muted
                ),
                lp(MATCH, WRAP, t = a.dp(8))
            )
            adminCard.addView(
                a.row(L.t("Open security settings", "নিরাপত্তা সেটিংস খুলুন"), null, a.tv("›", 22f, C.muted)) {
                    launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            )
        } else {
            adminCard.addView(
                a.tv(
                    L.t("While active, Android blocks uninstalling PowerLock 2 — you must first turn the admin off in system Settings. It stops impulsive uninstalls during a lock.", "চালু থাকলে Android PowerLock 2 আনইনস্টল আটকাবে — আগে সিস্টেম সেটিংসে অ্যাডমিন বন্ধ করতে হবে। লক চলাকালীন হঠাৎ আনইনস্টল ঠেকায়।"),
                    13f, C.muted
                ),
                lp(MATCH, WRAP, t = a.dp(8))
            )
            val offAt = DeviceAdmin.deactivatedAt(a)
            if (offAt > 0) {
                adminCard.addView(
                    a.tv(
                        L.t("Turned off: ", "বন্ধ করা হয়েছিল: ") + fmtDate(offAt) + " " + fmtClock(offAt),
                        13f, C.warn
                    ),
                    lp(MATCH, WRAP, t = a.dp(8))
                )
            }
            val enableBtn = a.outlineBtn(L.t("Enable device admin", "ডিভাইস অ্যাডমিন চালু করুন"), C.primary)
            enableBtn.setOnClickListener {
                try {
                    a.startActivity(DeviceAdmin.enableIntent(a))
                } catch (e: Exception) {
                    Log.e(TAG, "admin enable failed", e)
                }
            }
            adminCard.addView(enableBtn, lp(MATCH, a.dp(48), t = a.dp(12)))
        }
        adminCard.addView(
            a.tv(
                L.t("Honest note: turning the admin off always allows uninstalling — this only makes it a deliberate two-step action, not a one-tap impulse.", "সত্যি কথা: অ্যাডমিন বন্ধ করলেই আনইনস্টল করা যাবে — এটা শুধু এক চাপের বদলে ভেবেচিন্তে দুই ধাপে করতে হয়।"),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        col.addView(adminCard, lp(MATCH, WRAP, b = a.dp(12)))

        // location lock first, then focus mode (defined in Features.kt)
        col.addView(a.locationCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.historyCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.focusCard(), lp(MATCH, WRAP, b = a.dp(12)))

        // general
        val gen = a.card()
        gen.addView(a.section(L.t("General", "সাধারণ")))
        gen.addView(
            a.row(
                L.t("Sound and vibration", "সাউন্ড ও ভাইব্রেশন"),
                L.t("When a session ends", "সেশন শেষ হলে"),
                a.toggle(Store.soundVib(a)) { v -> Store.settings(a).edit().putBoolean("sound", v).apply() }
            )
        )
        // silence notifications during a lock (DND) — if the permission was
        // revoked, the toggle turns itself off with a toast, never a crash
        if (Dnd.isOn(a) && !Dnd.isGranted(a)) {
            Store.settings(a).edit().putBoolean("dnd_on", false).apply()
            a.toast(L.t("Do Not Disturb permission was revoked — silencing turned off", "Do Not Disturb পারমিশন তুলে নেওয়া হয়েছে — নোটিফিকেশন বন্ধের অপশন বন্ধ করা হলো"))
        }
        lateinit var dndSw: PremiumSwitch
        dndSw = a.toggle(Dnd.isOn(a)) { v ->
            if (v && !Dnd.isGranted(a)) {
                dndSw.setCheckedImmediate(false)
                a.toast(L.t("Allow Do Not Disturb access first, then turn this on again", "আগে Do Not Disturb অনুমতি দিন, তারপর আবার চালু করুন"))
                try {
                    a.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Log.e(TAG, "DND settings failed", e)
                }
                return@toggle
            }
            Store.settings(a).edit().putBoolean("dnd_on", v).apply()
        }
        gen.addView(
            a.row(
                L.t("Silence notifications during lock", "লক চলাকালীন নোটিফিকেশন বন্ধ"),
                L.t("Calls are silenced too — alarms still ring.", "কলও চুপ থাকবে — অ্যালার্ম বাজবে।"),
                dndSw
            )
        )
        // custom message on the block screen
        val msg = Extra.blockMessage(a)
        gen.addView(
            a.row(
                L.t("Block screen message", "ব্লক স্ক্রিনের মেসেজ"),
                if (msg.isBlank()) L.t("Tap to write your own motivational message", "নিজের মোটিভেশনাল মেসেজ লিখতে চাপুন") else msg,
                a.tv("›", 22f, C.muted)
            ) { blockMessageDialog() }
        )
        // focus sound during a lock (generated in code — no downloads)
        gen.addView(
            a.row(
                L.t("Focus sounds", "ফোকাস সাউন্ড"),
                FocusSound.label(FocusSound.choice(a)) + " · " + L.t("plays during a lock", "লক চলাকালীন বাজবে"),
                a.tv("›", 22f, C.muted)
            ) { focusSoundDialog() }
        )
        // sound mixer: blend the generated sounds into one custom mix
        gen.addView(
            a.row(
                L.t("Sound mixer", "সাউন্ড মিক্সার"),
                SoundMixer.summary(a),
                a.tv("›", 22f, C.muted)
            ) { mixerDialog() }
        )
        // home background photo: a gallery photo behind the Home screen, dimmed
        gen.addView(
            a.row(
                L.t("Home background photo", "হোমের ছবি"),
                if (Wallpaper.has(a)) L.t("Tap to change or remove", "বদলাতে বা সরাতে চাপুন")
                else L.t("Tap to pick a photo", "ছবি বাছতে চাপুন"),
                a.tv("›", 22f, C.muted)
            ) { wallpaperDialog() }
        )
        gen.addView(
            a.row(L.t("Notification settings", "নোটিফিকেশন সেটিংস"), null, a.tv("›", 22f, C.muted)) {
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
            }
        )
        gen.addView(a.row(L.t("Weekly schedule", "সাপ্তাহিক সময়সূচি"), null, a.tv("›", 22f, C.muted)) { a.go(1) })
        gen.addView(a.row(L.t("Statistics", "পরিসংখ্যান"), null, a.tv("›", 22f, C.muted)) { a.go(2) })
        col.addView(gen, lp(MATCH, WRAP, b = a.dp(12)))

        // permissions
        val perm = a.card()
        perm.addView(a.section(L.t("Permission status", "পারমিশনের অবস্থা")))
        fun permRow(title: String, ok: Boolean, fix: () -> Unit) {
            val mark = a.tv(if (ok) "✔" else "✘", 18f, if (ok) C.primary else C.danger, 1)
            val sub = if (ok) L.t("Granted", "অনুমোদিত") else L.t("Tap to fix", "ঠিক করতে চাপুন")
            perm.addView(a.row(title, sub, mark, if (ok) null else fix))
        }
        permRow("Accessibility", Perm.accessibilityOn(a)) {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permRow(L.t("Notifications", "নোটিফিকেশন"), Perm.notificationsOn(a)) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (!Store.settings(a).getBoolean("notif_asked", false)) {
                    // never asked: ask directly (the system prompt still works)
                    Store.settings(a).edit().putBoolean("notif_asked", true).apply()
                    a.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
                } else {
                    // already denied: the system prompt is gone for good, so
                    // the user must turn notifications on in system settings
                    launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
                }
            } else {
                // API < 33 has no runtime notification permission: go straight to system settings
                launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
            }
        }
        permRow(L.t("Battery optimization off", "ব্যাটারি অপটিমাইজেশন বন্ধ"), Perm.batteryOk(a)) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        permRow(L.t("Exact alarms (schedules)", "নির্দিষ্ট সময়ের অ্যালার্ম"), Perm.exactAlarmOk(a)) {
            if (Build.VERSION.SDK_INT >= 31) {
                launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.packageName)))
            }
        }
        if (!Perm.notificationsOn(a)) {
            perm.addView(
                a.tv(
                    L.t("Notifications are off — reminders and lock-end alerts won't appear. Tap the Notifications row above to turn them on.", "নোটিফিকেশন বন্ধ — রিমাইন্ডার আর লক শেষের সতর্কতা দেখা যাবে না। চালু করতে উপরের নোটিফিকেশন লাইনে চাপুন।"),
                    13f, C.muted
                ),
                lp(MATCH, WRAP, t = a.dp(8))
            )
        }
        col.addView(perm, lp(MATCH, WRAP, b = a.dp(12)))

        // about
        val about = a.card()
        about.addView(a.section(L.t("About PowerLock 2", "PowerLock 2 সম্পর্কে")))
        about.addView(a.tv("PowerLock 2", 18f, C.text, 1), lp(MATCH, WRAP, t = a.dp(10)))
        about.addView(a.tv(L.t("Version ", "সংস্করণ ") + L.num(APP_VERSION), 13f, C.muted))
        about.addView(a.tv(L.t("Build ", "বিল্ড ") + L.num(Extra.BUILD_ID), 13f, C.muted))
        about.addView(a.tv(a.packageName, 12f, C.muted))
        about.addView(
            a.tv(
                L.t(
                    "Note: a normal Android app cannot block the hardware power button, force restart (Power + Volume Down), recovery mode or every system power menu. PowerLock only closes the supported power menu while protection is active.",
                    "দ্রষ্টব্য: সাধারণ অ্যাপ হার্ডওয়্যার পাওয়ার বাটন, ফোর্স রিস্টার্ট (Power + Volume Down), রিকভারি মোড বা সব সিস্টেম পাওয়ার মেনু আটকাতে পারে না। প্রটেকশন চলাকালীন PowerLock শুধু সমর্থিত পাওয়ার মেনুটি বন্ধ করে দেয়।"
                ),
                12f, C.muted
            ),
            lp(MATCH, WRAP, t = a.dp(10))
        )
        col.addView(about)
        return sv
    }
}

// =====================================================================
// PROTECTION (app block: full block + reels block + search block)
// =====================================================================
class ProtectionScreen(private val a: MainActivity) {
    fun build(): View {
        val (sv, col) = a.page()
        col.addView(a.tv(L.t("Protection", "সুরক্ষা"), 24f, C.text, 1), lp(MATCH, WRAP, b = a.dp(14)))
        col.addView(a.protectionIntroCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("full"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("full"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("reels"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("reels"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appBlockModeCard("msg"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.lockedAppsCard("msg"), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.dailyLimitsCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.appGroupsCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.profilesCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.siteBlockCard(), lp(MATCH, WRAP, b = a.dp(12)))
        col.addView(a.diagnosticCard(), lp(MATCH, WRAP, b = a.dp(12)))
        return sv
    }
}
