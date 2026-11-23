package com.powerlock.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import kotlin.random.Random

// =====================================================================
// FOCUS PET
// =====================================================================

/**
 * Focus pet: a virtual cat on Home. Happiness 0–100: +10 per completed lock,
 * −15 per lock broken early. Mood is derived from the score and the drawing
 * changes face per mood. Reactions are one-time flags shown by the Home tick
 * (happy rides in the session-summary dialog, sad is a toast) — the same
 * pattern as the tree outcome.
 */
object FocusPet {
    fun happiness(ctx: Context): Int =
        Store.lock(ctx).getInt("pet_happy", 50).coerceIn(0, 100)

    fun name(ctx: Context): String {
        val saved = Store.lock(ctx).getString("pet_name", null)
        return if (saved.isNullOrBlank()) L.t("Mimi", "মিমি") else saved
    }

    fun setName(ctx: Context, n: String) {
        Store.lock(ctx).edit().putString("pet_name", n.trim().take(20)).apply()
    }

    /** 0 happy, 1 content, 2 sad. */
    fun mood(ctx: Context): Int = when (happiness(ctx)) {
        in 70..100 -> 0
        in 40..69 -> 1
        else -> 2
    }

    fun moodLabel(ctx: Context): String = when (mood(ctx)) {
        0 -> L.t("Very happy", "খুব খুশি")
        1 -> L.t("Content", "ভালো আছে")
        else -> L.t("Sad", "দুঃখী")
    }

    /** Called from Store.addSession for every completed lock. */
    fun onLockCompleted(ctx: Context) {
        val p = Store.lock(ctx)
        p.edit()
            .putInt("pet_happy", (happiness(ctx) + 10).coerceAtMost(100))
            .putString("pet_react", "happy")
            .apply()
    }

    /** Called from Store.addSession for every lock stopped early. */
    fun onLockBroken(ctx: Context) {
        val p = Store.lock(ctx)
        p.edit()
            .putInt("pet_happy", (happiness(ctx) - 15).coerceAtLeast(0))
            .putString("pet_react", "sad")
            .apply()
    }

    /** The unshown reaction ("happy"/"sad"), or null. */
    fun reactPending(ctx: Context): String? =
        Store.lock(ctx).getString("pet_react", null)

    fun clearReact(ctx: Context) {
        Store.lock(ctx).edit().remove("pet_react").apply()
    }
}

/** Canvas-drawn cat: round body, triangle ears, tail, and a face per mood. */
class PetView(ctx: Context) : View(ctx) {
    /** 0 happy, 1 content, 2 sad. */
    var mood: Int = 1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val bodyCol = Color.parseColor("#FF9F43")
        val darkCol = Color.parseColor("#E8833A")
        val bellyCol = Color.parseColor("#FFE3C2")
        paint.style = Paint.Style.FILL

        // tail: a curved stroke behind the body
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = w * 0.045f
        paint.color = bodyCol
        val tail = Path()
        tail.moveTo(cx + w * 0.26f, h * 0.78f)
        tail.quadTo(cx + w * 0.42f, h * 0.80f, cx + w * 0.40f, h * 0.58f)
        canvas.drawPath(tail, paint)

        // ears
        paint.style = Paint.Style.FILL
        paint.color = bodyCol
        val earL = Path()
        earL.moveTo(cx - w * 0.20f, h * 0.38f)
        earL.lineTo(cx - w * 0.24f, h * 0.18f)
        earL.lineTo(cx - w * 0.08f, h * 0.28f)
        earL.close()
        canvas.drawPath(earL, paint)
        val earR = Path()
        earR.moveTo(cx + w * 0.20f, h * 0.38f)
        earR.lineTo(cx + w * 0.24f, h * 0.18f)
        earR.lineTo(cx + w * 0.08f, h * 0.28f)
        earR.close()
        canvas.drawPath(earR, paint)
        // inner ears
        paint.color = Color.parseColor("#FFC9A3")
        val inL = Path()
        inL.moveTo(cx - w * 0.185f, h * 0.36f)
        inL.lineTo(cx - w * 0.21f, h * 0.24f)
        inL.lineTo(cx - w * 0.11f, h * 0.30f)
        inL.close()
        canvas.drawPath(inL, paint)
        val inR = Path()
        inR.moveTo(cx + w * 0.185f, h * 0.36f)
        inR.lineTo(cx + w * 0.21f, h * 0.24f)
        inR.lineTo(cx + w * 0.11f, h * 0.30f)
        inR.close()
        canvas.drawPath(inR, paint)

