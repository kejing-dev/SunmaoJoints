package io.embodit.sunmaojoints.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Design acceptance.unitTests U1, U2, U3, U6, U8 and the counts in lessonRules.count. */
class JointsTest {
    private val size = 0.4f // 4 mm voxels (design U1)

    @Test fun counts() {
        val all = Joints.ALL
        assertEquals(8, all.size)
        assertEquals(13, all.sumOf { it.steps.size })
        val cands = all.flatMap { l -> l.steps.flatMap { it.cands } }
        assertEquals(29, cands.size)
        assertEquals(13, cands.count { it.outcome == Outcome.FITS })
        assertEquals(14, cands.count { it.outcome == Outcome.BLOCKED })
        assertEquals(2, cands.count { it.outcome == Outcome.TRAP })
        assertEquals(21, all.sumOf { it.pieces.size })
        assertTrue(all.all { it.pieces.size <= 3 && it.fixed.size == 1 })
    }

    /** U1: every candidate does what the design says, and stops where the table says (4 mm sweep vs the 2 mm table). */
    @Test fun u1SweepMatchesTable() {
        val bad = ArrayList<String>()
        for ((li, l) in Joints.ALL.withIndex()) for ((k, st) in l.steps.withIndex()) {
            val standing = Sweep.standing(l, k)
            for ((i, c) in st.cands.withIndex()) {
                val r = Sweep.push(l, c, standing, size)
                val row = JointTable.row(li, k, i)
                val ok = when (c.outcome) {
                    Outcome.FITS, Outcome.TRAP -> r.stopCm == 0f && row.stopCm == 0f
                    Outcome.BLOCKED -> r.stopCm > 0.3f && abs(r.stopCm - row.stopCm) <= 0.45f && row.contact != null
                }
                if (!ok) bad += "${l.id} s$k c$i ${c.piece} ${c.from} ${c.outcome}: sweep ${r.stopCm} table ${row.stopCm}"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** U2: each step has exactly one candidate that goes all the way in. */
    @Test fun u2OneFitPerStep() {
        for (l in Joints.ALL) for ((k, st) in l.steps.withIndex()) {
            val standing = Sweep.standing(l, k)
            val fits = st.cands.count { Sweep.push(l, it, standing, size).stopCm == 0f && it.outcome != Outcome.TRAP }
            assertEquals("${l.id} step $k", 1, fits)
            // every candidate comes from a different side (design spatialLayout.noOverlap)
            assertEquals(st.cands.size, st.cands.map { it.from }.toSet().size)
        }
    }

    /** U3: with the trap piece in, nothing of that step's right piece reaches home (and the table's bounce agrees). */
    @Test fun u3TrapBlocksTheNext() {
        var traps = 0
        for ((li, l) in Joints.ALL.withIndex()) for ((k, st) in l.steps.withIndex()) for ((i, c) in st.cands.withIndex()) {
            if (c.outcome != Outcome.TRAP) continue
            traps++
            val standing = Sweep.standing(l, k) + l.piece(c.piece)
            for (o in st.cands.filter { it.piece == st.fit.piece }) {
                assertTrue("${l.id} ${o.from} after trap", Sweep.push(l, o, standing, size).stopCm > 0.3f)
            }
            val b = Sweep.push(l, st.fit, standing, size)
            assertTrue(abs(b.stopCm - JointTable.row(li, k, i).bounceCm) <= 0.45f)
        }
        assertEquals(2, traps)
    }

    /** U8: assembled, no two pieces overlap (the tenons really sit in their mortises). */
    @Test fun u8NoOverlap() {
        val all = Joints.ALL.flatMap { Sweep.overlaps(it, size) }
        assertTrue(all.joinToString("\n"), all.isEmpty())
    }

    /** U6: plaque lines fit (zh <= 14 characters, en <= 30). */
    @Test fun u6SignLengths() {
        val texts = Joints.ALL.flatMap { l -> listOf(l.name) + l.steps.flatMap { s -> s.cands.mapNotNull { it.sign } } + listOfNotNull(l.tug) } +
            listOf(Joints.OK, Joints.LOCKED, Joints.ASK, Joints.ALL_DONE)
        for (t in texts) {
            assertTrue("zh ${t.zh}", t.zh.length <= 14)
            assertTrue("en ${t.en}", t.en.length <= 30)
        }
    }

    /** Pieces stay small enough for the mats (design E6: lying flat <= 20 x 14 cm, <= 7 cm tall). */
    @Test fun moversFitTheMats() {
        for (l in Joints.ALL) for (p in l.movers) {
            val s = p.bounds.size.let { listOf(it.x, it.y, it.z).sorted() }
            assertTrue("${l.id} ${p.id} $s", s[0] <= 7f && s[1] <= 14.01f && s[2] <= 20.01f)
        }
    }

    @Test fun jointsFitTheTurntable() {
        for (l in Joints.ALL) {
            val b = l.bounds
            assertTrue("${l.id} height ${b.size.y}", b.hi.y <= 26.01f)
        }
    }
}
