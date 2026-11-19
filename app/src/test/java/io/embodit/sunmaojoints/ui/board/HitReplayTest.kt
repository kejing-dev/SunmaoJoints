package io.embodit.sunmaojoints.ui.board

import io.embodit.sunmaojoints.domain.Flow
import io.embodit.sunmaojoints.domain.Joints
import io.embodit.sunmaojoints.domain.MemStore
import io.embodit.sunmaojoints.domain.Phase
import io.embodit.sunmaojoints.domain.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Board-input template 必带单测 (build.md 命中回放): a real hit lands on the deepest mesh; walking up its parents must
 * reach the holder, and the holder must drive the same flow call as the window / debug broadcast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    @Test fun pathsResolveToHolders() {
        assertEquals("arrow:0", BoardTargets.resolvePath(listOf("arrow:0:mesh", "arrow:0", "content", "root")))
        assertEquals("arrow:2", BoardTargets.resolvePath(listOf("arrow:2", "content", "root")))
        assertEquals("turntable", BoardTargets.resolvePath(listOf("turntable:mesh", "turntable", "content", "root")))
        assertEquals("badge", BoardTargets.resolvePath(listOf("badge:icon", "badge", "content", "root")))
        assertEquals("badge", BoardTargets.resolvePath(listOf("badge:disc", "badge", "content", "root")))
        // pieces, ghosts, the plaque and the mats are not targets: a ray on them resolves to nothing
        assertNull(BoardTargets.resolvePath(listOf("rail:wood", "piece:rail", "content", "root")))
        assertNull(BoardTargets.resolvePath(listOf("rail:wood", "ghost:0:inner", "ghost:0:pivot", "ghost:0", "content", "root")))
        assertNull(BoardTargets.resolvePath(listOf("plaqueText", "content", "root")))
        assertNull(BoardTargets.resolvePath(listOf("arrow:0:mesh:extra")))
    }

    @Test fun everyArrowOfEveryStepDrivesItsCandidate() = runTest {
        for ((li, l) in Joints.ALL.withIndex()) for ((k, st) in l.steps.withIndex()) for (i in st.cands.indices) {
            val flow = Flow(backgroundScope, { testScheduler.currentTime }, MemStore(Progress(firstRunDone = true)))
            flow.start(); runCurrent()
            flow.gotoLesson(li); flow.open(); flow.build()
            for (s in 0 until k) { routeFlowCall(flow, BoardTargets.resolvePath(listOf("arrow:${l.steps[s].fitIndex}:mesh", "arrow:${l.steps[s].fitIndex}"))); testScheduler.advanceTimeBy(1300); runCurrent() }
            assertEquals(k, flow.state.value.step)
            routeFlowCall(flow, BoardTargets.resolvePath(listOf("arrow:$i:mesh", "arrow:$i", "content")))
            val p = flow.state.value.push
            assertEquals("${l.id} s$k c$i", i, p?.cand)
            assertEquals(st.cands[i].piece, p?.piece)
        }
    }

    @Test fun turntableTurnsAndLockedTapsAreIgnored() = runTest {
        val flow = Flow(backgroundScope, { testScheduler.currentTime }, MemStore(Progress(firstRunDone = true)))
        flow.start(); runCurrent()
        routeFlowCall(flow, "turntable")
        assertEquals(1, flow.state.value.yaw)
        flow.open(); flow.build()
        routeFlowCall(flow, "arrow:0")
        routeFlowCall(flow, "arrow:1") // locked: ignored
        assertEquals(0, flow.state.value.push?.cand)
        routeFlowCall(flow, "turntable") // locked: ignored
        assertEquals(Phase.PUSH, flow.state.value.phase)
    }
}