        // body + head
        paint.color = bodyCol
        canvas.drawOval(cx - w * 0.28f, h * 0.52f, cx + w * 0.28f, h * 0.86f, paint)
        canvas.drawCircle(cx, h * 0.40f, w * 0.24f, paint)
        // belly patch
        paint.color = bellyCol
        canvas.drawOval(cx - w * 0.13f, h * 0.60f, cx + w * 0.13f, h * 0.82f, paint)
        // stripes on the head
        paint.color = darkCol
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.02f
        canvas.drawLine(cx - w * 0.08f, h * 0.20f, cx - w * 0.08f, h * 0.26f, paint)
        canvas.drawLine(cx, h * 0.18f, cx, h * 0.24f, paint)
        canvas.drawLine(cx + w * 0.08f, h * 0.20f, cx + w * 0.08f, h * 0.26f, paint)

        // face
        val eyeY = h * 0.38f
        val eyeDx = w * 0.10f
        val eyeR = w * 0.028f
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#2B1A10")
        when (mood) {
            0 -> {
                // happy: closed upward-curved eyes (arcs)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.022f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(cx - eyeDx - eyeR, eyeY - eyeR, cx - eyeDx + eyeR, eyeY + eyeR, 200f, 140f, false, paint)
                canvas.drawArc(cx + eyeDx - eyeR, eyeY - eyeR, cx + eyeDx + eyeR, eyeY + eyeR, 200f, 140f, false, paint)
                // open smile
                paint.style = Paint.Style.FILL
                canvas.drawArc(cx - w * 0.06f, h * 0.44f, cx + w * 0.06f, h * 0.54f, 0f, 180f, true, paint)
            }
            2 -> {
                // sad: round eyes + a tear + downturned mouth
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR, paint)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR, paint)
                paint.color = Color.parseColor("#64B5F6")
                canvas.drawCircle(cx - eyeDx, eyeY + h * 0.06f, w * 0.018f, paint)
                paint.color = Color.parseColor("#2B1A10")
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.022f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(cx - w * 0.05f, h * 0.46f, cx + w * 0.05f, h * 0.54f, 180f, 180f, false, paint)
            }
            else -> {
                // content: round eyes + small smile
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR, paint)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.022f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(cx - w * 0.05f, h * 0.44f, cx + w * 0.05f, h * 0.52f, 20f, 140f, false, paint)
            }
        }
        // nose + whiskers
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#E85D75")
        canvas.drawCircle(cx, h * 0.44f, w * 0.018f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.012f
        paint.color = Color.parseColor("#8A5A3B")
        canvas.drawLine(cx - w * 0.22f, h * 0.40f, cx - w * 0.30f, h * 0.38f, paint)
        canvas.drawLine(cx - w * 0.22f, h * 0.44f, cx - w * 0.30f, h * 0.46f, paint)
        canvas.drawLine(cx + w * 0.22f, h * 0.40f, cx + w * 0.30f, h * 0.38f, paint)
        canvas.drawLine(cx + w * 0.22f, h * 0.44f, cx + w * 0.30f, h * 0.46f, paint)
    }
}

// =====================================================================
// UNLOCKABLE STORY
// =====================================================================

data class Chapter(val titleEn: String, val titleBn: String, val bodyEn: String, val bodyBn: String)

/**
 * An original adventure story in 10 chapters: "The Lantern of the Quiet
 * Forest". One chapter unlocks per completed lock, derived from the existing
 * sessions data (no separate counter). The seen count backfills silently on
 * first read, so long-time users keep the chapters they already earned and
 * only NEW unlocks are announced in the session-complete dialog.
 */
object Story {
    const val TOTAL = 10

