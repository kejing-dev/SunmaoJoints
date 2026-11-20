package io.embodit.sunmaojoints.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Every length of the volume in metres (design container.volume.math / spatialLayout / occlusionCheck). Origin at the
 * volume centre, +x right, +y up, +z toward the user. Joint geometry is in centimetres (2.5x real); [CM] turns it into
 * metres. Pure, unit-tested (U4 volume box, U5 arrows, D17 / brief item 4 angles).
 */
object Layout {
    const val CM = 0.01f
    const val VOL_W = 0.68f
    const val VOL_H = 0.70f
    const val VOL_D = 0.70f
    /** Everything stays in the volume shrunk by 2 cm (design: 往里收 2 cm 的盒子). */
    val INNER = Aabb(V3(-0.32f, -0.35f, -0.33f), V3(0.32f, 0.33f, 0.33f))

    const val FLOOR_Y = -0.35f
    const val TABLE_Y = -0.25f
    const val TABLE_R = 0.20f
    const val BLOCK_H = 0.04f
    val TURNTABLE_C = V3(0f, -0.30f, 0f)

    /** Entry travel (design v1.3: 10 cm); a candidate that stops further out starts 3 cm before its stop. */
    const val ENTER_CM = 10f
    /** Pull-apart travel (S03). */
    const val EXIT_CM = 12f

    /** Turntable stops: 45° / 135° / 225° / 315° (three faces showing). Stop 0 puts +x front-right, +z front-left. */
    fun yawDeg(stop: Int): Float = -45f + 90f * Math.floorMod(stop, 4)
    fun yawQ(stopF: Float): Q = Q.yawDeg(-45f + 90f * stopF)

    fun baseY(l: Lesson) = TABLE_Y + if (l.base == Base.BLOCK) BLOCK_H else 0f

    /** Joint frame (cm) -> volume (m) at turntable yaw [yaw]. */
    fun joint(l: Lesson, yaw: Q): Pose = Pose(V3(0f, baseY(l), 0f), yaw)

    /** [p] given in cm (piece / candidate pose in the joint frame) as metres. */
    fun m(p: Pose) = Pose(p.pos * CM, p.rot)

    fun entryCm(row: JointTable.Row, c: Cand): Float = if (c.outcome == Outcome.BLOCKED) max(ENTER_CM, row.stopCm + 3f) else ENTER_CM

    /** A candidate's piece at push distance [s] cm, in the volume. */
    fun candPose(l: Lesson, c: Cand, s: Float, yaw: Q): Pose = joint(l, yaw) * m(Motion.at(l, c, s))

    /** The pulled-apart pose of a mover (S03): out along its own way in, by [k] x 12 cm. */
    fun apartPose(l: Lesson, id: String, k: Float, yaw: Q): Pose = joint(l, yaw) * m(Pose(l.fitFrom(id).v * (EXIT_CM * k)))

    // ---------------------------------------------------------------- mats (design spatialLayout: 散件垫板)
    val MAT_C = listOf(V3(-0.22f, -0.345f, 0.255f), V3(0.22f, -0.345f, 0.255f))
    val MAT_SIZE = V3(0.20f, 0.01f, 0.15f)
    const val MAT_TOP = -0.34f

    /** The mat a mover lies on: the first mover left, the second right. */
    fun matOf(l: Lesson, id: String): Int = l.movers.indexOfFirst { it.id == id }.coerceAtLeast(0) % 2

