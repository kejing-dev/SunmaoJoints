package io.embodit.sunmaojoints.domain

import io.embodit.sunmaojoints.domain.Mat.DARK
import io.embodit.sunmaojoints.domain.Mat.GOLD
import io.embodit.sunmaojoints.domain.Mat.LIGHT
import io.embodit.sunmaojoints.domain.Mat.RED
import io.embodit.sunmaojoints.domain.Prim.Companion.span
import io.embodit.sunmaojoints.domain.Prim.Companion.xy
import io.embodit.sunmaojoints.domain.Prim.Companion.xz
import io.embodit.sunmaojoints.domain.Prim.Companion.zy

data class Text(val zh: String, val en: String) {
    fun of(zhLang: Boolean) = if (zhLang) zh else en
}

/** Where a candidate comes from, in the joint frame: 上 (+y), 右前 (+x), 左前 (+z) (design lessonRules.candidateDirs). */
enum class From(val v: V3, val zh: String, val en: String) {
    UP(V3(0f, 1f, 0f), "上", "above"), RF(V3(1f, 0f, 0f), "右前", "front-right"), LF(V3(0f, 0f, 1f), "左前", "front-left");
    val horizontal: Boolean get() = this != UP
}

enum class Outcome { FITS, BLOCKED, TRAP }

/** How the piece is held before it is pushed (sample.js candidatePose / CAND_OVERRIDE). */
sealed interface Hold {
    /** As it sits assembled. */
    data object Home : Hold
    /** Turned about the vertical axis of the joint (a horizontal candidate from the other front side). */
    data class Yaw(val deg: Float) : Hold
    /** Laid flat: 90° about x through its own centre (夹头榫「牙条平着推」). */
    data object FlatX : Hold
}

class Cand(val piece: String, val from: From, val outcome: Outcome, val sign: Text? = null, val hold: Hold = Hold.Home)

class Step(val cands: List<Cand>) {
    val fit: Cand get() = cands.first { it.outcome == Outcome.FITS }
    val fitIndex: Int get() = cands.indexOfFirst { it.outcome == Outcome.FITS }
}

enum class Base { TABLE, BLOCK }

class Lesson(
    val n: Int,
    val id: String,
    val name: Text,
    val usedIn: Text,
    val why: Text,
    val base: Base,
    val pieces: List<Piece>,
    val steps: List<Step>,
    /** 燕尾榫「拽一下」after it locks (design afterLock, A12). */
    val tug: Text? = null,
) {
    val fixed: List<Piece> get() = pieces.filter { it.fixed }
    val movers: List<Piece> get() = pieces.filter { !it.fixed }
    fun piece(id: String): Piece = pieces.first { it.id == id }
    /** The movers in the order they go in. */
    val order: List<String> get() = steps.map { it.fit.piece }
    val bounds: Aabb get() = pieces.map { it.bounds }.reduce { a, b -> a.union(b) }
    /** The direction each mover comes out (pull-apart = the fit direction reversed in time). */
    fun fitFrom(id: String): From = steps.first { it.fit.piece == id }.fit.from
}

/**
 * The eight joints (design lessons[], v1.3), as pieces of boxes and prisms in centimetres (2.5x the real size; the
 * 1:1 mini is the same meshes at 0.4). Joint frame: +x = 右前, +z = 左前, y = 0 on the turntable or the block. Holes
 * are carved out (the pieces never overlap when assembled, U8); red mortise linings are paint and never collide.
 * The numbers (tenon sizes, pockets, tapers) are the build station's table (lessonRules.authoring); every outcome is
 * proved by the voxel sweep in the unit tests (U1–U3).
 */
object Joints {
    /** A red lining plate on the wall of a hole at [at] on [axis], the hole on the [holeSide] (+1 / -1) of it. */
    private fun lining(axis: Int, at: Float, holeSide: Int, a0: Float, a1: Float, b0: Float, b1: Float): Prim {
        val lo = if (holeSide > 0) at - 0.06f else at - 0.02f
        val hi = if (holeSide > 0) at + 0.02f else at + 0.06f
        return when (axis) {
            0 -> span(lo, hi, a0, a1, b0, b1, RED) // a = y, b = z
            1 -> span(a0, a1, lo, hi, b0, b1, RED) // a = x, b = z
            else -> span(a0, a1, b0, b1, lo, hi, RED) // a = x, b = y
        }
    }

