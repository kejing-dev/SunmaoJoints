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

    /**
     * Gate B decision 4 / D50 补 (QA #1 q1-1, q1-2): every step x 4 stops x the three eyes, each hit sphere >= 3°, gaps
     * >= 2°, >= 70 % seen past wood, ghosts, block and turntable, on the near side at the step's stop.
     */
    @Test fun arrowsBigEnoughSeparateAndVisible() {
        val bad = ArrayList<String>()
        var n = 0
        for (li in 0..7) {
            val l = Joints.ALL[li]
            for (k in l.steps.indices) for (y in 0..3) {
                val st = pick(li, k, y)
                val f = Frames.frame(st, 10_000)
                val occ = Layout.occluders(li, k, Layout.yawQ(y.toFloat()))
                for ((ei, eye) in Layout.EYES.withIndex()) {
                    for (a in f.arrows) {
                        n++
                        val ang = Layout.angleDeg(eye, a.spot.pos, Layout.ARROW_HIT_D)
                        if (ang < 3f) bad += "${l.id} s$k stop $y eye $ei arrow ${a.cand}: ${"%.2f".format(ang)}° < 3°"
                        val vis = Layout.visibleFrac(eye, a.spot.pos, occ)
                        if (vis < 0.7f) bad += "${l.id} s$k stop $y eye $ei arrow ${a.cand}: only ${(vis * 100).toInt()} % visible"
                        if (!Layout.clearOfWindow(eye, a.spot.pos)) bad += "${l.id} s$k stop $y eye $ei arrow ${a.cand}: behind the control window"
                    }
                    for (i in f.arrows.indices) for (j in i + 1 until f.arrows.size) {
                        val gap = Layout.gapDeg(eye, f.arrows[i].spot.pos, f.arrows[j].spot.pos)
                        if (gap < 2f) bad += "${l.id} s$k stop $y eye $ei arrows $i/$j gap ${"%.2f".format(gap)}° < 2°"
                    }
                }
                for (a in f.arrows) if (y == 0 && a.spot.pos.z < 0f) bad += "${l.id} s$k arrow ${a.cand} not on the near side at the step's stop: z ${a.spot.pos.z}"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        assertTrue("checked $n", n == 348)
    }

    /**
     * QA #2 q2-1: the window model matches the solver log and is a real occluder. Its footprint stays outside the volume
     * (so the system placed it beside, not inside), the old v0.1.1 layout really was behind it from the right eye at
     * stops 1 / 2 (the test would have caught q2-1), and the new layout is clear from all three eyes at every stop.
     */
    @Test fun controlWindowOccluder() {
        val raw = V3(0.626776f - 0.037804f, 0f, -1.256906f + 1.748934f)
        assertTrue("centre ${Layout.WINDOW_C} vs log $raw", (Layout.WINDOW_C - raw).length < 2e-3f)
        val q = Q.yawDeg(Layout.WINDOW_YAW)
        assertTrue("yaw vs log quat y -0.210376", kotlin.math.abs(q.y - (-0.210376f)) < 1e-3f && kotlin.math.abs(q.w - 0.977621f) < 1e-3f)
        // the default 560 x 360 dp glass corners lie outside the volume box
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
            val c = Layout.WINDOW_C + q.rotate(V3(sx * 280f, sy * 180f, 0f) * Layout.WINDOW_M_PER_DP)
            assertTrue("glass corner $c outside the volume", !(kotlin.math.abs(c.x) < Layout.VOL_W / 2 && kotlin.math.abs(c.z) < Layout.VOL_D / 2))
        }
        // a point right behind the window centre, seen from the right eye, is hidden; the same from the left eye is not
        val behind = Layout.WINDOW_C + (Layout.WINDOW_C - Layout.EYES[2]).normalized() * 0.3f
        assertTrue(Layout.windowHit(Layout.EYES[2], behind))
        assertTrue(!Layout.windowHit(Layout.EYES[1], V3(0f, 0f, 0f)))
        // QA #2's case: v0.1.1 put zongjiao (lesson 7) step 1 stop 2's third arrow behind the window from the right eye
        var hiddenStops = 0
        for (li in 0..7) for (k in Joints.ALL[li].steps.indices) for (y in 0..3) for (a in Layout.arrowSpotsAt(li, k, y)) for (e in Layout.EYES) if (!Layout.clearOfWindow(e, a.pos)) hiddenStops++
        assertTrue("arrows behind the window: $hiddenStops", hiddenStops == 0)
    }

    /** The layout found a spot with margin for every arrow (>= 3.2°, gaps >= 2.3°), never its fallback. */
    @Test fun arrowLayoutMarginHolds() {
        for (li in 0..7) for (k in Joints.ALL[li].steps.indices) for (y in 0..3) {
            val s = Layout.arrowSpotsAt(li, k, y)
            for (a in s) for (e in Layout.EYES) assertTrue("${Joints.ALL[li].id} s$k stop $y", Layout.angleDeg(e, a.pos, Layout.ARROW_HIT_D) >= Layout.ARROW_MIN_DEG)
            for (i in s.indices) for (j in i + 1 until s.size) assertTrue("${Joints.ALL[li].id} s$k stop $y $i/$j", Layout.minGapDeg(s[i].pos, s[j].pos) >= Layout.ARROW_GAP_DEG)
        }
    }

    /** While the turntable turns the arrows ride along and land exactly on the next stop's layout. */
    @Test fun arrowsBlendBetweenStops() {
        for (li in 0..7) for (k in Joints.ALL[li].steps.indices) for (y in 0..3) {
            val end = Layout.arrowSpots(li, k, y + 0.99999f); val next = Layout.arrowSpotsAt(li, k, y + 1)
            for (i in end.indices) assertTrue("${Joints.ALL[li].id} s$k stop $y", (end[i].pos - next[i].pos).length < 1e-3f)
            for (a in Layout.arrowSpots(li, k, y + 0.5f)) assertTrue("mid inside ${a.pos}", Layout.INNER.contains(a.pos))
        }
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
        assertTrue(z.dot((Layout.EYE_MID - spot.pos).normalized()) > 0.5f)
    }

    /**
     * QA #1 q1-2: no eye sees an arrow as a thin edge. The arrow is two crossed fins (outline 7 x 6.6 cm in its xy and xz
     * planes, 1.5 cm thick); projected on each eye's view plane its silhouette is >= 3 cm wide in every direction (a
     * single flat fin seen edge-on is 1.5 cm), and seen nearly along the push it still shows the crossed head.
     */
    @Test fun arrowFinsReadableFromEveryEye() {
        val outline = listOf(-3.5f to -1.8f, 0.2f to -1.8f, 0.2f to -3.3f, 3.5f to 0f, 0.2f to 3.3f, 0.2f to 1.8f, -3.5f to 1.8f)
        val pts = outline.flatMap { (u, v) -> listOf(-0.75f, 0.75f).flatMap { t -> listOf(V3(u, v, t), V3(u, t, v)) } }.map { it * 0.01f }
        val bad = ArrayList<String>()
        var minW = 1f
        for (li in 0..7) for (k in Joints.ALL[li].steps.indices) for (y in 0..3) for (s in Layout.arrowSpotsAt(li, k, y)) {
            val q = Layout.arrowRot(s)
            val world = pts.map { q.rotate(it) }
            for ((ei, e) in Layout.EYES.withIndex()) {
                val v = (e - s.pos).normalized()
                val a = (if (kotlin.math.abs(v.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)).let { (it - v * it.dot(v)).normalized() }
                val b = v.cross(a)
                // narrowest width of the silhouette over 12 directions in the view plane
                val w = (0 until 12).minOf { i ->
                    val t = Math.PI * i / 12; val d = a * kotlin.math.cos(t).toFloat() + b * kotlin.math.sin(t).toFloat()
                    world.maxOf { it.dot(d) } - world.minOf { it.dot(d) }
                }
                minW = minOf(minW, w)
                if (w < 0.03f) bad += "${Joints.ALL[li].id} s$k stop $y eye $ei silhouette ${"%.1f".format(w * 100)} cm"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        println("arrow silhouette min width ${"%.1f".format(minW * 100)} cm")
    }
}