    val CHAPTERS = listOf(
        Chapter(
            "The Dark Forest", "অন্ধকার বন",
            "In the middle of the Quiet Forest stood a great lantern on a tall stone. Every evening it lit up the paths so all the animals could find their way home.\n\nOne autumn night, a strong wind blew through the trees — and the lantern went out. The forest fell dark, and the animals were afraid to move.\n\nOnly Rupa, a small fox with bright eyes, was not afraid. \"I will find the light again,\" she said, and packed a little bag for the road.",
            "নীরব বনের মাঝখানে এক উঁচু পাথরের উপর দাঁড়িয়ে ছিল এক বড় লণ্ঠন। প্রতিদিন সন্ধ্যায় সেটা পথ আলোকিত করত, যাতে সব প্রাণী বাড়ি ফিরতে পারে।\n\nএক শরতের রাতে জোরে বাতাস বয়ে গেল গাছের ফাঁক দিয়ে — আর লণ্ঠনটা নিভে গেল। বন অন্ধকারে ডুবে গেল, আর প্রাণীরা ভয়ে নড়তে পারছিল না।\n\nশুধু রূপা, উজ্জ্বল চোখের এক ছোট শিয়াল, ভয় পায়নি। \"আমি আবার আলো খুঁজে আনব,\" সে বলল, আর রাস্তার জন্য একটা ছোট ব্যাগ গুছিয়ে নিল।"
        ),
        Chapter(
            "The Old Owl", "বুড়ো পেঁচা",
            "Rupa walked to the oldest tree in the forest, where the old owl lived. The owl had seen a hundred winters and knew every secret of the forest.\n\n\"The lantern's flame comes from the Star Pool,\" said the owl. \"It lies far across the river, beyond the Mountain of Fog. Few have ever reached it.\"\n\n\"Then I will be one of the few,\" said Rupa. The owl smiled and gave her a tiny bell. \"Ring it when you are lost,\" he said.",
            "রূপা হেঁটে গেল বনের সবচেয়ে পুরনো গাছের কাছে, যেখানে বুড়ো পেঁচা থাকত। পেঁচা একশো শীত দেখেছে আর বনের সব গোপন কথা জানত।\n\n\"লণ্ঠনের আগুন আসে তারার পুকুর থেকে,\" পেঁচা বলল। \"সেটা নদীর ওপারে, কুয়াশার পাহাড়ের পরে। খুব কম প্রাণীই কখনো সেখানে পৌঁছেছে।\"\n\n\"তাহলে আমি সেই কম প্রাণীদের একজন হব,\" রূপা বলল। পেঁচা হেসে তাকে একটা ছোট ঘণ্টা দিল। \"হারিয়ে গেলে এটা বাজিও,\" সে বলল।"
        ),
        Chapter(
            "The River", "নদী",
            "The river was wide and fast, and Rupa could not swim across it. She sat on the bank and thought hard.\n\nJust then an old turtle surfaced beside her. \"Climb on my back, little fox,\" he said. \"I cross this river every day.\"\n\nThe crossing was slow, but the turtle told her stories of the river the whole way. Rupa learned that slow is not the same as late — and that asking for help is a kind of courage too.",
            "নদীটা ছিল চওড়া আর স্রোত ছিল তীব্র, রূপা সাঁতরে পার হতে পারল না। সে তীরে বসে গভীরভাবে ভাবতে লাগল।\n\nতখনই এক বুড়ো কচ্ছপ তার পাশে ভেসে উঠল। \"আমার পিঠে উঠে বসো, ছোট শিয়াল,\" সে বলল। \"আমি প্রতিদিন এই নদী পার হই।\"\n\nপার হওয়াটা ছিল ধীর, কিন্তু কচ্ছপ পুরো পথ নদীর গল্প শোনাল। রূপা শিখল যে ধীর মানেই দেরি নয় — আর সাহায্য চাওয়াও এক ধরনের সাহস।"
        ),
        Chapter(
            "The Whispering Reeds", "ফিসফিসে নলখাগড়া",
            "Beyond the river stood a field of tall reeds that whispered in the wind. As Rupa walked between them, the reeds began to ask her riddles.\n\n\"What runs but never walks?\" whispered one reed. \"Water,\" said Rupa.\n\n\"What has a face but never smiles?\" whispered another. \"A clock,\" said Rupa. The reeds bowed low and gave her a silver feather. \"For the storm ahead,\" they whispered.",
            "নদীর ওপারে ছিল উঁচু নলখাগড়ার এক মাঠ, যারা বাতাসে ফিসফিস করছিল। রূপা তাদের মাঝ দিয়ে হাঁটতেই নলখাগড়াগুলো তাকে ধাঁধা জিজ্ঞেস করতে শুরু করল।\n\n\"কী দৌড়ায় কিন্তু কখনো হাঁটে না?\" একটা নল ফিসফিস করে বলল। \"পানি,\" রূপা বলল।\n\n\"কার মুখ আছে কিন্তু কখনো হাসে না?\" আরেকটা বলল। \"ঘড়ি,\" রূপা বলল। নলখাগড়াগুলো মাথা নিচু করে তাকে একটা রূপালি পালক দিল। \"সামনের ঝড়ের জন্য,\" তারা ফিসফিস করল।"
        ),
        Chapter(
            "The Mountain of Fog", "কুয়াশার পাহাড়",
            "The Mountain of Fog was so thick with mist that Rupa could not see her own paws. She walked carefully, one step at a time.\n\nThen she heard a small frightened sound — a goat kid, lost in the fog. Rupa rang the owl's little bell, and the clear sound showed them both the way down.\n\nThe goat's mother thanked her with tears in her eyes. Rupa was tired, but her heart felt lighter than the fog.",
            "কুয়াশার পাহাড় এত ঘন কুয়াশায় ঢাকা ছিল যে রূপা নিজের থাবাও দেখতে পাচ্ছিল না। সে সাবধানে হাঁটছিল, এক পা এক পা করে।\n\nতখন সে একটা ছোট ভীত শব্দ শুনল — কুয়াশায় হারিয়ে যাওয়া এক ছাগলের বাচ্চা। রূপা পেঁচার ছোট ঘণ্টাটা বাজাল, আর পরিষ্কার শব্দটা দুজনকেই নিচে নামার পথ দেখিয়ে দিল।\n\nছাগলের মা চোখে জল নিয়ে তাকে ধন্যবাদ দিল। রূপা ক্লান্ত ছিল, কিন্তু তার মন কুয়াশার চেয়েও হালকা লাগছিল।"
        ),
        Chapter(
            "The Star Pool", "তারার পুকুর",
            "At last, beyond the mountain, Rupa found the Star Pool. Stars floated on the water like fireflies, and the whole pool glowed with soft silver light.\n\nRupa took out her small lantern and dipped it gently into the pool. A tiny star swam inside it and settled there, warm and bright.\n\n\"Thank you,\" Rupa whispered to the pool. The stars on the water twinkled, as if they were smiling back at her.",
            "অবশেষে, পাহাড়ের ওপারে, রূপা তারার পুকুর খুঁজে পেল। তারাগুলো জোনাকির মতো পানির উপর ভাসছিল, আর পুরো পুকুরটা নরম রূপালি আলোয় জ্বলজ্বল করছিল।\n\nরূপা তার ছোট লণ্ঠনটা বের করে আস্তে করে পুকুরে ডুবাল। একটা ছোট তারা তার ভেতরে সাঁতরে গিয়ে বসল, উষ্ণ আর উজ্জ্বল।\n\n\"ধন্যবাদ,\" রূপা পুকুরকে ফিসফিস করে বলল। পানির তারাগুলো ঝিকমিক করল, যেন তারা তার দিকে হেসে তাকিয়ে আছে।"
        ),
        Chapter(
            "The Storm", "ঝড়",
            "On the way home, a great storm rose up. Rain hammered down and the wind tried to steal the little star from Rupa's lantern.\n\nRupa remembered the silver feather. She held it over the lantern, and the feather spread into a shining shield that turned the rain aside.\n\nAll night she walked through the storm, holding the lantern close to her heart. The little star never went out — not even once.",
            "বাড়ি ফেরার পথে এক বিরাট ঝড় উঠল। বৃষ্টি ঝরতে লাগল আর বাতাস রূপার লণ্ঠন থেকে ছোট তারাটাকে কেড়ে নিতে চাইল।\n\nরূপার মনে পড়ল রূপালি পালকের কথা। সে পালকটা লণ্ঠনের উপরে ধরল, আর পালকটা ছড়িয়ে এক ঝলমলে ঢালে পরিণত হলো, যা বৃষ্টিকে সরিয়ে দিল।\n\nসারা রাত সে ঝড়ের মধ্যে হাঁটল, লণ্ঠনটা বুকের কাছে ধরে। ছোট তারাটা একবারও নিভল না — একবারও না।"
        ),
        Chapter(
            "The Bridge of Roots", "শিকড়ের সেতু",
            "When Rupa reached the river, she found the old crossing washed away by the storm. The water was too wild for the turtle now.\n\nBut the animals of the forest had come to meet her. The great trees bent their roots across the river, the beavers wove them tight, and the birds tied them with vines.\n\nTogether they built a bridge in a single morning. Rupa crossed it slowly, the lantern held high, and every animal cheered.",
            "রূপা নদীর কাছে পৌঁছে দেখল ঝড়ে পুরনো পারাপারের জায়গাটা ভেসে গেছে। পানি এখন এত উত্তাল যে কচ্ছপও পার হতে পারবে না।\n\nকিন্তু বনের প্রাণীরা তাকে নিতে এসেছিল। বড় গাছগুলো তাদের শিকড় নদীর উপর দিয়ে বাঁকিয়ে দিল, বিভারগুলো সেগুলো শক্ত করে বুনল, আর পাখিরা লতা দিয়ে বেঁধে দিল।\n\nসবাই মিলে এক সকালেই একটা সেতু বানিয়ে ফেলল। রূপা আস্তে আস্তে সেতু পার হলো, লণ্ঠনটা উঁচু করে ধরে, আর সব প্রাণী উল্লাস করল।"
        ),
        Chapter(
            "Home Again", "আবার বাড়ি",
            "Rupa came home to the Quiet Forest as the sun was setting. Every animal was waiting by the great stone.\n\nBut something wonderful had happened while she was away: the animals had not just waited. The fireflies had gathered their light, the glow-mushrooms had been collected, and the children had caught moonlight in little jars.\n\n\"We learned to make our own light,\" said the old owl, \"while we waited for you to bring back the star.\"",
            "সূর্য ডোবার সময় রূপা নীরব বনে বাড়ি ফিরে এলো। সব প্রাণী বড় পাথরটার পাশে অপেক্ষা করছিল।\n\nকিন্তু সে যখন দূরে ছিল, তখন এক অসাধারণ ঘটনা ঘটেছে: প্রাণীরা শুধু অপেক্ষা করেনি। জোনাকিরা তাদের আলো জড়ো করেছে, জ্বলজ্বলে মাশরুম সংগ্রহ করা হয়েছে, আর বাচ্চারা ছোট জারে চাঁদের আলো ধরেছে।\n\n\"তুমি তারা নিয়ে ফেরার অপেক্ষায় আমরাও নিজেরা আলো বানাতে শিখে গেছি,\" বুড়ো পেঁচা বলল।"
        ),
        Chapter(
            "The Lantern Burns Again", "আবার জ্বলে লণ্ঠন",
            "Rupa climbed the tall stone and placed her little star inside the great lantern. The star grew and grew until the whole forest glowed — brighter than ever before.\n\nThen the animals added their own lights around it: fireflies, mushrooms, jars of moonlight. The lantern was no longer one flame. It was everyone, together.\n\nAnd Rupa understood at last: the light was never something to find far away. It was the courage to walk, the patience to wait, and the friends who walk beside you.",
            "রূপা উঁচু পাথরে উঠে তার ছোট তারাটাকে বড় লণ্ঠনের ভেতরে রাখল। তারাটা বড় হতে হতে পুরো বন আলোকিত করে ফেলল — আগের চেয়েও উজ্জ্বল।\n\nতারপর প্রাণীরা তাদের নিজেদের আলো তার চারপাশে যোগ করল: জোনাকি, মাশরুম, চাঁদের আলোর জার। লণ্ঠনটা আর একটা শিখা রইল না। সেটা হয়ে গেল সবাই, একসাথে।\n\nআর রূপা অবশেষে বুঝল: আলো কখনো দূরে খুঁজে পাওয়ার জিনিস ছিল না। আলো ছিল হাঁটার সাহস, অপেক্ষার ধৈর্য, আর পাশে হাঁটা বন্ধুরা।"
        )
    )