    private fun p(vararg v: Float): List<Pair<Float, Float>> = v.toList().chunked(2) { it[0] to it[1] }

    // ------------------------------------------------------------------ 1 直榫
    private fun zhi(): Lesson {
        val post = Piece("post", "立柱", "Post", true, listOf(
            span(-4f, 4f, 0f, 10.5f, -4f, 4f, DARK), span(-4f, 4f, 15.5f, 26f, -4f, 4f, DARK),
            span(-4f, -1f, 10.5f, 15.5f, -4f, 4f, DARK),
            span(-1f, 4f, 10.5f, 15.5f, -4f, -1.25f, DARK), span(-1f, 4f, 10.5f, 15.5f, 1.25f, 4f, DARK),
            lining(0, -1f, 1, 10.5f, 15.5f, -1.25f, 1.25f), lining(1, 15.5f, -1, -1f, 4f, -1.25f, 1.25f),
            lining(1, 10.5f, 1, -1f, 4f, -1.25f, 1.25f),
        ))
        val rail = Piece("rail", "横枨", "Rail", false, listOf(
            span(4f, 15f, 9f, 17f, -2.5f, 2.5f, LIGHT), span(-1f, 4f, 10.5f, 15.5f, -1.25f, 1.25f, GOLD),
        ))
        return Lesson(1, "zhi", Text("直榫", "Mortise & Tenon"), Text("桌椅的横枨和腿", "Rails and legs of tables and chairs"),
            Text("横枨一头削薄成榫头，柱上凿一个同样大的卯眼。榫头的肩抵住柱面，上下左右都动不了。",
                "The end of the rail is cut down to a tenon, and the post has a mortise the same size. The shoulders sit tight against the post, so nothing can shift."),
            Base.TABLE, listOf(post, rail),
            listOf(Step(listOf(
                Cand("rail", From.RF, Outcome.FITS),
                Cand("rail", From.LF, Outcome.BLOCKED, Text("这一面没有卯眼", "No mortise on this face"), Hold.Yaw(-90f)),
            ))))
    }

    // ------------------------------------------------------------------ 2 燕尾榫 (tails 3 cm deep so the board lies <= 7 cm on its mat)
    /** Socket width (along z) at x: 2.0 at the inner face x = +2, 3.6 at the outer face x = -2 (slope 1:3, exaggerated). */
    private fun sw(x: Float) = 2f + (2f - x) * 0.4f
    private fun sock(zc: Float) = p(2f, zc - sw(2f) / 2, 2f, zc + sw(2f) / 2, -2f, zc + sw(-2f) / 2, -2f, zc - sw(-2f) / 2)

    private fun yanwei(): Lesson {
        val pins = Piece("pins", "立板", "Pin board", true, listOf(
            span(-2f, 2f, 0f, 16f, -8f, 8f, DARK),
            xz(p(-2f, -8f, 2f, -8f, 2f, -3.6f - sw(2f) / 2, -2f, -3.6f - sw(-2f) / 2), 16f, 19f, DARK),
            xz(p(-2f, -3.6f + sw(-2f) / 2, 2f, -3.6f + sw(2f) / 2, 2f, 3.6f - sw(2f) / 2, -2f, 3.6f - sw(-2f) / 2), 16f, 19f, DARK),
            xz(p(-2f, 3.6f + sw(-2f) / 2, 2f, 3.6f + sw(2f) / 2, 2f, 8f, -2f, 8f), 16f, 19f, DARK),
            xz(sock(-3.6f), 15.94f, 16.02f, RED), xz(sock(3.6f), 15.94f, 16.02f, RED),
        ))
        val tails = Piece("tails", "横板", "Tail board", false, listOf(
            span(-2f, 12f, 19f, 23f, -8f, 8f, LIGHT), xz(sock(-3.6f), 16f, 19f, GOLD), xz(sock(3.6f), 16f, 19f, GOLD),
        ))
        return Lesson(2, "yanwei", Text("燕尾榫", "Dovetail"), Text("抽屉、箱子的转角", "Corners of drawers and chests"),
            Text("榫头外宽里窄，像燕子尾巴。只能顺着一个方向落进去，往外拉的时候越拉越紧。",
                "The tails are wider at the tip, like the tail of a swallow. They drop in from one direction only, and pulling outward just wedges them tighter."),
            Base.TABLE, listOf(pins, tails),
            listOf(Step(listOf(
                Cand("tails", From.UP, Outcome.FITS),
                Cand("tails", From.RF, Outcome.BLOCKED, Text("燕尾头比口宽，进不去", "Tails are wider than the gap")),
            ))),
            tug = Text("往外拉，拉不动", "Pull it. It will not come out."))
    }

