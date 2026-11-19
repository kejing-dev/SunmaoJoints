package io.embodit.sunmaojoints.ui.board

import io.embodit.sunmaojoints.domain.Joints
import io.embodit.sunmaojoints.domain.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mesh budget (design entities3d / budget: <= 400 triangles a piece, <= 1,200 a joint) and the triangulator. */
class MeshGenTest {
    @Test fun budget() {
        for (l in Joints.ALL) {
            var total = 0
            for (pc in l.pieces) {
                val n = MeshGen.piece(pc).triangles
                assertTrue("${l.id} ${pc.id} $n triangles", n <= 400)
                total += n
            }
            assertTrue("${l.id} $total triangles", total <= 1200)
        }
    }

    @Test fun nonConvexTriangulates() {
        // the middle layer of 粽角榫 rail B (a pocket cut out of the footprint)
        val poly = listOf(-3.5f to -3.5f, -1.5f to -1.5f, -1.5f to 0f, 0.5f to 2f, 0.5f to 0.5f, 3.5f to 3.5f, 3.5f to 16f, -3.5f to 16f)
        val t = MeshGen.triangulate(poly)
        assertEquals(poly.size - 2, t.size)
        var area = 0f
        for ((a, b, c) in t) {
            val (ax, ay) = poly[a]; val (bx, by) = poly[b]; val (cx, cy) = poly[c]
            area += kotlin.math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2
        }
        var shoelace = 0f
        for (i in poly.indices) { val (x0, y0) = poly[i]; val (x1, y1) = poly[(i + 1) % poly.size]; shoelace += x0 * y1 - x1 * y0 }
        assertEquals(kotlin.math.abs(shoelace) / 2, area, 1e-3f)
    }

    @Test fun cubeFacesPointOut() {
        val m = MeshGen.cube()
        assertEquals(12, m.triangles)
        for (i in 0 until m.triangles) {
            val a = m.pos[m.idx[3 * i]]; val b = m.pos[m.idx[3 * i + 1]]; val c = m.pos[m.idx[3 * i + 2]]
            val n = (b - a).cross(c - a)
            val centroid = (a + b + c) * (1f / 3)
            assertTrue(n.dot(centroid - V3.ZERO) > 0f)
        }
    }
}