    /** Chapters unlocked so far: one per completed lock, max 10.
     * Floored at the already-seen count: the sessions list is capped at 500
     * entries, so without this 491+ consecutive broken locks would silently
     * re-lock chapters. Reads the raw pref — seenCount() calls this on first
     * read, so calling it here would recurse infinitely. */
    fun unlockedCount(ctx: Context): Int {
        val seen = Store.lock(ctx).getInt("story_seen", 0)
        return maxOf(Store.sessions(ctx).count { it.ok }, seen).coerceAtMost(TOTAL)
    }

    /**
     * Chapters the user has already been told about. Backfills silently on
     * first read, so existing users keep what they earned without a flood of
     * "new chapter" messages.
     */
    fun seenCount(ctx: Context): Int {
        val p = Store.lock(ctx)
        if (!p.contains("story_seen")) {
            val cur = unlockedCount(ctx)
            p.edit().putInt("story_seen", cur).apply()
            return cur
        }
        return p.getInt("story_seen", 0).coerceIn(0, TOTAL)
    }

    fun markSeen(ctx: Context, n: Int) {
        Store.lock(ctx).edit().putInt("story_seen", n.coerceIn(0, TOTAL)).apply()
    }

    /** Chapters unlocked since the user was last told (0 when none are new). */
    fun newUnlocks(ctx: Context): Int =
        (unlockedCount(ctx) - seenCount(ctx)).coerceAtLeast(0)