    /** Lying flat on its mat: the thinnest side up, the long side along x, centred, resting on the mat (cm -> m). */
    fun matPose(l: Lesson, id: String): Pose {
        val piece = l.piece(id)
        val s = piece.bounds.size
        // lay the thinnest axis vertical
        var q = when {
            s.y <= s.x && s.y <= s.z -> Q.I
            s.x <= s.z -> Q.axisAngle(V3(0f, 0f, 1f), (Math.PI / 2).toFloat())
            else -> Q.axisAngle(V3(1f, 0f, 0f), (Math.PI / 2).toFloat())
        }
        var b = piece.bounds.transformed(Pose(V3.ZERO, q))
        if (b.size.z > b.size.x) { q = Q.yawDeg(90f) * q; b = piece.bounds.transformed(Pose(V3.ZERO, q)) }
        val c = MAT_C[matOf(l, id)]
        val target = V3(c.x, MAT_TOP + b.size.y * CM / 2, c.z)
        val centre = b.centre * CM
        return Pose(target - centre, q)
    }

    // ---------------------------------------------------------------- the rest of the volume
    val PLAQUE_C = V3(0f, 0.29f, 0f)
    val PLAQUE_SIZE = V3(0.44f, 0.05f, 0.012f)
    val BADGE_C = V3(0.28f, 0.29f, 0f)
    const val BADGE_D = 0.08f
    val MINI_C = V3(-0.27f, FLOOR_Y, -0.15f)
    const val MINI_K = 0.4f
    const val MINI_ALL_K = 0.25f

    /** S10: the eight minis on the two mats, four each (design E8). */
    fun miniAllPose(i: Int): Pose {
        val side = if (i < 4) -1f else 1f
        val k = i % 4
        return Pose(V3(side * 0.22f - 0.045f + (k % 2) * 0.09f, MAT_TOP, 0.255f - 0.035f + (k / 2) * 0.07f), Q.yawDeg(-45f))
    }

    // ---------------------------------------------------------------- arrows (brief item 4, profile D50 / D17)
    /**
     * Collider sphere of every arrow: 12 cm (the visible arrow is 7 x 6.6 cm, two crossed fins). QA #1 q1-2: 11 cm fell
     * to 2.99° from the side eyes at the far turntable stops; 12 cm keeps >= 3.2° out to 2.1 m.
     */
    const val ARROW_HIT_D = 0.12f
    const val ARROW_LEN = 0.07f
    /** Gap between the ghost's outer end and the arrow (design: 虚影尾巴外 3 cm). */
    const val ARROW_GAP = 0.03f
    /** Gate B decision 4 with margin: every hit sphere >= 3.2° (rule 3°) and every gap >= 2.3° (rule 2°) from all [EYES]. */
    const val ARROW_MIN_DEG = 3.2f
    const val ARROW_GAP_DEG = 2.3f
    /** At least this much of each hit sphere (centre + 6 points at 0.7 r) is seen past wood, ghosts and turntable. */
    const val ARROW_MIN_VIS = 0.7f

    /** The default seated eye (design occlusionCheck.eye, N10). */
    val EYE = V3(0f, 0.061f, 1.749f)
    /** The three eye spots of the arrow audit: front, 30° left, 30° right on the same 1.75 m circle. */
    val EYES = listOf(EYE, V3(-0.875f, 0.061f, 1.515f), V3(0.875f, 0.061f, 1.515f))
    /** Where the main fin of every arrow turns to: between the three eyes. */
    val EYE_MID = V3(0f, 0.061f, 1.593f)

    data class ArrowSpot(val pos: V3, val push: V3, val lifted: Boolean)

    /**
     * Where candidate [c]'s arrow would stand on its own: beyond the outer end of its ghost along the way it comes in,
     * pointing the way it is pushed. When the turntable has been turned so that the way in faces away from the user, the
     * arrow is lifted above the joint (brief item 4: 转台转动后箭头跟到可点的一侧) so it is never hidden behind the wood.
     */
    fun arrowSpot(l: Lesson, c: Cand, entryCm: Float, yaw: Q): ArrowSpot {
        val pose = candPose(l, c, entryCm, yaw)
        val b = l.piece(c.piece).bounds.let { Aabb(it.lo * CM, it.hi * CM) }.transformed(pose)
        val d = yaw.rotate(c.from.v).normalized()
        val h = b.size * 0.5f
        val ext = abs(d.x) * h.x + abs(d.y) * h.y + abs(d.z) * h.z
        var p = b.centre + d * (ext + ARROW_GAP + ARROW_LEN / 2)
        val lifted = d.z < -0.2f
        if (lifted) p = V3(p.x, max(p.y, jointTop(l) + LIFT), p.z)
        return ArrowSpot(p, -d, lifted)
    }

