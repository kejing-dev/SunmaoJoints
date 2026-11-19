package io.embodit.sunmaojoints.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemStore(var p: Progress = Progress(), var corrupt: Boolean = false) : ProgressStore {
    var saves = 0
    override fun load() = p to corrupt
    override fun save(p: Progress) { this.p = p; saves++ }
}

/** The state machine on a virtual clock (design states[] / stateModel.inputLock / M8 / M13). */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private fun TestScope.flow(store: MemStore = MemStore(), sounds: MutableList<Sound> = ArrayList()) =
        Flow(backgroundScope, { testScheduler.currentTime }, store, { sounds += it }).also { it.start(); runCurrent() }

    private fun TestScope.wait(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test fun firstRunGlowsAndGoesThrough() = runTest {
        val store = MemStore()
        val f = flow(store)
        assertEquals(Phase.CLOSED, f.state.value.phase)
        f.open(); f.build()
        assertTrue("S01", f.state.value.firstTrip && f.state.value.hint)
        f.tap(1) // blocked
        assertTrue(store.p.firstRunDone)
        wait(T.FLY + T.PUSH + 50)
        assertEquals(Phase.BLOCKED, f.state.value.phase)
        assertTrue("still S01 on this trip", f.state.value.firstTrip)
        wait(3000)
        assertEquals(Phase.PICK, f.state.value.phase)
        assertTrue(1 in f.state.value.tried)
        f.tap(0)
        wait(1100)
        assertEquals(Phase.DONE, f.state.value.phase)
        assertTrue(0 in store.p.done)
        assertFalse(f.state.value.firstTrip)
    }

    @Test fun inputLockIsUnderOneSecondAndBlockedFreesAtTheStop() = runTest {
        val f = flow(MemStore(Progress(firstRunDone = true)))
        f.gotoLesson(4); f.open(); f.build()
        f.tap(1) // halfB from above: blocked
        val p = f.state.value.push!!
        assertTrue(p.lockUntil - p.at <= 1000)
        wait(p.stopAt - testScheduler.currentTime + 10)
        assertEquals(Phase.BLOCKED, f.state.value.phase)
        assertTrue("S06: other arrows answer at once", f.canTap())
        f.tap(0) // interrupt with the right one
        assertEquals(Phase.PUSH, f.state.value.phase)
        assertTrue(f.state.value.ret != null)
        val q = f.state.value.push!!
        assertTrue(q.lockUntil - testScheduler.currentTime <= 1000 + T.QUICK_BACK)
        wait(1300)
        assertEquals(Phase.PICK, f.state.value.phase)
        assertEquals(1, f.state.value.step)
    }

    @Test fun trapThenHintAfterTwoMisses() = runTest {
        val sounds = ArrayList<Sound>()
        val f = flow(MemStore(Progress(firstRunDone = true)), sounds)
        f.gotoLesson(4); f.open(); f.build()
        f.tap(2) // pin first: trap
        wait(T.FLY + T.PUSH + 10)
        assertEquals(Phase.TRAP, f.state.value.phase)
        assertFalse(f.state.value.hint)
        wait(4000)
        assertEquals(Phase.PICK, f.state.value.phase)
        f.tap(1)
        wait(4000)
        assertTrue("M1 after two misses", f.state.value.hint)
        f.updateProgress { it.copy(hintsOn = false) }
        assertFalse(f.state.value.hint)
        assertTrue(Sound.BLOCK in sounds && Sound.SEAT in sounds && Sound.TRAY in sounds)
    }

    @Test fun allEightOnce() = runTest {
        val store = MemStore(Progress(firstRunDone = true, done = (0..6).toSet()))
        val f = flow(store)
        f.gotoLesson(7); f.open(); f.build()
        f.tap(0); wait(1200)
        f.tap(0); wait(1200)
        assertEquals(Phase.ALL_DONE, f.state.value.phase)
        f.nextLesson()
        assertEquals(0, f.state.value.lesson)
        f.open(); f.build(); f.tap(0); wait(1200)
        assertEquals("S10 only the first time", Phase.DONE, f.state.value.phase)
    }

    @Test fun quitAndNavigation() = runTest {
        val store = MemStore(Progress(firstRunDone = true))
        val f = flow(store)
        f.next(); f.next()
        assertEquals(2, f.state.value.lesson)
        assertEquals(2, store.p.lessonIndex)
        f.open(); f.build(); f.quit()
        assertEquals(Phase.CLOSED, f.state.value.phase)
        f.gotoLesson(7); f.next()
        assertEquals(7, f.state.value.lesson)
        f.gotoLesson(7); f.open(); f.build(); f.tap(0); wait(1200); f.tap(0); wait(1200)
        assertEquals(Phase.DONE, f.state.value.phase)
        f.nextLesson()
        assertEquals("the first one not learned yet", 0, f.state.value.lesson)
    }

    @Test fun settingsSettlesAndFreezes() = runTest {
        val f = flow(MemStore(Progress(firstRunDone = true)))
        f.open(); f.build(); f.tap(1)
        wait(100)
        f.setSettingsOpen(true)
        assertEquals(Phase.PICK, f.state.value.phase)
        assertTrue(1 in f.state.value.tried)
        assertFalse(f.canTap()); assertFalse(f.canTurn())
        f.turn()
        assertEquals(0, f.state.value.yaw)
        f.setSettingsOpen(false)
        f.turn()
        assertEquals(1, f.state.value.yaw)
    }

    @Test fun eachStepStartsAtItsStop() = runTest {
        val f = flow(MemStore(Progress(firstRunDone = true)))
        f.turn(); f.turn(); f.turn()
        f.open(); f.build()
        assertEquals(0, Math.floorMod(f.state.value.yaw, 4))
    }

    @Test fun corruptSaveStartsAtLessonOne() = runTest {
        val f = flow(MemStore(corrupt = true))
        assertTrue(f.state.value.corrupt)
        assertEquals(0, f.state.value.lesson)
        f.open()
        assertFalse(f.state.value.corrupt)
    }

    @Test fun codecRoundTripAndGarbage() {
        val p = Progress(5, setOf(0, 2, 7), true, false, true, Lang.EN)
        assertEquals(p, SaveCodec.decode(SaveCodec.encode(p)))
        assertEquals(Progress(), SaveCodec.decode(SaveCodec.encode(Progress())))
        for (bad in listOf("", "{", "{\"lessonIndex\":9}", "nonsense", "{\"v\":1,\"lessonIndex\":1,\"done\":[x]}")) {
            assertTrue(runCatching { SaveCodec.decode(bad) }.isFailure)
        }
    }

    @Test fun framesInEveryPhase() = runTest {
        val f = flow(MemStore(Progress(firstRunDone = true)))
        for (li in 0..7) {
            f.gotoLesson(li)
            assertEquals(Joints.ALL[li].name, Stage.frame(f.state.value, testScheduler.currentTime).plaque)
            f.open(); wait(1500)
            f.build()
            val fr = Stage.frame(f.state.value, testScheduler.currentTime + 500)
            assertEquals(Joints.ASK, fr.plaque)
            assertEquals(Joints.ALL[li].steps[0].cands.size, fr.arrows.size)
            for (k in Joints.ALL[li].steps.indices) { f.tap(Joints.ALL[li].steps[k].fitIndex); wait(1300) }
            assertTrue(f.state.value.phase == Phase.DONE || f.state.value.phase == Phase.ALL_DONE)
            val mid = Stage.frame(f.state.value, f.state.value.doneAt + (if (f.state.value.tug) T.TUG else 0) + 1000)
            assertEquals(0.35f, mid.wood, 1e-3f)
            assertEquals(Joints.LOCKED, mid.plaque)
            wait(8000)
        }
    }
}
