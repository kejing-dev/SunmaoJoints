package io.embodit.sunmaojoints.domain

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    operator fun unaryMinus() = V3(-x, -y, -z)
    operator fun get(axis: Int): Float = when (axis) { 0 -> x; 1 -> y; else -> z }
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 { val l = length; return if (l < 1e-9f) this else this * (1f / l) }
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun min(o: V3) = V3(minOf(x, o.x), minOf(y, o.y), minOf(z, o.z))
    fun max(o: V3) = V3(maxOf(x, o.x), maxOf(y, o.y), maxOf(z, o.z))
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val UP = V3(0f, 1f, 0f)
        fun axis(a: Int, k: Float = 1f) = when (a) { 0 -> V3(k, 0f, 0f); 1 -> V3(0f, k, 0f); else -> V3(0f, 0f, k) }
    }
}

/** A unit quaternion (x, y, z, w). */
data class Q(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Q) = Q(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )
    fun rotate(v: V3): V3 {
        val u = V3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }
    fun dot(o: Q) = x * o.x + y * o.y + z * o.z + w * o.w
    fun slerp(o: Q, t: Float): Q {
        var b = o
        var d = dot(o)
        if (d < 0) { b = Q(-o.x, -o.y, -o.z, -o.w); d = -d }
        if (d > 0.9995f) return Q(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t).norm()
        val th = acos(d.coerceIn(-1f, 1f)); val s = sin(th)
        val ka = sin((1 - t) * th) / s; val kb = sin(t * th) / s
        return Q(x * ka + b.x * kb, y * ka + b.y * kb, z * ka + b.z * kb, w * ka + b.w * kb)
    }
    fun norm(): Q { val l = sqrt(x * x + y * y + z * z + w * w); return Q(x / l, y / l, z / l, w / l) }

    companion object {
        val I = Q(0f, 0f, 0f, 1f)
        fun axisAngle(a: V3, rad: Float): Q { val n = a.normalized(); val s = sin(rad / 2); return Q(n.x * s, n.y * s, n.z * s, cos(rad / 2)) }
        fun yawDeg(deg: Float): Q = axisAngle(V3.UP, Math.toRadians(deg.toDouble()).toFloat())
    }
}

/** A rigid pose: rotate, then translate. */
data class Pose(val pos: V3, val rot: Q = Q.I) {
    fun apply(p: V3): V3 = rot.rotate(p) + pos
    operator fun times(o: Pose): Pose = Pose(apply(o.pos), rot * o.rot)

    companion object {
        val I = Pose(V3.ZERO)
        /** Rotation [q] about pivot [c]: p -> q (p - c) + c. */
        fun about(c: V3, q: Q) = Pose(c - q.rotate(c), q)
    }
}

/** What a prim is made of. RED (mortise lining) is paint only: it never collides (design E3: 卯眼内壁朱红子网格). */
enum class Mat { LIGHT, DARK, GOLD, RED;
    val solid: Boolean get() = this != RED
}

/** An axis-aligned box. All design geometry is in centimetres, joint frame (+x = 右前, +z = 左前, y = 0 on the base). */
data class Aabb(val lo: V3, val hi: V3) {
    fun contains(p: V3, eps: Float = 0f) = p.x > lo.x + eps && p.x < hi.x - eps && p.y > lo.y + eps && p.y < hi.y - eps && p.z > lo.z + eps && p.z < hi.z - eps
    fun union(o: Aabb) = Aabb(lo.min(o.lo), hi.max(o.hi))
    fun overlaps(o: Aabb) = lo.x < o.hi.x && hi.x > o.lo.x && lo.y < o.hi.y && hi.y > o.lo.y && lo.z < o.hi.z && hi.z > o.lo.z
    val size: V3 get() = hi - lo
    val centre: V3 get() = (lo + hi) * 0.5f
    fun corners(): List<V3> = listOf(
        V3(lo.x, lo.y, lo.z), V3(hi.x, lo.y, lo.z), V3(lo.x, hi.y, lo.z), V3(hi.x, hi.y, lo.z),
        V3(lo.x, lo.y, hi.z), V3(hi.x, lo.y, hi.z), V3(lo.x, hi.y, hi.z), V3(hi.x, hi.y, hi.z),
    )
    fun transformed(p: Pose): Aabb {
        var a = V3(1e9f, 1e9f, 1e9f); var b = V3(-1e9f, -1e9f, -1e9f)
        for (c in corners()) { val t = p.apply(c); a = a.min(t); b = b.max(t) }
        return Aabb(a, b)
    }
    fun offset(d: V3) = Aabb(lo + d, hi + d)
}

