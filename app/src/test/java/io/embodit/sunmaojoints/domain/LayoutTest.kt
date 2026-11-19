package io.embodit.sunmaojoints.domain

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U4 (everything inside the volume shrunk by 2 cm, every step x 4 stops), U5 / M7 and gate B decision 4 (each arrow's
 * hit sphere >= 3° from the seated eye, gaps >= 2°, near side, not hidden by the wood), the mats and the plaque.
 */
class LayoutTest {
    private fun pick(lesson: Int, step: Int, yaw: Int) = PlayState(lesson = lesson, phase = Phase.PICK, step = step, yaw = yaw, stepAt = 0)

    private fun pieceBoxes(st: PlayState, f: Frames.Frame): List<Aabb> =
        f.pieces.map { v -> st.lessonData.piece(v.id).bounds.let { Aabb(it.lo * Layout.CM, it.hi * Layout.CM) }.transformed(v.pose) }

    @Test fun u4EverythingInsideTheVolume() {
        val bad = ArrayList<String>()
        for (li in 0..7) {
            val l = Joints.ALL[li]
            for (k in l.steps.indices) for (y in 0..3) {
                val st = pick(li, k, y)
                val f = Frames.frame(st, 10_000)
                for ((what, b) in Frames.boxes(st, f)) if (!(Layout.INNER.lo.x <= b.lo.x && b.hi.x <= Layout.INNER.hi.x && Layout.INNER.lo.y <= b.lo.y && b.hi.y <= Layout.INNER.hi.y && Layout.INNER.lo.z <= b.lo.z && b.hi.z <= Layout.INNER.hi.z))
                    bad += "${l.id} step $k stop $y $what $b"
            }
            // pulled apart, every stop
            for (y in 0..3) {
                val st = PlayState(lesson = li, phase = Phase.OPEN, yaw = y, openAt = 0)
                for ((what, b) in Frames.boxes(st, Frames.frame(st, 10_000))) if (!(Layout.INNER.lo.x <= b.lo.x && b.hi.x <= Layout.INNER.hi.x && b.hi.y <= Layout.INNER.hi.y && Layout.INNER.lo.z <= b.lo.z && b.hi.z <= Layout.INNER.hi.z))
                    bad += "${l.id} apart stop $y $what $b"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** Fraction of the arrow's hit sphere (centre + 6 points at 0.7 r) the eye sees past the wood (piece boxes). */
    private fun visible(eye: V3, c: V3, boxes: List<Aabb>): Float {
        val r = Layout.ARROW_HIT_D / 2 * 0.7f
        val pts = listOf(c, c + V3(r, 0f, 0f), c - V3(r, 0f, 0f), c + V3(0f, r, 0f), c - V3(0f, r, 0f), c + V3(0f, 0f, r), c - V3(0f, 0f, r))
        return pts.count { p -> boxes.none { segHits(eye, p, it) } }.toFloat() / pts.size
    }

    private fun segHits(a: V3, b: V3, box: Aabb): Boolean {
        var t0 = 0f; var t1 = 1f
        for (ax in 0..2) {
            val d = b[ax] - a[ax]
            if (kotlin.math.abs(d) < 1e-9f) { if (a[ax] <= box.lo[ax] || a[ax] >= box.hi[ax]) return false; continue }
            var ta = (box.lo[ax] - a[ax]) / d; var tb = (box.hi[ax] - a[ax]) / d
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 >= t1) return false
        }
        return t1 > 0f && t0 < 1f
    }

    @Test fun arrowsBigEnoughSeparateAndVisible() {
        val bad = ArrayList<String>()
        for (li in 0..7) {
            val l = Joints.ALL[li]
            for (k in l.steps.indices) for (y in 0..3) {
                val st = pick(li, k, y)
                val f = Frames.frame(st, 10_000)
                val boxes = pieceBoxes(st, f)
                for (a in f.arrows) {
                    val ang = Layout.angleDeg(Layout.EYE, a.spot.pos, Layout.ARROW_HIT_D)
                    if (ang < 3f) bad += "${l.id} s$k stop $y arrow ${a.cand}: ${"%.2f".format(ang)}° < 3°"
                    val vis = visible(Layout.EYE, a.spot.pos, boxes)
                    if (vis < 0.7f) bad += "${l.id} s$k stop $y arrow ${a.cand}: only ${(vis * 100).toInt()} % visible"
                    if (y == 0 && a.spot.pos.z < 0f) bad += "${l.id} s$k arrow ${a.cand} not on the near side at the step's stop: z ${a.spot.pos.z}"
                }
                for (i in f.arrows.indices) for (j in i + 1 until f.arrows.size) {
                    val a = f.arrows[i].spot.pos; val b = f.arrows[j].spot.pos
                    val gap = Layout.sepDeg(Layout.EYE, a, b) - Layout.angleDeg(Layout.EYE, a, Layout.ARROW_HIT_D) / 2 - Layout.angleDeg(Layout.EYE, b, Layout.ARROW_HIT_D) / 2
                    if (gap < 2f) bad += "${l.id} s$k stop $y arrows $i/$j gap ${"%.2f".format(gap)}° < 2°"
                    if ((a - b).length < 0.12f) bad += "${l.id} s$k stop $y arrows $i/$j centres ${(a - b).length} m < 12 cm"
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun matsHoldTheirPieces() {
        for (l in Joints.ALL) for (p in l.movers) {
            val b = p.bounds.let { Aabb(it.lo * Layout.CM, it.hi * Layout.CM) }.transformed(Layout.matPose(l, p.id))
            val c = Layout.MAT_C[Layout.matOf(l, p.id)]
            assertTrue("${l.id} ${p.id} on the mat ${b.lo.y}", kotlin.math.abs(b.lo.y - Layout.MAT_TOP) < 1e-3f)
            assertTrue("${l.id} ${p.id} height", b.hi.y <= Layout.MAT_TOP + 0.0701f)
            assertTrue("${l.id} ${p.id} footprint $b", b.lo.x >= c.x - 0.1001f && b.hi.x <= c.x + 0.1001f && b.lo.z >= c.z - 0.0751f && b.hi.z <= c.z + 0.0751f)
        }
    }

    /** M10: nothing reaches the plaque (y 0.265 .. 0.315 at z = 0). */
    @Test fun plaqueClear() {
        for (li in 0..7) for (k in Joints.ALL[li].steps.indices) {
            val st = pick(li, k, 0)
            val f = Frames.frame(st, 10_000)
            for ((what, b) in Frames.boxes(st, f)) assertTrue("${Joints.ALL[li].id} $what ${b.hi.y}", b.hi.y < Layout.PLAQUE_C.y - Layout.PLAQUE_SIZE.y / 2 || kotlin.math.abs(b.centre.z) > 0.1f)
        }
    }

    @Test fun arrowFacesTheEye() {
        val l = Joints.ALL[0]
        val spot = Layout.arrowSpot(l, l.steps[0].cands[0], 10f, Layout.yawQ(0f))
        val q = Layout.arrowRot(spot)
        val x = q.rotate(V3(1f, 0f, 0f)); val z = q.rotate(V3(0f, 0f, 1f))
        assertTrue((x - spot.push).length < 1e-3f)
        assertTrue(z.dot((Layout.EYE - spot.pos).normalized()) > 0.5f)
    }
}