    /** Highest a hit sphere centre may go (it stays under the plaque, y 0.265, and in the inner box). */
    const val LIFT_MAX = 0.20f

    /** Gap (degrees) between two hit spheres seen from [eye]: centre angle minus both half sizes. */
    fun gapDeg(eye: V3, a: V3, b: V3): Float = sepDeg(eye, a, b) - angleDeg(eye, a, ARROW_HIT_D) / 2 - angleDeg(eye, b, ARROW_HIT_D) / 2

    /** The smallest gap over all three eyes (QA #1 q1-1: the old layout only looked from the front eye). */
    fun minGapDeg(a: V3, b: V3): Float = EYES.minOf { gapDeg(it, a, b) }

    // ---------------------------------------------------------------- the control window as an occluder (v0.1.2, QA #2 q2-1)
    /**
     * The control window as the system solves Placement.right(volume) (logcat WorldSpaceNative onShowSolveTransform,
     * 2026-11-19 emulator-5554, two cold starts and QA #2 identical): centre (0.589, 0, 0.492) from the volume centre,
     * turned -24.3° about y to face the user. The window measures 0.00080 m/dp (logcat `WINDOW mPerDp`, the app's own
     * dpToLength in the window), so the largest window it can be resized to (640 x 460 dp) is 0.512 x 0.368 m.
     */
    val WINDOW_C = V3(0.589f, 0f, 0.492f)
    const val WINDOW_YAW = -24.3f
    const val WINDOW_M_PER_DP = 0.0008f
    /**
     * The window's whole footprint in its own frame (x right, y up, z toward the user), in metres: the largest window
     * (640 x 460 dp), the native TabBar capsule above it (90 dp), the system dots / close row below it (60 dp,
     * profile D8 补), plus 2 cm all round and 3 cm of depth for the capsule standing proud of the glass.
     */
    val WINDOW_BOX = Aabb(
        V3(-(320f * WINDOW_M_PER_DP + 0.02f), -((230f + 60f) * WINDOW_M_PER_DP + 0.02f), -0.03f),
        V3(320f * WINDOW_M_PER_DP + 0.02f, (230f + 90f) * WINDOW_M_PER_DP + 0.02f, 0.03f),
    )
    private val W_X = Q.yawDeg(WINDOW_YAW).rotate(V3(1f, 0f, 0f))
    private val W_Y = V3(0f, 1f, 0f)
    private val W_Z = Q.yawDeg(WINDOW_YAW).rotate(V3(0f, 0f, 1f))
    /** A volume point in the window's frame. */
    fun toWindow(p: V3): V3 { val d = p - WINDOW_C; return V3(d.dot(W_X), d.dot(W_Y), d.dot(W_Z)) }

    /** Whether the segment [a]..[b] passes through the control window's footprint [WINDOW_BOX]. */
    fun windowHit(a: V3, b: V3): Boolean = segHits(toWindow(a), toWindow(b), WINDOW_BOX)

    /** The 1:1 mini of lesson [l] (left of the turntable, 0.4 x the 2.5x joint, turned -45°). */
    fun miniBox(l: Lesson): Aabb = Aabb(l.bounds.lo * (CM * MINI_K), l.bounds.hi * (CM * MINI_K)).transformed(Pose(MINI_C, Q.yawDeg(-45f)))

    /** The walnut plaque (no collider, but it can still hide an arrow). */
    val PLAQUE_BOX = Aabb(PLAQUE_C - PLAQUE_SIZE * 0.5f, PLAQUE_C + PLAQUE_SIZE * 0.5f)