    /** The reader: a chapter list, then the chapter text in a scrollable dialog. */
    fun showReader(a: MainActivity) {
        val unlocked = unlockedCount(a)
        val items = ArrayList<String>()
        for ((i, ch) in CHAPTERS.withIndex()) {
            val title = L.t(ch.titleEn, ch.titleBn)
            items.add(
                if (i < unlocked) L.t("Chapter ${i + 1}", "অধ্যায় ${L.num((i + 1).toString())}") + " — " + title
                else L.t("Chapter ${i + 1}", "অধ্যায় ${L.num((i + 1).toString())}") + " — " + L.t("locked", "তালাবদ্ধ")
            )
        }
        a.dlg()
            .setTitle(L.t("The Lantern of the Quiet Forest", "নীরব বনের লণ্ঠন"))
            .setItems(items.toTypedArray()) { _, which ->
                if (which >= unlocked) {
                    a.toast(L.t("Complete a lock to unlock this chapter", "এই অধ্যায় খুলতে একটা লক সম্পন্ন করুন"))
                    return@setItems
                }
                showChapter(a, which)
            }
            .setNegativeButton(L.t("Close", "বন্ধ"), null)
            .show()
    }

    private fun showChapter(a: MainActivity, idx: Int) {
        val ch = CHAPTERS[idx]
        val sv = ScrollView(a)
        val tv = TextView(a)
        tv.text = L.t(ch.bodyEn, ch.bodyBn)
        tv.setTextColor(C.text)
        tv.textSize = 15f
        tv.setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(12))
        sv.addView(tv)
        a.dlg()
            .setTitle(L.t("Chapter ${idx + 1}", "অধ্যায় ${L.num((idx + 1).toString())}") + " — " + L.t(ch.titleEn, ch.titleBn))
            .setView(sv)
            .setPositiveButton(L.t("Close", "বন্ধ"), null)
            .show()
    }
}