    // ------------------------------------------------------------------ 3 格角榫
    private fun gejiao(): Lesson {
        val da = Piece("dabian", "大边", "Long rail", true, listOf(
            xz(p(-18f, -4f, 0f, -4f, -8f, 4f, -18f, 4f), 0f, 5f, DARK),
            span(-5.5f, -1.5f, 1.5f, 3.5f, -1f, 2f, GOLD),
        ))
        val mo = Piece("motou", "抹头", "Short rail", false, listOf(
            xz(p(0f, -4f, 0f, 12f, -8f, 12f, -8f, 4f), 0f, 1.5f, LIGHT),
            xz(p(0f, -4f, 0f, 12f, -8f, 12f, -8f, 4f), 3.5f, 5f, LIGHT),
            xz(p(0f, -4f, 0f, 12f, -1.5f, 12f, -1.5f, -2.5f), 1.5f, 3.5f, LIGHT),
            // the mortise is everything the tenon passes through on the way in: open at the mitre, blind at x = -1.5
            xz(p(-1.5f, 2f, -1.5f, 12f, -8f, 12f, -8f, 4f, -6f, 2f), 1.5f, 3.5f, LIGHT),
            xz(p(-1.5f, -2.5f, -1.5f, -1f, -3f, -1f), 1.5f, 3.5f, LIGHT),
            lining(0, -1.5f, -1, 1.5f, 3.5f, -1f, 2f),
        ))
        return Lesson(3, "gejiao", Text("格角榫", "Mitred Corner"), Text("桌面、柜门的边框转角", "Frame corners of tabletops and cabinet doors"),
            Text("两根边各切 45°，拼起来看不到端头。斜面里藏着榫头和卯眼，框子不会散。",
                "Each rail is cut at 45°, so no end grain shows. A tenon hidden inside the mitre keeps the frame from opening."),
            Base.BLOCK, listOf(da, mo),
            listOf(Step(listOf(
                Cand("motou", From.RF, Outcome.FITS),
                Cand("motou", From.LF, Outcome.BLOCKED, Text("斜面先撞上了", "The mitre faces hit first")),
                Cand("motou", From.UP, Outcome.BLOCKED, Text("卯眼在斜面里，不在上面", "The mortise is not on top")),
            ))))
    }