/**
 * A straight prism: polygon [poly] in the plane across [axis], extruded from [lo] to [hi] along it. The polygon's two
 * coordinates are (u, v) = (x, z) for axis y, (x, y) for axis z, (z, y) for axis x.
 */
class Prim(val axis: Int, val poly: List<Pair<Float, Float>>, val lo: Float, val hi: Float, val mat: Mat) {
    val bounds: Aabb by lazy {
        val us = poly.map { it.first }; val vs = poly.map { it.second }
        when (axis) {
            1 -> Aabb(V3(us.min(), lo, vs.min()), V3(us.max(), hi, vs.max()))
            2 -> Aabb(V3(us.min(), vs.min(), lo), V3(us.max(), vs.max(), hi))
            else -> Aabb(V3(lo, vs.min(), us.min()), V3(hi, vs.max(), us.max()))
        }
    }

    fun uv(p: V3): Pair<Float, Float> = when (axis) { 1 -> p.x to p.z; 2 -> p.x to p.y; else -> p.z to p.y }
    fun along(p: V3): Float = p[axis]
    /** 3D point of polygon coordinate (u, v) at height [h] along the axis. */
    fun point(u: Float, v: Float, h: Float): V3 = when (axis) { 1 -> V3(u, h, v); 2 -> V3(u, v, h); else -> V3(h, v, u) }

    /** Strictly inside (by more than [eps] cm): touching faces never count as an overlap. */
    fun contains(p: V3, eps: Float = 0.01f): Boolean {
        if (!bounds.contains(p, eps)) return false
        val h = along(p)
        if (h <= lo + eps || h >= hi - eps) return false
        val (u, v) = uv(p)
        return insidePoly(u, v, eps)
    }

    private fun insidePoly(u: Float, v: Float, eps: Float): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (ui, vi) = poly[i]; val (uj, vj) = poly[j]
            if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) inside = !inside
            j = i
        }
        if (!inside) return false
        // keep [eps] away from every edge
        j = poly.size - 1
        for (i in poly.indices) {
            if (segDist(u, v, poly[j], poly[i]) < eps) return false
            j = i
        }
        return true
    }

    companion object {
        private fun segDist(u: Float, v: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
            val dx = b.first - a.first; val dy = b.second - a.second
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-12f) 0f else (((u - a.first) * dx + (v - a.second) * dy) / l2).coerceIn(0f, 1f)
            val px = a.first + t * dx - u; val py = a.second + t * dy - v
            return sqrt(px * px + py * py)
        }

        /** A box from centre and size (the design's own notation, sample.js box(c, s)). */
        fun cube(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, mat: Mat): Prim =
            span(cx - sx / 2, cx + sx / 2, cy - sy / 2, cy + sy / 2, cz - sz / 2, cz + sz / 2, mat)

        /** A box from its x / y / z ranges. */
        fun span(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, mat: Mat): Prim =
            Prim(1, listOf(x0 to z0, x1 to z0, x1 to z1, x0 to z1), y0, y1, mat)

        /** Polygon in (x, z), extruded along y from [y0] to [y1]. */
        fun xz(pts: List<Pair<Float, Float>>, y0: Float, y1: Float, mat: Mat) = Prim(1, pts, y0, y1, mat)
        /** Polygon in (x, y), extruded along z. */
        fun xy(pts: List<Pair<Float, Float>>, z0: Float, z1: Float, mat: Mat) = Prim(2, pts, z0, z1, mat)
        /** Polygon in (z, y), extruded along x. */
        fun zy(pts: List<Pair<Float, Float>>, x0: Float, x1: Float, mat: Mat) = Prim(0, pts, x0, x1, mat)
    }
}

/** One piece of wood: its prims (joint frame, as it sits assembled). */
class Piece(val id: String, val zh: String, val en: String, val fixed: Boolean, val prims: List<Prim>) {
    val bounds: Aabb by lazy { prims.map { it.bounds }.reduce { a, b -> a.union(b) } }
    val solids: List<Prim> by lazy { prims.filter { it.mat.solid } }
    fun inside(p: V3, eps: Float = 0.01f): Boolean = solids.any { it.contains(p, eps) }
}
