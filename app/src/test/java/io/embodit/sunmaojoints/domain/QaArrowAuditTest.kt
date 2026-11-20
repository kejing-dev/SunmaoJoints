package io.embodit.sunmaojoints.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QA round 1's angle audit (qa/round-1/QaArrowAuditTest.kt.txt), kept as written and now asserted (q1-1 / q1-2): every
 * arrow, every step, every turntable stop, from all three eyes. Hit sphere >= 3°, gaps >= 2°, centre not behind wood,
 * block, turntable, mini, plaque or the control window (v0.1.2, QA #2 q2-1: no sample point of any sphere behind the
 * window), >= 70 % of the sphere visible. The table goes to build/qa-arrow-audit.tsv.
 */
class QaArrowAuditTest {
    private fun inBox(p: V3, b: Aabb) = p.x > b.lo.x && p.x < b.hi.x && p.y > b.lo.y && p.y < b.hi.y && p.z > b.lo.z && p.z < b.hi.z
    private fun inDrum(p: V3) = p.y > Layout.TURNTABLE_C.y - 0.05f && p.y < Layout.TABLE_Y && (p.x * p.x + p.z * p.z) < Layout.TABLE_R * Layout.TABLE_R
    /**
     * QA #2 q2-1 (v0.1.2): the control window, from the raw solver log (WorldSpaceNative onShowSolveTransform):
     * SunmaoControl (0.626776, -0.061074, -1.256906) quat(0.977621, {0, -0.210376, 0}), SunmaoJoints
     * (0.037804, -0.061074, -1.748934). Footprint: the largest window 640 x 460 dp at 0.0008 m/dp, the TabBar capsule
     * 90 dp above, the system row 60 dp below, 3 cm deep (no margin here; Layout keeps 2 cm more).
     */
    private val winC = V3(0.626776f - 0.037804f, 0f, -1.256906f + 1.748934f)
    private val winQ = Q(0f, -0.210376f, 0f, 0.977621f)
    private val winX = winQ.rotate(V3(1f, 0f, 0f)); private val winZ = winQ.rotate(V3(0f, 0f, 1f))
    private fun inWindow(q: V3): Boolean {
        val d = q - winC; val x = d.dot(winX); val y = d.y; val z = d.dot(winZ)
        return kotlin.math.abs(x) < 0.256f && y > -0.232f && y < 0.256f && kotlin.math.abs(z) < 0.03f
    }
    /** What the eye->p segment passes through (sampled every 2 mm, the last 1 cm skipped so the arrow's own sphere is not counted). */
    private fun blocker(eye: V3, p: V3, boxes: List<Pair<String, Aabb>>): String? {
        val d = p - eye; val len = d.length; val n = (len / 0.002f).toInt()
        for (i in 1 until n) {
            val t = i.toFloat() / n
            if ((1 - t) * len < 0.01f) break
            val q = eye + d * t
            if (inDrum(q)) return "turntable"
            if (inWindow(q)) return "window"
            for ((w, b) in boxes) if (inBox(q, b)) return w
        }
        return null
    }
    @Test fun audit() {
        val out = StringBuilder("lesson\tstep\tstop\teye\tarrow\tcand\tangle\tminGap\tcentreBlocker\tvisFrac\n")
        var minAng = 99f; var minGap = 99f; var centreBlocked = 0; var total = 0; var minVis = 1f; var windowTouched = 0
        val eyes = listOf("front", "left30", "right30")
        for (li in 0..7) { val l = Joints.ALL[li]
            for (k in l.steps.indices) for (y in 0..3) {
                val st = PlayState(lesson = li, phase = Phase.PICK, step = k, yaw = y, stepAt = 0)
                val f = Frames.frame(st, 10_000)
                val boxes = Frames.boxes(st, f).filter { it.first.startsWith("piece") }.toMutableList()
                f.block?.let { boxes += "block" to Aabb(it.pos - V3(0.06f, 0.02f, 0.06f), it.pos + V3(0.06f, 0.02f, 0.06f)) }
                // v0.1.2: the 1:1 mini and the plaque count as well (D50 补)
                if (f.mini) boxes += "mini" to Aabb(l.bounds.lo * (Layout.CM * Layout.MINI_K), l.bounds.hi * (Layout.CM * Layout.MINI_K)).transformed(Pose(Layout.MINI_C, Q.yawDeg(-45f)))
                boxes += "plaque" to Aabb(Layout.PLAQUE_C - Layout.PLAQUE_SIZE * 0.5f, Layout.PLAQUE_C + Layout.PLAQUE_SIZE * 0.5f)
                for ((ei, eye) in Layout.EYES.withIndex()) for ((ai, a) in f.arrows.withIndex()) {
                    val c = a.spot.pos
                    val ang = Layout.angleDeg(eye, c, Layout.ARROW_HIT_D)
                    var g = 99f
                    for ((bi, b) in f.arrows.withIndex()) if (bi != ai) g = minOf(g, Layout.sepDeg(eye, c, b.spot.pos) - ang / 2 - Layout.angleDeg(eye, b.spot.pos, Layout.ARROW_HIT_D) / 2)
                    val cb = blocker(eye, c, boxes)
                    val r = Layout.ARROW_HIT_D / 2 * 0.7f
                    val pts = listOf(c, c + V3(r, 0f, 0f), c - V3(r, 0f, 0f), c + V3(0f, r, 0f), c - V3(0f, r, 0f), c + V3(0f, 0f, r), c - V3(0f, 0f, r))
                    val vis = pts.count { blocker(eye, it, boxes) == null }.toFloat() / pts.size
                    if (pts.any { blocker(eye, it, boxes) == "window" }) windowTouched++
                    total++; if (cb != null) centreBlocked++
                    minAng = minOf(minAng, ang); minGap = minOf(minGap, g); minVis = minOf(minVis, vis)
                    out.append("${l.id}\t$k\t$y\t${eyes[ei]}\t$ai\t${a.cand}\t${"%.2f".format(ang)}\t${"%.2f".format(g)}\t${cb ?: "-"}\t${"%.2f".format(vis)}\n")
                }
            }
        }
        run { val st = PlayState(lesson = 0, phase = Phase.PICK, step = 0, yaw = 0, stepAt = 0); val f = Frames.frame(st, 10_000); val bx = Frames.boxes(st, f).filter { it.first.startsWith("piece") }; out.append("# sanity boxes ${bx.size} centreOfFirstPiece blocker=${blocker(V3(0f,0.061f,1.749f), bx[0].second.centre + V3(0f,0f,-0.5f), bx)} drum=${blocker(V3(0f,0.061f,1.749f), V3(0f,-0.30f,-0.3f), bx)}\n") }
        out.append("# total $total  minAngle ${"%.2f".format(minAng)}  minGap ${"%.2f".format(minGap)}  centreBlocked $centreBlocked  windowTouched $windowTouched  minVisFrac ${"%.2f".format(minVis)}\n")
        File(System.getProperty("user.dir"), "build").mkdirs()
        File(System.getProperty("user.dir"), "build/qa-arrow-audit.tsv").writeText(out.toString())
        assertEquals("13 steps x 4 stops x 3 eyes", 13, Joints.ALL.sumOf { it.steps.size })
        assertEquals("arrows x stops x eyes", 348, total)
        assertTrue("min angle $minAng", minAng >= 3f)
        assertTrue("min gap $minGap", minGap >= 2f)
        assertEquals("centres blocked", 0, centreBlocked)
        assertTrue("min visible $minVis", minVis >= 0.7f)
        assertEquals("sample points behind the control window (q2-1)", 0, windowTouched)
    }
}