    // ------------------------------------------------------------------ 4 抱肩榫
    private fun baojian(): Lesson {
        val pinX = p(4f, -0.8f, 5.5f, -1.3f, 5.5f, 1.3f, 4f, 0.8f)
        val pinZ = p(-0.8f, 4f, 0.8f, 4f, 1.3f, 5.5f, -1.3f, 5.5f)
        val leg = Piece("leg", "腿", "Leg", true, listOf(
            span(-4f, 4f, 0f, 26f, -4f, 4f, DARK), xz(pinX, 16f, 24f, GOLD), xz(pinZ, 16f, 24f, GOLD),
        ))
        val a = Piece("apronA", "牙条甲", "Apron A", false, listOf(
            span(5.5f, 8f, 16f, 24f, -4f, 10f, LIGHT),
            xz(p(4f, -4f, 5.5f, -4f, 5.5f, -1.3f, 4f, -0.8f), 16f, 24f, LIGHT),
            xz(p(4f, 0.8f, 5.5f, 1.3f, 5.5f, 10f, 4f, 10f), 16f, 24f, LIGHT),
            lining(0, 5.5f, -1, 16f, 24f, -1.3f, 1.3f),
        ))
        val b = Piece("apronB", "牙条乙", "Apron B", false, listOf(
            span(-10f, 4f, 16f, 24f, 5.5f, 8f, LIGHT),
            xz(p(-10f, 4f, -0.8f, 4f, -1.3f, 5.5f, -10f, 5.5f), 16f, 24f, LIGHT),
            xz(p(0.8f, 4f, 4f, 4f, 4f, 5.5f, 1.3f, 5.5f), 16f, 24f, LIGHT),
            lining(2, 5.5f, -1, -1.3f, 1.3f, 16f, 24f),
        ))
        val sign = Text("挂销挡着，横着进不去", "The hanging pin is in the way")
        return Lesson(4, "baojian", Text("抱肩榫", "Embracing Shoulder"), Text("有束腰的桌子，腿和牙条相接处", "Where leg meets apron on waisted tables"),
            Text("腿上留一条竖着的挂销，牙条背面开槽，从上往下挂住。两边的斜肩抱住腿，桌面一压就更稳。",
                "The leg keeps a vertical hanging pin; the apron has a slot in its back and hangs on from above. Mitred shoulders hug the leg, and the weight of the top tightens it."),
            Base.TABLE, listOf(leg, a, b),
            listOf(
                Step(listOf(Cand("apronA", From.UP, Outcome.FITS), Cand("apronA", From.RF, Outcome.BLOCKED, sign))),
                Step(listOf(Cand("apronB", From.UP, Outcome.FITS), Cand("apronB", From.LF, Outcome.BLOCKED, sign))),
            ))
    }

    // ------------------------------------------------------------------ 5 楔钉榫
    /** Half width of the wedge pin (and its hole) at z: 0.9 at the small end z = -4.5, 1.25 at z = +4.5. */
    private fun pw(z: Float) = 0.9f + (z + 4.5f) / 9f * 0.35f

    private fun xieding(): Lesson {
        val holeL = p(-5.5f, -3f, -pw(-3f), -3f, -pw(3f), 3f, -5.5f, 3f)
        val holeR = p(pw(-3f), -3f, 5.5f, -3f, 5.5f, 3f, pw(3f), 3f)
        val a = Piece("halfA", "下半段", "Lower half", true, listOf(
            span(-12.5f, -7f, 0f, 6f, -3f, 3f, DARK),
            span(-7f, -5.5f, 0f, 3.5f, -3f, 3f, DARK), span(-7f, -5.5f, 5f, 6f, -3f, 3f, DARK),
            span(-7f, -5.5f, 3.5f, 5f, -3f, -1.5f, DARK), span(-7f, -5.5f, 3.5f, 5f, 1.5f, 3f, DARK),
            span(-5.5f, 5.5f, 0f, 1.75f, -3f, 3f, DARK), xz(holeL, 1.75f, 3f, DARK), xz(holeR, 1.75f, 3f, DARK),
            span(5.5f, 7f, 1f, 2.5f, -1.5f, 1.5f, GOLD),
            lining(0, -7f, 1, 3.5f, 5f, -1.5f, 1.5f),
        ))
        val b = Piece("halfB", "上半段", "Upper half", false, listOf(
            span(7f, 12.5f, 0f, 6f, -3f, 3f, LIGHT),
            span(5.5f, 7f, 0f, 1f, -3f, 3f, LIGHT), span(5.5f, 7f, 2.5f, 6f, -3f, 3f, LIGHT),
            span(5.5f, 7f, 1f, 2.5f, -3f, -1.5f, LIGHT), span(5.5f, 7f, 1f, 2.5f, 1.5f, 3f, LIGHT),
            span(-5.5f, 5.5f, 4.25f, 6f, -3f, 3f, LIGHT), xz(holeL, 3f, 4.25f, LIGHT), xz(holeR, 3f, 4.25f, LIGHT),
            span(-7f, -5.5f, 3.5f, 5f, -1.5f, 1.5f, GOLD),
            lining(0, 7f, -1, 1f, 2.5f, -1.5f, 1.5f),
        ))
        val pin = Piece("pin", "楔钉", "Wedge pin", false, listOf(
            xz(p(-1.25f, 4.5f, 1.25f, 4.5f, 0.9f, -4.5f, -0.9f, -4.5f), 1.75f, 4.25f, LIGHT),
        ))
        return Lesson(5, "xieding", Text("楔钉榫", "Wedge-pinned Scarf"), Text("圈椅的弧形扶手，一段一段接起来", "Joining the curved arm of a round-backed chair"),
            Text("两段各切掉一半，顺着长度对插，头上的小舌头咬进对面的槽，上下左右都分不开。中间再打进一根楔钉，顺着拉也拉不开。",
                "Two halves slide together end to end. Small tongues at each tip slip into grooves, so the halves cannot lift or shift. A wedge pin through the middle stops them sliding back apart."),
            Base.BLOCK, listOf(a, b, pin),
            listOf(
                Step(listOf(
                    Cand("halfB", From.RF, Outcome.FITS),
                    Cand("halfB", From.UP, Outcome.BLOCKED, Text("小舌头挡着，压不下去", "Tongues block it from above")),
                    Cand("pin", From.LF, Outcome.TRAP, Text("楔钉先进，另一半推不进", "Pin first? The half is blocked")),
                )),
                Step(listOf(
                    Cand("pin", From.LF, Outcome.FITS),
                    Cand("pin", From.UP, Outcome.BLOCKED, Text("销眼是横着开的", "The pin hole runs sideways")),
                )),
            ))
    }