    /** The seven sample points of a hit sphere at [c] (centre + 6 at 0.7 r), as both audits use them. */
    fun samplePoints(c: V3): List<V3> {
        val r = ARROW_HIT_D / 2 * 0.7f
        return listOf(c, c + V3(r, 0f, 0f), c - V3(r, 0f, 0f), c + V3(0f, r, 0f), c - V3(0f, r, 0f), c + V3(0f, 0f, r), c - V3(0f, 0f, r))
    }

    /** No part of the hit sphere at [c] is behind the control window from [eye] (D50 补: nothing may hide an arrow). */
    fun clearOfWindow(eye: V3, c: V3): Boolean = samplePoints(c).none { windowHit(eye, it) }

    /**
     * What can stand between an eye and an arrow of lesson [li] step [k] at turntable yaw [yaw]: the pieces (fixed and
     * installed at home, the rest on their mats), the step's ghosts and the block, the 1:1 mini and the plaque (v0.1.2). The drum is checked in [drumHit], the control window in [windowHit].
     */
    fun occluders(li: Int, k: Int, yaw: Q): List<Aabb> {
        val l = Joints.ALL[li]
        fun box(id: String, p: Pose) = l.piece(id).bounds.let { Aabb(it.lo * CM, it.hi * CM) }.transformed(p)
        val installed = (0 until k).map { l.steps[it].fit.piece }.toSet()
        val out = ArrayList<Aabb>()
        for (pc in l.pieces) out += box(pc.id, if (pc.fixed || pc.id in installed) joint(l, yaw) else matPose(l, pc.id))
        for ((i, c) in l.steps[k].cands.withIndex()) out += box(c.piece, candPose(l, c, entryCm(JointTable.row(li, k, i), c), yaw))
        if (l.base == Base.BLOCK) out += Aabb(V3(-0.06f, TABLE_Y, -0.06f), V3(0.06f, TABLE_Y + BLOCK_H, 0.06f))
        out += miniBox(l)
        out += PLAQUE_BOX
        return out
    }

    private fun inDrum(q: V3) = q.y > TURNTABLE_C.y - 0.05f && q.y < TABLE_Y && q.x * q.x + q.z * q.z < TABLE_R * TABLE_R

    /** Whether the segment [a]..[b] passes through the turntable drum (sampled every 5 mm). */
    fun drumHit(a: V3, b: V3): Boolean {
        val d = b - a; val n = (d.length / 0.005f).toInt().coerceAtLeast(1)
        return (1 until n).any { inDrum(a + d * (it.toFloat() / n)) }
    }

