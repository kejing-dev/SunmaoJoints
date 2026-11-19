package io.embodit.sunmaojoints.ui.board

import io.embodit.sunmaojoints.domain.Mat
import io.embodit.sunmaojoints.domain.Piece
import io.embodit.sunmaojoints.domain.Prim
import io.embodit.sunmaojoints.domain.V3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Triangles with flat normals and UVs, metres (pure: unit-tested for winding and the triangle budget). */
class MeshData {
    val pos = ArrayList<V3>()
    val nrm = ArrayList<V3>()
    val uv = ArrayList<FloatArray>()
    val idx = ArrayList<Int>()
    val triangles: Int get() = idx.size / 3
    val empty: Boolean get() = idx.isEmpty()

    /** Adds triangle a, b, c turned so it faces [out]. */
    fun tri(a: V3, b: V3, c: V3, out: V3, uvOf: (V3) -> FloatArray) {
        val n = (b - a).cross(c - a)
        val (p, q) = if (n.dot(out) >= 0f) b to c else c to b
        val base = pos.size
        val nn = out.normalized()
        for (v in listOf(a, p, q)) { pos += v; nrm += nn; uv += uvOf(v) }
        idx += listOf(base, base + 1, base + 2)
    }
}

object MeshGen {
    /** Ear clipping of a simple polygon (either winding); returns index triples. */
    fun triangulate(poly: List<Pair<Float, Float>>): List<Triple<Int, Int, Int>> {
        val n = poly.size
        if (n < 3) return emptyList()
        var area = 0f
        for (i in 0 until n) { val (x0, y0) = poly[i]; val (x1, y1) = poly[(i + 1) % n]; area += x0 * y1 - x1 * y0 }
        val ccw = area > 0
        val rest = (0 until n).toMutableList()
        val out = ArrayList<Triple<Int, Int, Int>>()
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) = (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        fun inside(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>): Boolean {
            val d1 = cross(a, b, p); val d2 = cross(b, c, p); val d3 = cross(c, a, p)
            val neg = d1 < -1e-6f || d2 < -1e-6f || d3 < -1e-6f
            val pos = d1 > 1e-6f || d2 > 1e-6f || d3 > 1e-6f
            return !(neg && pos)
        }
        var guard = 0
        while (rest.size > 3 && guard++ < 10_000) {
            var cut = false
            for (k in rest.indices) {
                val i0 = rest[(k + rest.size - 1) % rest.size]; val i1 = rest[k]; val i2 = rest[(k + 1) % rest.size]
                val c = cross(poly[i0], poly[i1], poly[i2])
                if (if (ccw) c <= 1e-7f else c >= -1e-7f) continue
                if (rest.any { it != i0 && it != i1 && it != i2 && inside(poly[it], poly[i0], poly[i1], poly[i2]) }) continue
                out += Triple(i0, i1, i2); rest.removeAt(k); cut = true; break
            }
            if (!cut) break
        }
        if (rest.size == 3) out += Triple(rest[0], rest[1], rest[2])
        return out
    }

    /**
     * Wood UVs: the grain runs along the piece's long axis on its side faces; end faces show the other two axes. One
     * texture repeat every 24 cm.
     */
    private fun uvFor(longAxis: Int, n: V3): (V3) -> FloatArray {
        val na = listOf(abs(n.x), abs(n.y), abs(n.z)).let { l -> l.indexOf(l.max()) }
        val (ua, va) = if (na == longAxis) ((longAxis + 1) % 3) to ((longAxis + 2) % 3) else longAxis to (3 - longAxis - na)
        return { p -> floatArrayOf(p[ua] / 0.24f, p[va] / 0.24f) }
    }