    // ------------------------------------------------------------------ 6 夹头榫
    private fun jiatou(): Lesson {
        val leg = Piece("leg", "腿", "Leg", true, listOf(
            span(-4f, 4f, 0f, 15f, -4f, 4f, DARK),
            span(-4f, 4f, 15f, 22f, -4f, -1.25f, DARK), span(-4f, 4f, 15f, 22f, 1.25f, 4f, DARK),
            span(-1.25f, 1.25f, 22f, 25f, -3.625f, -1.625f, GOLD), span(-1.25f, 1.25f, 22f, 25f, 1.625f, 3.625f, GOLD),
            lining(1, 15f, 1, -4f, 4f, -1.25f, 1.25f),
        ))
        val apron = Piece("apron", "牙条", "Apron", false, listOf(span(-9f, 9f, 15f, 22f, -1.25f, 1.25f, LIGHT)))
        val top = Piece("top", "案面", "Top", false, listOf(
            span(-10f, 10f, 25f, 26f, -7f, 7f, LIGHT),
            span(-10f, -1.25f, 22f, 25f, -7f, 7f, LIGHT), span(1.25f, 10f, 22f, 25f, -7f, 7f, LIGHT),
            span(-1.25f, 1.25f, 22f, 25f, -7f, -3.625f, LIGHT), span(-1.25f, 1.25f, 22f, 25f, -1.625f, 1.625f, LIGHT),
            span(-1.25f, 1.25f, 22f, 25f, 3.625f, 7f, LIGHT),
            lining(1, 25f, -1, -1.25f, 1.25f, -3.625f, -1.625f), lining(1, 25f, -1, -1.25f, 1.25f, 1.625f, 3.625f),
        ))
        return Lesson(6, "jiatou", Text("夹头榫", "Clamp-head Joint"), Text("条案、画案的腿和案面", "Legs and tops of long recessed-leg tables"),
            Text("腿顶开一道口，把牙条夹在里面；口上面的榫头再插进案面。一条腿同时管住牙条和案面。",
                "The top of the leg is slotted to clamp the apron, and the tenons above the slot go into the tabletop. One leg holds both."),
            Base.TABLE, listOf(leg, apron, top),
            listOf(
                Step(listOf(
                    Cand("apron", From.UP, Outcome.FITS),
                    Cand("apron", From.RF, Outcome.BLOCKED, Text("夹口是竖的，平着进不去", "The slot is upright, not flat"), Hold.FlatX),
                )),
                Step(listOf(
                    Cand("top", From.UP, Outcome.FITS),
                    Cand("top", From.LF, Outcome.BLOCKED, Text("榫头朝上，横着套不上", "Tenons point up, not sideways")),
                )),
            ))
    }

