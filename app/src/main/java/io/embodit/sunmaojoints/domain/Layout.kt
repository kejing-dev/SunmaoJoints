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
    /** Collider sphere of every arrow: 11 cm (the visible arrow is 7 x 6.6 x 1.5 cm). */
    const val ARROW_HIT_D = 0.11f
    const val ARROW_LEN = 0.07f
    /** Gap between the ghost's outer end and the arrow (design: 虚影尾巴外 3 cm). */
    const val ARROW_GAP = 0.03f

    /** The default seated eye (design occlusionCheck.eye, N10). */
    val EYE = V3(0f, 0.061f, 1.749f)
    /** The three eye spots of the arrow audit: front, 30° left, 30° right on the same 1.75 m circle. */
    val EYES = listOf(EYE, V3(-0.875f, 0.061f, 1.515f), V3(0.875f, 0.061f, 1.515f))

    data class ArrowSpot(val pos: V3, val push: V3, val lifted: Boolean)

    /**
     * Where candidate [c]'s arrow stands: beyond the outer end of its ghost along the way it comes in, pointing the way
     * it is pushed. When the turntable has been turned so that the way in faces away from the user, the arrow is lifted
     * above the joint (brief item 4: 转台转动后箭头跟到可点的一侧) so it is never hidden behind the wood.
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

    /** Highest a lifted arrow may go (its hit sphere stays under the plaque, y 0.265). */
    const val LIFT_MAX = 0.205f

    /**
     * All arrows of step [k] at turntable yaw [yaw]. A lifted arrow (way in facing away) is raised in 1 cm steps until
     * its hit sphere clears every other one by >= 2.2° from the seated eye (gate B decision 4: 彼此间隔 >= 2°).
     */
    fun arrowSpots(l: Lesson, li: Int, k: Int, yaw: Q): List<ArrowSpot> {
        val spots = l.steps[k].cands.mapIndexed { i, c -> arrowSpot(l, c, entryCm(JointTable.row(li, k, i), c), yaw) }.toMutableList()
        fun gap(a: V3, b: V3) = sepDeg(EYE, a, b) - angleDeg(EYE, a, ARROW_HIT_D) / 2 - angleDeg(EYE, b, ARROW_HIT_D) / 2
        for (i in spots.indices) {
            if (!spots[i].lifted) continue
            var p = spots[i].pos
            fun crowded(q: V3) = spots.indices.any { j -> j != i && gap(q, spots[j].pos) < 2.2f }
            while (p.y < LIFT_MAX && crowded(p)) p = V3(p.x, minOf(LIFT_MAX, p.y + 0.01f), p.z)
            // still crowded at the top: further out, at most 26.5 cm along x and z (the hit sphere stays in the volume)
            while (crowded(p) && maxOf(kotlin.math.abs(p.x), kotlin.math.abs(p.z)) < 0.2649f) {
                val k = minOf(1.03f, 0.265f / maxOf(kotlin.math.abs(p.x), kotlin.math.abs(p.z)))
                p = V3(p.x * k, p.y, p.z * k)
            }
            spots[i] = spots[i].copy(pos = p)
        }
        return spots
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

    /** The arrow's rotation: pointing along [push], its flat face toward the seated eye. */
    fun arrowRot(spot: ArrowSpot): Q = basis(spot.push, EYE - spot.pos)
}
