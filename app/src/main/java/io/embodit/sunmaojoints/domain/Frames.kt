package io.embodit.sunmaojoints.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * What the volume shows at time [now] (pure: BoardScene only applies it; the unit tests walk every step x stop).
 * Poses are in the volume frame, metres.
 */
object Frames {
    data class PieceView(val id: String, val pose: Pose)
    data class GhostView(val cand: Int, val piece: String, val pose: Pose, val scale: Float, val alpha: Float)
    data class ArrowView(val cand: Int, val spot: Layout.ArrowSpot, val rot: Q, val scale: Float, val dim: Boolean, val glow: Boolean)
    /** The orange contact flash: a thin box (centre, size) in the joint frame turned with the turntable. */
    data class FlashView(val pose: Pose, val size: V3)

    data class Frame(
        val yaw: Q,
        val pieces: List<PieceView>,
        val ghosts: List<GhostView>,
        val arrows: List<ArrowView>,
        val flash: FlashView?,
        /** Wood opacity (1 = solid; 0.35 at the height of 透木). */
        val wood: Float,
        val plaque: Text?,
        val block: Pose?,
        val mini: Boolean,
        val minisAll: Boolean,
        /** Arrows / turntable take taps now. */
        val live: Boolean,
    )

    fun yawStops(st: PlayState, now: Long): Float {
        var y = st.yaw.toFloat()
        st.yawFrom?.let { f ->
            val u = ((now - st.yawAt).toFloat() / st.yawMs).coerceIn(0f, 1f)
            y = f + (st.yaw - f) * Ease.inOut(u)
        }
        if ((st.phase == Phase.DONE || st.phase == Phase.ALL_DONE) && st.doneAt > 1) {
            val u = ((now - spinStart(st)).toFloat() / T.SPIN).coerceIn(0f, 1f)
            y += 4f * Ease.inOut(u)
        }
        return y
    }

    private fun xrayStart(st: PlayState) = st.doneAt + if (st.tug) T.TUG else 0
    private fun spinStart(st: PlayState) = xrayStart(st) + T.XRAY

    fun yawQ(st: PlayState, now: Long): Q = Layout.yawQ(yawStops(st, now))

    private fun lerp(a: Pose, b: Pose, u: Float, lift: Float = 0f): Pose =
        Pose(a.pos.lerp(b.pos, u) + V3(0f, lift * sin(PI.toFloat() * u), 0f), a.rot.slerp(b.rot, u))

    /** The pushed piece now (design A3–A6). */
    fun pushPose(l: Lesson, p: PushAnim, now: Long, yaw: Q): Pose {
        val c = l.steps[p.step].cands[p.cand]
        val mat = Layout.matPose(l, p.piece)
        val tt = now - p.at
        if (tt < 0) return mat
        val entry = Layout.candPose(l, c, p.entry, yaw)
        fun at(s: Float) = Layout.candPose(l, c, s, yaw)
        if (tt < T.FLY) return lerp(mat, entry, Ease.outCubic(tt.toFloat() / T.FLY), 0.03f)
        val t1 = tt - T.FLY
        return when (p.outcome) {
            Outcome.FITS -> when {
                t1 < T.PUSH -> at(p.entry * (1f - Ease.outQuart(t1.toFloat() / T.PUSH)))
                t1 < T.PUSH + T.REBOUND -> at(-0.3f * sin(PI.toFloat() * (t1 - T.PUSH) / T.REBOUND))
                else -> at(0f)
            }
            Outcome.TRAP -> {
                val hold = T.PUSH + T.BOUNCE + T.TRAP_STAY
                when {
                    t1 < T.PUSH -> at(p.entry * (1f - Ease.outQuart(t1.toFloat() / T.PUSH)))
                    t1 < hold -> at(0f)
                    t1 < hold + T.TRAP_OUT -> at(p.entry * Ease.inOut((t1 - hold).toFloat() / T.TRAP_OUT))
                    t1 < hold + T.TRAP_OUT + T.TRAP_FLY -> lerp(entry, mat, Ease.inOut((t1 - hold - T.TRAP_OUT).toFloat() / T.TRAP_FLY), 0.03f)
                    else -> mat
                }
            }
            Outcome.BLOCKED -> {
                val w = p.walkMs
                when {
                    t1 < w -> at(p.entry - (p.entry - p.stop) * (t1.toFloat() / w))
                    t1 < w + T.SHAKE -> { val v = (t1 - w).toFloat() / T.SHAKE; at(p.stop + 0.4f * sin(4f * PI.toFloat() * v) * (1f - v)) }
                    t1 < w + T.SHAKE + T.STAY -> at(p.stop)
                    t1 < w + T.SHAKE + T.STAY + T.RETURN -> lerp(at(p.stop), mat, Ease.inQuad((t1 - w - T.SHAKE - T.STAY).toFloat() / T.RETURN), 0.03f)
                    else -> mat
                }
            }
        }
    }