    // ------------------------------------------------------------------ 7 粽角榫
    private fun zongjiao(): Lesson {
        val tongue = p(-1.5f, -1.5f, 0.5f, 0.5f, 0.5f, 2f, -1.5f, 0f)
        val fa = p(-3.5f, -3.5f, 16f, -3.5f, 16f, 3.5f, 3.5f, 3.5f)
        val fb = p(-3.5f, -3.5f, 3.5f, 3.5f, 3.5f, 16f, -3.5f, 16f)
        val leg = Piece("leg", "腿", "Leg", true, listOf(
            span(-3.5f, 3.5f, 0f, 12f, -3.5f, 3.5f, DARK), span(0.5f, 2.5f, 12f, 15f, -2.5f, -0.5f, GOLD),
        ))
        val a = Piece("railA", "横材甲", "Rail A", false, listOf(
            xz(fa, 15f, 19f, LIGHT),
            xz(p(2.5f, -3.5f, 16f, -3.5f, 16f, 3.5f, 3.5f, 3.5f, 2.5f, 2.5f), 12f, 15f, LIGHT),
            xz(p(0.5f, -3.5f, 2.5f, -3.5f, 2.5f, -2.5f, 0.5f, -2.5f), 12f, 15f, LIGHT),
            xz(p(0.5f, -0.5f, 2.5f, -0.5f, 2.5f, 2.5f, 0.5f, 0.5f), 12f, 15f, LIGHT),
            xz(p(-3.5f, -3.5f, 0.5f, -3.5f, 0.5f, 0.5f), 12f, 15f, LIGHT),
            xz(tongue, 15f, 17f, GOLD),
            lining(1, 15f, -1, 0.5f, 2.5f, -2.5f, -0.5f),
        ))
        val b = Piece("railB", "横材乙", "Rail B", false, listOf(
            xz(fb, 12f, 15f, LIGHT), xz(fb, 17f, 19f, LIGHT),
            xz(p(-3.5f, -3.5f, -1.5f, -1.5f, -1.5f, 0f, 0.5f, 2f, 0.5f, 0.5f, 3.5f, 3.5f, 3.5f, 16f, -3.5f, 16f), 15f, 17f, LIGHT),
            xz(tongue, 16.98f, 17.06f, RED),
        ))
        return Lesson(7, "zongjiao", Text("粽角榫", "Three-way Mitre"), Text("柜子、书架的顶角，三根方材在一点相交", "Top corners of cabinets and shelves"),
            Text("三根方材在一个角上相交，三个面都是 45° 斜线，像粽子的角。里面的榫头朝着不同方向，互相锁住，所以要按顺序装。",
                "Three square members meet at one corner, with a 45° line on every face, like the corner of a zongzi. Hidden tenons point different ways and lock each other, so order matters."),
            Base.TABLE, listOf(leg, a, b),
            listOf(
                Step(listOf(
                    Cand("railA", From.UP, Outcome.FITS),
                    Cand("railA", From.RF, Outcome.BLOCKED, Text("腿顶的榫头朝上", "The tenon on the leg points up")),
                    Cand("railB", From.LF, Outcome.TRAP, Text("乙先装，甲就落不下来", "B first? Then A cannot drop in")),
                )),
                Step(listOf(
                    Cand("railB", From.LF, Outcome.FITS),
                    Cand("railB", From.UP, Outcome.BLOCKED, Text("甲的小榫压在上面", "The tongue of A is in the way")),
                )),
            ))
    }

    // ------------------------------------------------------------------ 8 龙凤榫加穿带
    /** Half width of the batten (and its housing) at height y: 1.5 at the small end y = 1, 2.0 at y = 17. */
    private fun bw(y: Float) = 1.5f + (y - 1f) / 16f * 0.5f

    /** The housing layers (z 1.4–1.7 flange slot, z 1.7–2.0 stem slot) for a board band y0..y1. */
    private fun housing(y0: Float, y1: Float, mat: Mat): List<Prim> {
        val out = ArrayList<Prim>()
        for ((z0, z1, extra) in listOf(Triple(1.4f, 1.7f, 0.4f), Triple(1.7f, 2.0f, 0f))) {
            out += xy(p(-8f, y0, -bw(y0) - extra, y0, -bw(y1) - extra, y1, -8f, y1), z0, z1, mat)
            out += xy(p(bw(y0) + extra, y0, 8f, y0, 8f, y1, bw(y1) + extra, y1), z0, z1, mat)
        }
        return out
    }