    /** Whether the segment [a]..[b] passes through [box] (slab test). */
    fun segHits(a: V3, b: V3, box: Aabb): Boolean {
        var t0 = 0f; var t1 = 1f
        for (ax in 0..2) {
            val d = b[ax] - a[ax]
            if (abs(d) < 1e-9f) { if (a[ax] <= box.lo[ax] || a[ax] >= box.hi[ax]) return false; continue }
            var ta = (box.lo[ax] - a[ax]) / d; var tb = (box.hi[ax] - a[ax]) / d
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 >= t1) return false
        }
        return t1 > 0f && t0 < 1f
    }

    /** Fraction of the hit sphere at [c] (centre + 6 points at 0.7 r) that [eye] sees past [occ] and the drum. */
    fun visibleFrac(eye: V3, c: V3, occ: List<Aabb>): Float {
        val r = ARROW_HIT_D / 2 * 0.7f
        val pts = listOf(c, c + V3(r, 0f, 0f), c - V3(r, 0f, 0f), c + V3(0f, r, 0f), c - V3(0f, r, 0f), c + V3(0f, 0f, r), c - V3(0f, 0f, r))
        return pts.count { p -> occ.none { segHits(eye, p, it) } && !drumHit(eye, p) }.toFloat() / pts.size
    }

    /** The hit sphere at [c] stays inside the inner box. */
    fun sphereInside(c: V3): Boolean {
        val r = ARROW_HIT_D / 2
        return c.x - r >= INNER.lo.x && c.x + r <= INNER.hi.x && c.y - r >= INNER.lo.y && c.y + r <= INNER.hi.y && c.z - r >= INNER.lo.z && c.z + r <= INNER.hi.z
    }

    /**
     * Offsets an arrow may move by when its own spot is crowded or hidden, nearest first. Toward the user (+z) and up
     * cost less than sideways, away costs most (D50 补: 摆在离用户近的一侧).
     */
    private val MOVES: List<V3> by lazy {
        val out = ArrayList<Pair<Float, V3>>()
        for (iy in -4..16) for (ix in -32..20) for (iz in -10..18) {
            val d = V3(ix * 0.01f, iy * 0.015f, iz * 0.015f)
            out += sqrt(d.x * d.x + d.y * d.y * (if (d.y > 0) 0.8f else 1.5f) + d.z * d.z * (if (d.z > 0) 0.6f else 2.5f)) to d
        }
        out.sortedBy { it.first }.map { it.second }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<Triple<Int, Int, Int>, List<ArrowSpot>>()

    /**
     * All arrows of lesson [li] step [k] at whole turntable stop [stop], cached. Each arrow keeps its own spot when that
     * passes; otherwise it moves to the nearest spot (cheapest of [MOVES]) where, from all three [EYES], its hit sphere is
     * >= [ARROW_MIN_DEG], >= [ARROW_GAP_DEG] from every arrow already placed, >= [ARROW_MIN_VIS] visible past wood, mini
     * and plaque, wholly clear of the control window (v0.1.2, QA #2 q2-1) and inside the inner box (QA #1 q1-1).
     */
    fun arrowSpotsAt(li: Int, k: Int, stop: Int): List<ArrowSpot> = cache.getOrPut(Triple(li, k, Math.floorMod(stop, 4))) {
        val l = Joints.ALL[li]
        val yaw = yawQ(Math.floorMod(stop, 4).toFloat())
        val own = l.steps[k].cands.mapIndexed { i, c -> arrowSpot(l, c, entryCm(JointTable.row(li, k, i), c), yaw) }
        val occ = occluders(li, k, yaw)
        fun place(order: List<Int>): Pair<Float, List<ArrowSpot>>? {
            val placed = arrayOfNulls<ArrowSpot>(own.size)
            var cost = 0f
            for (i in order) {
                fun ok(p: V3): Boolean {
                    if (p.y > LIFT_MAX || !sphereInside(p)) return false
                    if (EYES.any { angleDeg(it, p, ARROW_HIT_D) < ARROW_MIN_DEG }) return false
                    if (placed.any { it != null && minGapDeg(p, it.pos) < ARROW_GAP_DEG }) return false
                    if (occ.any { it.contains(p) }) return false
                    if (EYES.any { !clearOfWindow(it, p) }) return false
                    return EYES.all { visibleFrac(it, p, occ) >= ARROW_MIN_VIS }
                }
                val base = own[i].pos
                val m = MOVES.firstOrNull { ok(base + it) } ?: return null
                placed[i] = own[i].copy(pos = base + m); cost += m.length
            }
            return cost to placed.map { it!! }
        }
        // arrows nearer the user first, lifted ones last; when that order leaves one without a spot (an earlier arrow
        // sits in its way from a side eye), every other order is tried and the cheapest full layout wins
        val first = own.indices.sortedWith(compareBy({ own[it].lifted }, { -own[it].pos.z }))
        place(first)?.second ?: perms(own.indices.toList()).mapNotNull { place(it) }.minByOrNull { it.first }?.second
            ?: own // never reached: LayoutTest.arrowLayoutMarginHolds proves a full layout for every step x stop
    }

    private fun perms(l: List<Int>): List<List<Int>> = if (l.size <= 1) listOf(l) else l.flatMap { x -> perms(l - x).map { listOf(x) + it } }

    /** Lays out every lesson x step x stop ahead of time (called off the main thread while the scene loads). */
    fun warmArrows() { for ((li, l) in Joints.ALL.withIndex()) for (k in l.steps.indices) for (s in 0..3) arrowSpotsAt(li, k, s) }

    /**
     * The arrows while the turntable is at [stopF] (between stops while it turns): the layouts of the two neighbouring
     * stops are carried round with the turntable and blended, so at a whole stop it is exactly [arrowSpotsAt].
     */
    fun arrowSpots(li: Int, k: Int, stopF: Float): List<ArrowSpot> {
        val s0 = kotlin.math.floor(stopF).toInt(); val u = stopF - s0
        val a = arrowSpotsAt(li, k, s0)
        if (u < 1e-4f) return a
        val b = arrowSpotsAt(li, k, s0 + 1)
        fun carry(sp: ArrowSpot, from: Int): ArrowSpot {
            val q = Q.yawDeg(90f * (stopF - from))
            val c = V3(0f, sp.pos.y, 0f)
            return ArrowSpot(q.rotate(sp.pos - c) + c, q.rotate(sp.push), sp.lifted)
        }
        return a.indices.map { i ->
            val x = carry(a[i], s0); val y = carry(b[i], s0 + 1)
            // carried round the corner of the box a sphere would poke out: keep it inside while turning
            val r = ARROW_HIT_D / 2
            val p = x.pos.lerp(y.pos, u).max(INNER.lo + V3(r, r, r)).min(INNER.hi - V3(r, r, r))
            ArrowSpot(p, x.push.lerp(y.push, u).normalized(), if (u < 0.5f) x.lifted else y.lifted)
        }
    }

    /** How far above the top of the joint a lifted arrow floats. */
    const val LIFT = 0.10f

    fun jointTop(l: Lesson): Float = baseY(l) + l.bounds.hi.y * CM

    /** Angular size (degrees) of a sphere of diameter [d] at [c] seen from [eye]. */
    fun angleDeg(eye: V3, c: V3, d: Float): Float = Math.toDegrees(2.0 * atan((d / 2 / (c - eye).length).toDouble())).toFloat()

    /** Angle (degrees) between two points seen from [eye]. */
    fun sepDeg(eye: V3, a: V3, b: V3): Float {
        val u = (a - eye).normalized(); val v = (b - eye).normalized()
        return Math.toDegrees(kotlin.math.acos(u.dot(v).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /** Rotation whose +x is [x] and whose +z leans toward [toward] (the flat arrow faces the user). */
    fun basis(x: V3, toward: V3): Q {
        val ax = x.normalized()
        var az = toward - ax * toward.dot(ax)
        if (az.length < 1e-4f) az = V3(0f, 1f, 0f) - ax * ax.y
        az = az.normalized()
        val ay = az.cross(ax)
        // rotation matrix columns ax, ay, az -> quaternion
        val m00 = ax.x; val m01 = ay.x; val m02 = az.x
        val m10 = ax.y; val m11 = ay.y; val m12 = az.y
        val m20 = ax.z; val m21 = ay.z; val m22 = az.z
        val tr = m00 + m11 + m22
        return if (tr > 0) {
            val s = sqrt(tr + 1f) * 2
            Q((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2
            Q(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2
            Q((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2
            Q((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
        }.norm()
    }

    /**
     * The arrow's rotation: pointing along [push], its main fin facing the middle of the three eyes (the second fin
     * stands across it, so no eye sees only a thin edge, QA #1 q1-2).
     */
    fun arrowRot(spot: ArrowSpot): Q = basis(spot.push, EYE_MID - spot.pos)
}