// =====================================================================
// GARDEN ANIMALS
// =====================================================================

data class AnimalDef(val id: String, val nameEn: String, val nameBn: String, val trees: Int, val color: String)

/**
 * Garden animals: they move into the garden as the user grows more trees
 * (alive trees only), and can also be won early from the lucky wheel.
 * Tree-threshold animals are derived from the garden count; wheel-won ones
 * are stored in prefs. The garden shows the union of both.
 */
object GardenAnimals {
    val ALL = listOf(
        AnimalDef("bird", "Bird", "পাখি", 3, "#64B5F6"),
        AnimalDef("butterfly", "Butterfly", "প্রজাপতি", 5, "#BA68C8"),
        AnimalDef("rabbit", "Rabbit", "খরগোশ", 10, "#E8E0D0"),
        AnimalDef("fox", "Fox", "শিয়াল", 20, "#FF9F43")
    )

    fun byId(id: String): AnimalDef? = ALL.firstOrNull { it.id == id }

    /** Alive trees in the garden — the count animals are earned by. */
    fun treeCount(ctx: Context): Int = FocusTree.garden(ctx).count { it.alive }

    private fun wheelWon(ctx: Context): Set<String> {
        val raw = Store.lock(ctx).getString("garden_animals_wheel", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun unlocked(ctx: Context): List<AnimalDef> {
        val won = wheelWon(ctx)
        val trees = treeCount(ctx)
        return ALL.filter { it.id in won || trees >= it.trees }
    }

    fun locked(ctx: Context): List<AnimalDef> {
        val un = unlocked(ctx).map { it.id }.toSet()
        return ALL.filter { it.id !in un }
    }

    /** Unlocks a random not-yet-owned animal (wheel reward). Null when all are owned. */
    fun unlockFromWheel(ctx: Context): AnimalDef? {
        val rest = locked(ctx)
        if (rest.isEmpty()) return null
        val pick = rest[Random.nextInt(rest.size)]
        val won = wheelWon(ctx).toMutableSet()
        won.add(pick.id)
        Store.lock(ctx).edit().putString("garden_animals_wheel", won.joinToString(",")).apply()
        return pick
    }

    /** The next animal to earn by growing trees, or null when all are earned. */
    fun nextByTrees(ctx: Context): AnimalDef? =
        // locked() excludes wheel-won animals too: filtering ALL by trees
        // alone could return an animal the user already owns via the wheel
        locked(ctx).minByOrNull { it.trees }
}

/** Canvas-drawn garden animal (bird, butterfly, rabbit, fox). Simple shapes, no assets. */
class AnimalView(ctx: Context, private val animalId: String) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val col = Color.parseColor(GardenAnimals.byId(animalId)?.color ?: "#64B5F6")
        paint.style = Paint.Style.FILL
        paint.color = col
        when (animalId) {
            "bird" -> {
                // body + head + beak + wing + tail
                canvas.drawOval(cx - w * 0.30f, cy - h * 0.12f, cx + w * 0.30f, cy + h * 0.30f, paint)
                canvas.drawCircle(cx + w * 0.22f, cy - h * 0.22f, w * 0.16f, paint)
                paint.color = Color.parseColor("#FF9F43")
                val beak = Path()
                beak.moveTo(cx + w * 0.36f, cy - h * 0.24f)
                beak.lineTo(cx + w * 0.48f, cy - h * 0.18f)
                beak.lineTo(cx + w * 0.36f, cy - h * 0.12f)
                beak.close()
                canvas.drawPath(beak, paint)
                paint.color = Color.parseColor("#1E88E5")
                canvas.drawOval(cx - w * 0.26f, cy - h * 0.06f, cx + w * 0.06f, cy + h * 0.18f, paint)
                paint.color = col
                val tail = Path()
                tail.moveTo(cx - w * 0.28f, cy + h * 0.08f)
                tail.lineTo(cx - w * 0.44f, cy - h * 0.06f)
                tail.lineTo(cx - w * 0.38f, cy + h * 0.20f)
                tail.close()
                canvas.drawPath(tail, paint)
                paint.color = Color.parseColor("#1A1A1A")
                canvas.drawCircle(cx + w * 0.26f, cy - h * 0.26f, w * 0.03f, paint)
            }
            "butterfly" -> {
                // four wings + body + antennae
                canvas.drawOval(cx - w * 0.42f, cy - h * 0.40f, cx - w * 0.04f, cy - h * 0.02f, paint)
                canvas.drawOval(cx + w * 0.04f, cy - h * 0.40f, cx + w * 0.42f, cy - h * 0.02f, paint)
                paint.color = Color.parseColor("#7B1FA2")
                canvas.drawOval(cx - w * 0.34f, cy + h * 0.04f, cx - w * 0.04f, cy + h * 0.36f, paint)
                canvas.drawOval(cx + w * 0.04f, cy + h * 0.04f, cx + w * 0.34f, cy + h * 0.36f, paint)
                paint.color = Color.parseColor("#2B1A10")
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.05f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(cx, cy - h * 0.28f, cx, cy + h * 0.28f, paint)
                paint.strokeWidth = w * 0.02f
                canvas.drawLine(cx - w * 0.02f, cy - h * 0.28f, cx - w * 0.12f, cy - h * 0.42f, paint)
                canvas.drawLine(cx + w * 0.02f, cy - h * 0.28f, cx + w * 0.12f, cy - h * 0.42f, paint)
            }
            "rabbit" -> {
                // long ears + head + body + tail
                canvas.drawOval(cx - w * 0.20f, cy - h * 0.46f, cx - w * 0.06f, cy - h * 0.08f, paint)
                canvas.drawOval(cx + w * 0.06f, cy - h * 0.46f, cx + w * 0.20f, cy - h * 0.08f, paint)
                paint.color = Color.parseColor("#F8BBD0")
                canvas.drawOval(cx - w * 0.155f, cy - h * 0.40f, cx - w * 0.095f, cy - h * 0.14f, paint)
                canvas.drawOval(cx + w * 0.095f, cy - h * 0.40f, cx + w * 0.155f, cy - h * 0.14f, paint)
                paint.color = col
                canvas.drawCircle(cx, cy - h * 0.06f, w * 0.22f, paint)
                canvas.drawOval(cx - w * 0.26f, cy + h * 0.06f, cx + w * 0.26f, cy + h * 0.42f, paint)
                canvas.drawCircle(cx + w * 0.28f, cy + h * 0.30f, w * 0.08f, paint)
                paint.color = Color.parseColor("#1A1A1A")
                canvas.drawCircle(cx - w * 0.09f, cy - h * 0.10f, w * 0.03f, paint)
                canvas.drawCircle(cx + w * 0.09f, cy - h * 0.10f, w * 0.03f, paint)
            }
            else -> { // fox
                // pointy ears + head + body + big tail
                val earL = Path()
                earL.moveTo(cx - w * 0.20f, cy - h * 0.16f)
                earL.lineTo(cx - w * 0.24f, cy - h * 0.40f)
                earL.lineTo(cx - w * 0.06f, cy - h * 0.26f)
                earL.close()
                canvas.drawPath(earL, paint)
                val earR = Path()
                earR.moveTo(cx + w * 0.20f, cy - h * 0.16f)
                earR.lineTo(cx + w * 0.24f, cy - h * 0.40f)
                earR.lineTo(cx + w * 0.06f, cy - h * 0.26f)
                earR.close()
                canvas.drawPath(earR, paint)
                canvas.drawCircle(cx, cy - h * 0.12f, w * 0.22f, paint)
                canvas.drawOval(cx - w * 0.24f, cy + h * 0.02f, cx + w * 0.24f, cy + h * 0.38f, paint)
                // tail sweeping to the side, white tip
                paint.color = col
                canvas.drawOval(cx + w * 0.10f, cy + h * 0.18f, cx + w * 0.48f, cy + h * 0.44f, paint)
                paint.color = Color.WHITE
                canvas.drawOval(cx + w * 0.36f, cy + h * 0.20f, cx + w * 0.48f, cy + h * 0.42f, paint)
                // white chest + face
                paint.color = Color.WHITE
                canvas.drawOval(cx - w * 0.12f, cy + h * 0.08f, cx + w * 0.12f, cy + h * 0.34f, paint)
                paint.color = Color.parseColor("#1A1A1A")
                canvas.drawCircle(cx - w * 0.09f, cy - h * 0.16f, w * 0.03f, paint)
                canvas.drawCircle(cx + w * 0.09f, cy - h * 0.16f, w * 0.03f, paint)
                canvas.drawCircle(cx, cy - h * 0.04f, w * 0.035f, paint)
            }
        }
    }
}

