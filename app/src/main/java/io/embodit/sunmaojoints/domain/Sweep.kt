package io.embodit.sunmaojoints.domain

/** Where a candidate piece is held (joint frame) at push distance [s] cm along its entry direction. */
object Motion {
    fun hold(lesson: Lesson, c: Cand): Pose = when (val h = c.hold) {
        Hold.Home -> Pose.I
        is Hold.Yaw -> Pose(V3.ZERO, Q.yawDeg(h.deg))
        Hold.FlatX -> Pose.about(lesson.piece(c.piece).bounds.centre, Q.axisAngle(V3(1f, 0f, 0f), (Math.PI / 2).toFloat()))
    }

    fun at(lesson: Lesson, c: Cand, s: Float): Pose = Pose(c.from.v * s) * hold(lesson, c)
}

/**
 * The voxel sweep (design lessonRules.truth, U1–U3, U8): a piece is sampled at the centres of a [size] cm grid; a
 * sample collides when it is strictly inside a solid prim of a piece already standing. Pushing a candidate means moving
 * it from far out toward s = 0 along its entry direction; the first s where a sample goes in is where it stops.
 */
object Sweep {
    fun samples(piece: Piece, size: Float): List<V3> {
        val b = piece.bounds
        val out = ArrayList<V3>()
        val nx = ((b.hi.x - b.lo.x) / size).toInt().coerceAtLeast(1)
        val ny = ((b.hi.y - b.lo.y) / size).toInt().coerceAtLeast(1)
        val nz = ((b.hi.z - b.lo.z) / size).toInt().coerceAtLeast(1)
        val sx = (b.hi.x - b.lo.x) / nx; val sy = (b.hi.y - b.lo.y) / ny; val sz = (b.hi.z - b.lo.z) / nz
        for (i in 0 until nx) for (j in 0 until ny) for (k in 0 until nz) {
            val p = V3(b.lo.x + (i + 0.5f) * sx, b.lo.y + (j + 0.5f) * sy, b.lo.z + (k + 0.5f) * sz)
            if (piece.inside(p, 0f)) out += p
        }
        return out
    }

    /** Samples of [pts] (piece frame) placed by [pose] that sit inside any of [standing]. */
    fun hits(pts: List<V3>, pose: Pose, standing: List<Piece>): List<V3> {
        val solids = standing.flatMap { it.solids }
        val out = ArrayList<V3>()
        for (p in pts) {
            val q = pose.apply(p)
            if (solids.any { it.contains(q) }) out += q
        }
        return out
    }

    fun collides(pts: List<V3>, pose: Pose, standing: List<Piece>): Boolean {
        val solids = standing.flatMap { it.solids }
        val box = solids.map { it.bounds }.reduceOrNull { a, b -> a.union(b) } ?: return false
        for (p in pts) {
            val q = pose.apply(p)
            if (!box.contains(q)) continue
            if (solids.any { it.contains(q) }) return true
        }
        return false
    }

    /** Result of pushing one candidate: [stopCm] = 0 means it reached home; [contact] = where it touched (joint frame). */
    data class Push(val stopCm: Float, val contact: Aabb?)

    const val FAR = 40f

    fun push(lesson: Lesson, c: Cand, standing: List<Piece>, size: Float): Push {
        val pts = samples(lesson.piece(c.piece), size)
        var s = FAR
        check(!collides(pts, Motion.at(lesson, c, s), standing)) { "${lesson.id} ${c.piece} not free at $FAR cm" }
        val step = size / 2
        while (s > 0f) {
            val next = maxOf(0f, s - step)
            if (collides(pts, Motion.at(lesson, c, next), standing)) {
                var lo = next; var hi = s // lo collides, hi free
                while (hi - lo > 0.02f) { val m = (lo + hi) / 2; if (collides(pts, Motion.at(lesson, c, m), standing)) lo = m else hi = m }
                val inside = hits(pts, Motion.at(lesson, c, hi - size * 0.75f), standing)
                var box: Aabb? = null
                for (q in inside) box = box?.union(Aabb(q, q)) ?: Aabb(q, q)
                return Push(hi, box)
            }
            s = next
        }
        return Push(0f, null)
    }

    /** The pieces standing when step [k] begins: the fixed ones plus every earlier step's right piece. */
    fun standing(lesson: Lesson, k: Int): List<Piece> = lesson.fixed + (0 until k).map { lesson.piece(lesson.steps[it].fit.piece) }

    /** Any two pieces overlapping when assembled (U8): the samples of one inside the other. */
    fun overlaps(lesson: Lesson, size: Float): List<String> {
        val out = ArrayList<String>()
        for (a in lesson.pieces) for (b in lesson.pieces) if (a !== b) {
            val h = hits(samples(a, size), Pose.I, listOf(b))
            if (h.isNotEmpty()) out += "${lesson.id}: ${a.id} in ${b.id} (${h.size}) e.g. ${h.first()}"
        }
        return out
    }
}