    private fun longfeng(): Lesson {
        val tongue = p(-0.6f, 9f, 0.6f, 9f, 0.6f, 9.5f, 1.1f, 9.8f, 1.1f, 10.6f, -1.1f, 10.6f, -1.1f, 9.8f, -0.6f, 9.5f)
        val a = Piece("boardA", "下板", "Lower board", true, listOf(
            span(-8f, 8f, 0f, 9f, -2f, 1.4f, DARK), span(-8f, 8f, 0f, 1f, 1.4f, 2f, DARK),
        ) + housing(1f, 9f, DARK) + listOf(
            zy(tongue, -8f, 8f, GOLD),
            xy(p(-bw(1f) - 0.4f, 1f, bw(1f) + 0.4f, 1f, bw(9f) + 0.4f, 9f, -bw(9f) - 0.4f, 9f), 1.34f, 1.42f, RED),
        ))
        val b = Piece("boardB", "上板", "Upper board", false, listOf(
            span(-8f, 8f, 10.6f, 18f, -2f, 1.4f, LIGHT),
            zy(p(-2f, 9f, -0.6f, 9f, -0.6f, 9.5f, -1.1f, 9.8f, -1.1f, 10.6f, -2f, 10.6f), -8f, 8f, LIGHT),
            zy(p(0.6f, 9f, 1.4f, 9f, 1.4f, 10.6f, 1.1f, 10.6f, 1.1f, 9.8f, 0.6f, 9.5f), -8f, 8f, LIGHT),
        ) + housing(9f, 10.6f, LIGHT) + housing(10.6f, 18f, LIGHT) + listOf(
            span(-8f, 8f, 10.58f, 10.66f, -1.1f, 1.1f, RED),
            xy(p(-bw(9f) - 0.4f, 9f, bw(9f) + 0.4f, 9f, bw(18f) + 0.4f, 18f, -bw(18f) - 0.4f, 18f), 1.34f, 1.42f, RED),
        ))
        val batten = Piece("batten", "穿带", "Batten", false, listOf(
            xy(p(-bw(1f) - 0.4f, 1f, bw(1f) + 0.4f, 1f, bw(17f) + 0.4f, 17f, -bw(17f) - 0.4f, 17f), 1.4f, 1.7f, LIGHT),
            xy(p(-bw(1f), 1f, bw(1f), 1f, bw(17f), 17f, -bw(17f), 17f), 1.7f, 3.9f, LIGHT),
        ))
        return Lesson(8, "longfeng", Text("龙凤榫加穿带", "Tongue & Groove + Batten"), Text("桌面、柜门的大板，几块窄板拼成一块", "Wide panels made from narrow boards"),
            Text("板边一条头大颈细的榫舌，另一块开同样的槽，只能顺着长度推进去，拼缝拉不开。背面再穿一根一头宽一头窄的带，板就不会翘。",
                "One board has a tongue along its edge that is wider at the tip; the other has a matching groove, so it only slides in lengthwise and the seam cannot open. A tapered batten across the back keeps the panel flat."),
            Base.TABLE, listOf(a, b, batten),
            listOf(
                Step(listOf(
                    Cand("boardB", From.RF, Outcome.FITS),
                    Cand("boardB", From.UP, Outcome.BLOCKED, Text("榫舌头大颈细，压不进", "Tongue too wide to press in")),
                )),
                Step(listOf(
                    Cand("batten", From.UP, Outcome.FITS),
                    Cand("batten", From.LF, Outcome.BLOCKED, Text("槽口比带窄，按不进", "The slot mouth is too narrow")),
                )),
            ))
    }

    val ALL: List<Lesson> = listOf(zhi(), yanwei(), gejiao(), baojian(), xieding(), jiatou(), zongjiao(), longfeng())

    /** The common words (design lessonRules.common). */
    val OK = Text("对了", "That is it")
    val LOCKED = Text("扣住了", "Locked")
    val ASK = Text("哪个方向推得进去？", "Which way does it go in?")
    val ALL_DONE = Text("八种都会了", "All eight, done")
    val DISCLAIMER = Text("示意模型：为了看清原理做了简化和放大，不是下料图。", "Teaching models: simplified and enlarged to show the principle. Not cutting plans.")

}