    private fun flashOn(sinceMs: Long): Boolean = sinceMs in 0 until 120 || sinceMs in 220 until 340

    private fun flashView(l: Lesson, box: Aabb, axis: Int, yaw: Q): FlashView {
        // wider than the contact so it shows around the piece that covers it (design E10: 贴在真正撞上的那一对面上)
        val pad = V3.axis(axis, 0.2f) + V3(1.8f, 1.8f, 1.8f) - V3.axis(axis, 1.8f)
        var size = box.size + pad * 2f
        size = V3(max(size.x, 0.4f), max(size.y, 0.4f), max(size.z, 0.4f))
        return FlashView(Layout.joint(l, yaw) * Pose(box.centre * Layout.CM), size * Layout.CM)
    }

    fun frame(st: PlayState, now: Long): Frame {
        val l = st.lessonData
        val yaw = yawQ(st, now)
        val joint = Layout.joint(l, yaw)
        val home = { id: String -> joint }
        val pieces = ArrayList<PieceView>()
        val ghosts = ArrayList<GhostView>()
        val arrows = ArrayList<ArrowView>()
        var flash: FlashView? = null
        var wood = 1f
        var plaque: Text? = l.name
        var live = false
        val block = if (l.base == Base.BLOCK) Layout.joint(l, yaw) * Pose(V3(0f, -Layout.BLOCK_H / 2, 0f)) else null

        when (st.phase) {
            Phase.LOADING -> plaque = null
            Phase.CLOSED, Phase.OPEN -> {
                val order = l.order.reversed() // pull apart in reverse order of assembly
                for (pc in l.pieces) {
                    if (pc.fixed) { pieces += PieceView(pc.id, home(pc.id)); continue }
                    val idx = order.indexOf(pc.id)
                    val k = if (st.phase == Phase.OPEN) {
                        Ease.inOut(((now - st.openAt - idx * T.OPEN_STAGGER).toFloat() / T.OPEN_EACH).coerceIn(0f, 1f))
                    } else if (st.closeAt > 0) {
                        val back = (order.size - 1 - idx)
                        1f - Ease.inOut(((now - st.closeAt - back * T.OPEN_STAGGER).toFloat() / T.OPEN_EACH).coerceIn(0f, 1f))
                    } else 0f
                    pieces += PieceView(pc.id, Layout.apartPose(l, pc.id, k, yaw))
                }
                live = !st.settingsOpen
            }
            Phase.PICK, Phase.PUSH, Phase.BLOCKED, Phase.TRAP -> {
                val step = st.stepData!!
                val installed = st.installed()
                val p = st.push
                for (pc in l.pieces) {
                    val pose = when {
                        pc.fixed || pc.id in installed -> home(pc.id)
                        p != null && p.piece == pc.id && now >= p.at -> pushPose(l, p, now, yaw)
                        st.ret != null && st.ret.piece == pc.id && now < st.ret.at + T.QUICK_BACK ->
                            lerp(st.ret.from, Layout.matPose(l, pc.id), Ease.outCubic((now - st.ret.at).toFloat() / T.QUICK_BACK))
                        else -> Layout.matPose(l, pc.id)
                    }
                    pieces += PieceView(pc.id, pose)
                }
                val locked = p != null && now < p.lockUntil
                live = !locked && !st.settingsOpen
                val spots = Layout.arrowSpots(l, st.lesson, st.step, yaw)
                for ((i, c) in step.cands.withIndex()) {
                    val row = JointTable.row(st.lesson, st.step, i)
                    val entry = Layout.entryCm(row, c)
                    val appear = Ease.outBack(((now - st.stepAt - i * T.ARROW_STAGGER).toFloat() / T.ARROW_IN).coerceIn(0f, 1f))
                    val pushed = p != null && p.cand == i
                    if (!pushed) ghosts += GhostView(i, c.piece, Layout.candPose(l, c, entry, yaw), appear, if (p != null) 0.6f else 1f)
                    val spot = spots[i]
                    val glow = st.hint && i == step.fitIndex
                    val pulse = if (glow) 1f + 0.075f * (1f - kotlin.math.cos(2 * PI.toFloat() * (now % 1000) / 1000f)) else 1f
                    arrows += ArrowView(i, spot, Layout.arrowRot(spot), appear * pulse, dim = i in st.tried || locked, glow = glow)
                }
                plaque = if (now - st.okAt < T.OK_SHOW && st.okAt > 0) Joints.OK else Joints.ASK
                if (p != null) {
                    val c = step.cands[p.cand]
                    val row = JointTable.row(st.lesson, st.step, p.cand)
                    if (c.outcome == Outcome.BLOCKED && now >= p.stopAt) {
                        plaque = c.sign
                        if (flashOn(now - p.stopAt) && row.contact != null) flash = flashView(l, row.contact, dirAxis(c), yaw)
                    }
                    if (c.outcome == Outcome.TRAP && now >= p.stopAt) {
                        plaque = c.sign
                        // the next piece tries to come in and bounces off (A6)
                        val fit = step.fit
                        val fitRow = JointTable.row(st.lesson, st.step, step.fitIndex)
                        val tb = now - p.stopAt
                        if (tb < T.BOUNCE) {
                            val k = if (tb < T.BOUNCE / 2) tb * 2f / T.BOUNCE else 2f - tb * 2f / T.BOUNCE
                            val s = Layout.ENTER_CM - (Layout.ENTER_CM - p.bounce) * k
                            ghosts.removeAll { it.cand == step.fitIndex }
                            ghosts += GhostView(step.fitIndex, fit.piece, Layout.candPose(l, fit, s, yaw), 1f, 1f)
                            if (flashOn(tb - T.BOUNCE / 2) && row.bounceContact != null) flash = flashView(l, row.bounceContact, dirAxis(fit), yaw)
                        }
                        if (fitRow.stopCm != 0f) plaque = c.sign
                    }
                }
            }
            Phase.DONE, Phase.ALL_DONE -> {
                val t = now - st.doneAt
                for (pc in l.pieces) {
                    var pose = home(pc.id)
                    if (st.tug && pc.id == l.order.last() && t in 0 until T.TUG) {
                        val u = t.toFloat() / T.TUG
                        val k = if (u < 0.55f) Ease.outCubic(u / 0.55f) else 1f - Ease.outBack((u - 0.55f) / 0.45f)
                        pose = joint * Pose(V3(k * 1f * Layout.CM, 0f, 0f))
                    }
                    pieces += PieceView(pc.id, pose)
                }
                val x = now - xrayStart(st)
                wood = when {
                    st.doneAt <= 1 || x < 0 -> 1f
                    x < T.XRAY_IN -> 1f - 0.65f * x / T.XRAY_IN
                    x < T.XRAY_IN + T.XRAY_HOLD -> 0.35f
                    x < T.XRAY -> 0.35f + 0.65f * (x - T.XRAY_IN - T.XRAY_HOLD) / T.XRAY_OUT
                    else -> 1f
                }
                plaque = when {
                    st.doneAt > 1 && st.tug && t < T.TUG -> l.tug
                    st.doneAt > 1 && x < T.XRAY -> Joints.LOCKED
                    st.phase == Phase.ALL_DONE -> Joints.ALL_DONE
                    else -> l.name
                }
                live = !st.settingsOpen
            }
        }
        return Frame(yaw, pieces, ghosts, arrows, flash, wood, plaque, block,
            mini = st.phase != Phase.LOADING && st.phase != Phase.ALL_DONE, minisAll = st.phase == Phase.ALL_DONE, live = live)
    }

    private fun dirAxis(c: Cand): Int = when (c.from) { From.UP -> 1; From.RF -> 0; From.LF -> 2 }.let { a ->
        // a turned piece still moves along its way in; the flash plate lies across that axis
        a
    }

    /** All volume-frame boxes the frame occupies (U4: inside the inner box). Ghost and piece boxes from their bounds. */
    fun boxes(st: PlayState, f: Frame): List<Pair<String, Aabb>> {
        val l = st.lessonData
        val out = ArrayList<Pair<String, Aabb>>()
        fun cmBox(id: String) = l.piece(id).bounds.let { Aabb(it.lo * Layout.CM, it.hi * Layout.CM) }
        f.pieces.forEach { out += "piece ${it.id}" to cmBox(it.id).transformed(it.pose) }
        f.ghosts.forEach { out += "ghost ${it.cand}" to cmBox(it.piece).transformed(it.pose) }
        f.arrows.forEach { a -> val r = Layout.ARROW_HIT_D / 2; out += "arrow ${a.cand}" to Aabb(a.spot.pos - V3(r, r, r), a.spot.pos + V3(r, r, r)) }
        return out
    }

    @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