// =====================================================================
// TREE SPECIES
// =====================================================================

data class SpeciesDef(
    val id: String, val nameEn: String, val nameBn: String,
    /** Null = follow the theme accent (classic). Fixed colours otherwise. */
    val dark: String?, val mid: String?, val light: String?
)

/**
 * Tree species: alternate looks for the focus tree, won from the lucky wheel.
 * "classic" is the default theme-coloured tree and is always unlocked.
 */
object TreeSpecies {
    val ALL = listOf(
        SpeciesDef("classic", "Classic", "সাধারণ", null, null, null),
        SpeciesDef("cherry", "Cherry blossom", "চেরি ফুল", "#C2185B", "#F48FB1", "#FCE4EC"),
        SpeciesDef("palm", "Palm", "তাল গাছ", "#558B2F", "#9CCC65", "#DCEDC8"),
        SpeciesDef("autumn", "Autumn", "শরৎ", "#D84315", "#FF8A65", "#FFCCBC")
    )

    fun byId(id: String): SpeciesDef? = ALL.firstOrNull { it.id == id }

    private fun wheelWon(ctx: Context): Set<String> {
        val raw = Store.lock(ctx).getString("tree_species_wheel", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun unlocked(ctx: Context): List<SpeciesDef> {
        val won = wheelWon(ctx)
        return ALL.filter { it.id == "classic" || it.id in won }
    }

    fun locked(ctx: Context): List<SpeciesDef> {
        val un = unlocked(ctx).map { it.id }.toSet()
        return ALL.filter { it.id !in un }
    }

    /** The species the live tree is drawn with (falls back to classic). */
    fun selected(ctx: Context): String {
        val sel = Store.lock(ctx).getString("tree_species_sel", "classic") ?: "classic"
        return if (unlocked(ctx).any { it.id == sel }) sel else "classic"
    }

    fun select(ctx: Context, id: String) {
        if (unlocked(ctx).any { it.id == id }) {
            Store.lock(ctx).edit().putString("tree_species_sel", id).apply()
        }
    }

    /** Unlocks a random locked species (wheel reward). Null when all are owned. */
    fun unlockFromWheel(ctx: Context): SpeciesDef? {
        val rest = locked(ctx)
        if (rest.isEmpty()) return null
        val pick = rest[Random.nextInt(rest.size)]
        val won = wheelWon(ctx).toMutableSet()
        won.add(pick.id)
        Store.lock(ctx).edit().putString("tree_species_wheel", won.joinToString(",")).apply()
        return pick
    }
}