    /** Adds prim [p] (centimetres) to [m] in metres. */
    fun prism(m: MeshData, p: Prim, longAxis: Int, k: Float = 0.01f) {
        val poly = p.poly
        val axisV = V3.axis(p.axis)
        val tris = triangulate(poly)
        for ((h, out) in listOf(p.hi to axisV, p.lo to -axisV)) {
            val uvf = uvFor(longAxis, out)
            for ((a, b, c) in tris) m.tri(p.point(poly[a].first, poly[a].second, h) * k, p.point(poly[b].first, poly[b].second, h) * k, p.point(poly[c].first, poly[c].second, h) * k, out, uvf)
        }
        var area = 0f
        for (i in poly.indices) { val (x0, y0) = poly[i]; val (x1, y1) = poly[(i + 1) % poly.size]; area += x0 * y1 - x1 * y0 }
        val sign = if (area > 0) 1f else -1f
        val o = p.point(0f, 0f, 0f)
        for (i in poly.indices) {
            val (u0, v0) = poly[i]; val (u1, v1) = poly[(i + 1) % poly.size]
            val du = u1 - u0; val dv = v1 - v0
            if (abs(du) + abs(dv) < 1e-6f) continue
            // outward of edge (p -> q) of a CCW polygon is (dv, -du)
            val out = (p.point(dv * sign, -du * sign, 0f) - o).normalized()
            val a = p.point(u0, v0, p.lo) * k; val b = p.point(u1, v1, p.lo) * k
            val c = p.point(u1, v1, p.hi) * k; val d = p.point(u0, v0, p.hi) * k
            val uvf = uvFor(longAxis, out)
            m.tri(a, b, c, out, uvf); m.tri(a, c, d, out, uvf)
        }
    }

    /** One piece split by material: wood (its own wood colour), gold tenons, red linings. */
    class PieceParts(val wood: MeshData, val woodMat: Mat, val gold: MeshData, val red: MeshData) {
        val triangles: Int get() = wood.triangles + gold.triangles + red.triangles
    }

    fun piece(pc: Piece): PieceParts {
        val s = pc.bounds.size
        val long = listOf(s.x, s.y, s.z).let { l -> l.indexOf(l.max()) }
        val wood = MeshData(); val gold = MeshData(); val red = MeshData()
        var woodMat = Mat.LIGHT
        for (p in pc.prims) when (p.mat) {
            Mat.GOLD -> prism(gold, p, long)
            Mat.RED -> prism(red, p, long)
            else -> { woodMat = p.mat; prism(wood, p, long) }
        }
        return PieceParts(wood, woodMat, gold, red)
    }

    /** A drum (cylinder) of radius [r] and height [h] centred on the origin, [seg] sides. */
    fun drum(r: Float, h: Float, seg: Int): MeshData {
        val pts = (0 until seg).map { i -> val a = 2 * PI * i / seg; (r * cos(a)).toFloat() to (r * sin(a)).toFloat() }
        val m = MeshData()
        prism(m, Prim(1, pts, -h / 2, h / 2, Mat.DARK), 0, 1f)
        return m
    }

    /** A unit cube centred on the origin (scaled per use: mats, block, plaque board, flash). */
    fun cube(): MeshData {
        val m = MeshData()
        prism(m, Prim.span(-0.5f, 0.5f, -0.5f, 0.5f, -0.5f, 0.5f, Mat.DARK), 0, 1f)
        return m
    }

    /** The flat arrow (design E5: 7 x 6.6 x 1.5 cm), pointing +x, faces toward +-z. */
    fun arrow(): MeshData {
        val m = MeshData()
        val pts = listOf(-3.5f to -1.8f, 0.2f to -1.8f, 0.2f to -3.3f, 3.5f to 0f, 0.2f to 3.3f, 0.2f to 1.8f, -3.5f to 1.8f)
        prism(m, Prim.xy(pts, -0.75f, 0.75f, Mat.LIGHT), 0)
        return m
    }

    /** A quad in the xy plane facing +z, [w] x [h], UV 0..1 (v up). */
    fun quadXY(w: Float, h: Float): MeshData {
        val m = MeshData()
        val a = V3(-w / 2, -h / 2, 0f); val b = V3(w / 2, -h / 2, 0f); val c = V3(w / 2, h / 2, 0f); val d = V3(-w / 2, h / 2, 0f)
        val uvf = { p: V3 -> floatArrayOf(p.x / w + 0.5f, p.y / h + 0.5f) }
        m.tri(a, b, c, V3(0f, 0f, 1f), uvf); m.tri(a, c, d, V3(0f, 0f, 1f), uvf)
        return m
    }

    /** A quad in the xz plane facing +y (contact shadow). */
    fun quadXZ(w: Float, d: Float): MeshData {
        val m = MeshData()
        val a = V3(-w / 2, 0f, -d / 2); val b = V3(w / 2, 0f, -d / 2); val c = V3(w / 2, 0f, d / 2); val e = V3(-w / 2, 0f, d / 2)
        val uvf = { p: V3 -> floatArrayOf(p.x / w + 0.5f, p.z / d + 0.5f) }
        m.tri(a, b, c, V3(0f, 1f, 0f), uvf); m.tri(a, c, e, V3(0f, 1f, 0f), uvf)
        return m
    }
}
