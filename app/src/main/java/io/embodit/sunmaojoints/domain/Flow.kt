package io.embodit.sunmaojoints.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Design states[] (S11–S14 are window pages / modifiers, see Ui and PlayState.corrupt). */
enum class Phase(val id: String) {
    LOADING("S00"), CLOSED("S02"), OPEN("S03"), PICK("S04"), PUSH("S05"), BLOCKED("S06"), TRAP("S07"), DONE("S09"), ALL_DONE("S10");
}

enum class Lang { AUTO, ZH, EN }

enum class Sound { SLIDE, SEAT, BLOCK, TRAY, LOCK, TURN, ALL_DONE }

/** Design dataModel.Progress (one JSON file, SaveCodec). */
data class Progress(
    val lessonIndex: Int = 0,
    val done: Set<Int> = emptySet(),
    val firstRunDone: Boolean = false,
    val hintsOn: Boolean = true,
    val soundOn: Boolean = true,
    val lang: Lang = Lang.AUTO,
)

/** One push in flight (S05 → S06 / S07 / S08). [from] = where the piece starts (null = its mat). */
data class PushAnim(
    val step: Int,
    val cand: Int,
    val piece: String,
    val outcome: Outcome,
    val entry: Float,
    val stop: Float,
    val bounce: Float,
    val at: Long,
) {
    val walkMs: Long get() = if (outcome == Outcome.BLOCKED) ((entry - stop) / Layout.ENTER_CM * T.PUSH).toLong().coerceAtLeast(60) else T.PUSH
    /** When the piece stops (blocked) or reaches home (fits / trap). */
    val stopAt: Long get() = at + T.FLY + walkMs
    /** Input is locked from the tap to here (design inputLock: <= 1 s). */
    val lockUntil: Long get() = if (outcome == Outcome.FITS) stopAt + T.REBOUND else stopAt
    val endAt: Long get() = when (outcome) {
        Outcome.FITS -> stopAt + T.REBOUND
        Outcome.BLOCKED -> stopAt + T.SHAKE + T.STAY + T.RETURN
        Outcome.TRAP -> stopAt + T.BOUNCE + T.TRAP_STAY + T.TRAP_OUT + T.TRAP_FLY
    }
}

/** An interrupted piece going back to its mat in 0.15 s from [from] (volume pose). */
data class RetAnim(val piece: String, val from: Pose, val at: Long)

/** Timings (design animation[] A1–A12), ms. */
object T {
    const val FLY = 350L
    const val PUSH = 500L
    const val REBOUND = 120L
    const val SHAKE = 180L
    const val STAY = 600L
    const val RETURN = 400L
    const val BOUNCE = 400L
    const val TRAP_STAY = 1200L
    const val TRAP_OUT = 300L
    const val TRAP_FLY = 200L
    const val QUICK_BACK = 150L
    const val TURN = 350L
    const val STEP_TURN = 400L
    const val ARROW_IN = 200L
    const val ARROW_STAGGER = 80L
    const val OPEN_EACH = 450L
    const val OPEN_STAGGER = 100L
    const val XRAY_IN = 300L
    const val XRAY_HOLD = 1600L
    const val XRAY_OUT = 300L
    const val SPIN = 4000L
    const val TUG = 450L
    const val OK_SHOW = 1200L
    const val LOCK_DELAY = 120L
    val XRAY: Long get() = XRAY_IN + XRAY_HOLD + XRAY_OUT
}

data class PlayState(
    val lesson: Int = 0,
    val phase: Phase = Phase.LOADING,
    val step: Int = 0,
    /** Arrows of this step already tried (shown dim, still tappable). */
    val tried: Set<Int> = emptySet(),
    /** Blocked / trap outcomes in this step (two -> hint, M1). */
    val misses: Int = 0,
    /** S01: lesson 1's first trip (the right arrow glows from the start). */
    val firstTrip: Boolean = false,
    val yaw: Int = 0,
    val yawFrom: Int? = null,
    val yawAt: Long = 0,
    val yawMs: Long = T.TURN,
    val push: PushAnim? = null,
    val ret: RetAnim? = null,
    /** Candidate whose reason is on the plaque and the window (S06 / S07). */
    val reason: Int? = null,
    val stepAt: Long = 0,
    val okAt: Long = 0,
    val openAt: Long = 0,
    val closeAt: Long = 0,
    val doneAt: Long = 0,
    val tug: Boolean = false,
    val corrupt: Boolean = false,
    val progress: Progress = Progress(),
    val settingsOpen: Boolean = false,
) {
    val lessonData: Lesson get() = Joints.ALL[lesson]
    val stepData: Step? get() = lessonData.steps.getOrNull(step)
    val lastStep: Boolean get() = step == lessonData.steps.lastIndex
    /** M1: the right arrow glows (S01 always; otherwise after two misses with hints on). */
    val hint: Boolean get() = firstTrip || (progress.hintsOn && misses >= 2)
    val learned: Boolean get() = lesson in progress.done
    /** Pieces already standing in this step (fixed + earlier steps' right pieces). */
    fun installed(): Set<String> = (0 until step).map { lessonData.steps[it].fit.piece }.toSet()
}

/**
 * The single source of truth (design stateModel). Every route — the window, the 3D arrows / turntable / badge, the
 * debug broadcast — calls these same methods (D29). Animations are data (start times); scheduled phase changes wait on
 * [clock] so a debug hold freezes everything at one frame.
 */
class Flow(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val store: ProgressStore,
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private var job: Job? = null
    private var turnJob: Job? = null
    @Volatile var frozenAt: Long? = null
        private set

    /** Animation time: the clock, or the frozen frame during a debug hold. */
    fun now(): Long = frozenAt ?: clock()

    private fun set(f: (PlayState) -> PlayState) { _state.value = f(_state.value) }

    fun start() {
        val (p, corrupt) = store.load()
        set { it.copy(progress = p, lesson = p.lessonIndex.coerceIn(0, 7), phase = Phase.CLOSED, corrupt = corrupt) }
        log("FLOW start lesson=${p.lessonIndex} done=${p.done} corrupt=$corrupt")
    }

    private fun save(p: Progress) { set { it.copy(progress = p) }; store.save(p) }

    fun updateProgress(change: (Progress) -> Progress) = save(change(_state.value.progress))

    private suspend fun waitUntil(t: Long) {
        while (true) { val left = t - now(); if (left <= 0) return; delay(minOf(left, 40L)) }
    }

    private fun schedule(block: suspend () -> Unit) { job?.cancel(); job = scope.launch { block() } }

    val inputLocked: Boolean get() = _state.value.push?.let { now() < it.lockUntil } ?: false

    // ---------------------------------------------------------------- lesson navigation (window)

    private fun clearCorrupt() { if (_state.value.corrupt) set { it.copy(corrupt = false) } }

    fun gotoLesson(i: Int) {
        job?.cancel()
        val l = i.coerceIn(0, 7)
        set { it.copy(lesson = l, phase = Phase.CLOSED, step = 0, push = null, ret = null, reason = null, tried = emptySet(), misses = 0, firstTrip = false, tug = false) }
        save(_state.value.progress.copy(lessonIndex = l))
        log("FLOW lesson ${l + 1} S02")
    }

    fun prev() { clearCorrupt(); if (_state.value.lesson > 0) gotoLesson(_state.value.lesson - 1) }
    fun next() { clearCorrupt(); if (_state.value.lesson < 7) gotoLesson(_state.value.lesson + 1) }

    /** T3 拉开看看 (S02 → S03). */
    fun open() {
        clearCorrupt()
        if (_state.value.phase != Phase.CLOSED) return
        set { it.copy(phase = Phase.OPEN, openAt = now()) }
        onSound(Sound.SLIDE)
        log("FLOW open S03")
    }

    /** T4 合上 (S03 → S02). */
    fun close() {
        if (_state.value.phase != Phase.OPEN) return
        set { it.copy(phase = Phase.CLOSED, closeAt = now()) }
        onSound(Sound.SLIDE)
        log("FLOW close S02")
    }

    /** T5 我来拼 (S03 → S04 step 1, or S01 on lesson 1's first time). */
    fun build() {
        val st = _state.value
        if (st.phase != Phase.OPEN && st.phase != Phase.DONE && st.phase != Phase.ALL_DONE) return
        job?.cancel()
        val first = st.lesson == 0 && !st.progress.firstRunDone
        set { it.copy(phase = Phase.PICK, step = 0, push = null, ret = null, reason = null, tried = emptySet(), misses = 0, firstTrip = first, stepAt = now(), okAt = 0, tug = false) }
        log("FLOW build ${if (first) "S01" else "S04"} lesson ${st.lesson + 1}")
        autoTurn()
    }

    /** T9 再拼一次. */
    fun again() = build()

    /** T6 不拼了 (no confirm, gate B decision 6). */
    fun quit() {
        val st = _state.value
        if (st.phase !in setOf(Phase.PICK, Phase.PUSH, Phase.BLOCKED, Phase.TRAP)) return
        job?.cancel()
        if (st.firstTrip) markFirstRun()
        set { it.copy(phase = Phase.CLOSED, step = 0, push = null, ret = null, reason = null, tried = emptySet(), misses = 0, firstTrip = false, closeAt = 0) }
        log("FLOW quit S02")
    }

    /** T8 下一种 (S09) / 从第一课再看 (S10). */
    fun nextLesson() {
        val st = _state.value
        if (st.phase == Phase.ALL_DONE) { gotoLesson(0); return }
        if (st.phase != Phase.DONE) return
        val n = if (st.lesson < 7) st.lesson + 1 else (0..7).firstOrNull { it !in st.progress.done } ?: 0
        gotoLesson(n)
    }

    private fun markFirstRun() { if (!_state.value.progress.firstRunDone) save(_state.value.progress.copy(firstRunDone = true)) }

    // ---------------------------------------------------------------- turntable (T2) and the step stop (A1)

    fun canTurn(): Boolean {
        val st = _state.value
        return !st.settingsOpen && !inputLocked && st.phase != Phase.LOADING
    }

    fun turn() {
        if (!canTurn()) return
        turnTo(_state.value.yaw + 1, T.TURN)
        onSound(Sound.TURN)
        log("FLOW turn -> stop ${Math.floorMod(_state.value.yaw, 4)}")
    }

    private fun turnTo(stop: Int, ms: Long) {
        val st = _state.value
        if (stop == st.yaw) return
        set { it.copy(yawFrom = st.yaw, yaw = stop, yawAt = now(), yawMs = ms) }
    }

    /** Each step starts at its stop (every step's candidates are authored at stop 0: 上 / 右前 / 左前). */
    private fun autoTurn() {
        val st = _state.value
        val m = Math.floorMod(st.yaw, 4)
        if (m == 0) return
        // the shorter way back to stop 0
        val target = if (m <= 2) st.yaw - m else st.yaw + (4 - m)
        turnTo(target, T.STEP_TURN)
    }

    // ---------------------------------------------------------------- arrows (T1)

    fun canTap(): Boolean {
        val st = _state.value
        return !st.settingsOpen && st.phase in setOf(Phase.PICK, Phase.BLOCKED, Phase.TRAP) && !inputLocked
    }

    /** T1: push candidate [i] of the current step. */
    fun tap(i: Int) {
        val st = _state.value
        val step = st.stepData ?: return
        val c = step.cands.getOrNull(i) ?: return
        if (!canTap()) { log("FLOW tap $i ignored phase=${st.phase} locked=$inputLocked"); return }
        if (st.firstTrip) markFirstRun()
        val row = JointTable.row(st.lesson, st.step, i)
        val t0 = now()
        // S06 / S07: the piece out there goes back to its mat in 0.15 s first
        var ret: RetAnim? = null
        var at = t0
        st.push?.let { p ->
            ret = RetAnim(p.piece, Frames.pushPose(st.lessonData, p, t0, Frames.yawQ(st, t0)), t0)
            at = t0 + T.QUICK_BACK
        }
        val push = PushAnim(st.step, i, c.piece, c.outcome, Layout.entryCm(row, c), row.stopCm, row.bounceCm, at)
        set { it.copy(phase = Phase.PUSH, push = push, ret = ret, reason = null) }
        log("FLOW tap ${i} ${c.piece} from=${c.from} -> ${c.outcome} stop=${row.stopCm}")
        schedule {
            waitUntil(at)
            onSound(Sound.SLIDE)
            when (c.outcome) {
                Outcome.FITS -> {
                    waitUntil(push.stopAt)
                    onSound(Sound.SEAT)
                    val last = _state.value.lastStep
                    if (last) {
                        val before = _state.value.progress.done
                        val doneNow = before + st.lesson
                        save(_state.value.progress.copy(done = doneNow))
                        waitUntil(push.stopAt + T.LOCK_DELAY)
                        onSound(Sound.LOCK)
                        waitUntil(push.lockUntil)
                        val all = before.size == 7 && doneNow.size == 8
                        val tug = _state.value.lessonData.tug != null
                        set { it.copy(phase = if (all) Phase.ALL_DONE else Phase.DONE, push = null, ret = null, doneAt = now(), tug = tug, firstTrip = false, reason = null) }
                        log("FLOW lesson ${st.lesson + 1} ${if (all) "S10 all eight" else "S09 done"}")
                        if (all) { delay(T.XRAY); onSound(Sound.ALL_DONE) }
                    } else {
                        waitUntil(push.lockUntil)
                        set { it.copy(phase = Phase.PICK, step = it.step + 1, push = null, ret = null, tried = emptySet(), misses = 0, stepAt = now(), okAt = now()) }
                        log("FLOW S08 -> step ${_state.value.step + 1}")
                        autoTurn()
                    }
                }
                Outcome.BLOCKED -> {
                    waitUntil(push.stopAt)
                    onSound(Sound.BLOCK)
                    set { it.copy(phase = Phase.BLOCKED, reason = i, tried = it.tried + i, misses = it.misses + 1) }
                    log("FLOW S06 blocked at ${row.stopCm} cm: ${c.sign?.zh}")
                    waitUntil(push.endAt)
                    onSound(Sound.TRAY)
                    set { it.copy(phase = Phase.PICK, push = null, ret = null) }
                }
                Outcome.TRAP -> {
                    waitUntil(push.stopAt)
                    onSound(Sound.SEAT)
                    set { it.copy(phase = Phase.TRAP, reason = i, tried = it.tried + i, misses = it.misses + 1) }
                    log("FLOW S07 trap: ${c.sign?.zh}")
                    waitUntil(push.stopAt + T.BOUNCE / 2)
                    onSound(Sound.BLOCK)
                    waitUntil(push.endAt)
                    onSound(Sound.TRAY)
                    set { it.copy(phase = Phase.PICK, push = null, ret = null) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- window pages

    /** T10: the settings page freezes the volume; whatever is playing jumps to its end (design S11). */
    fun setSettingsOpen(open: Boolean) {
        if (open) settle()
        set { it.copy(settingsOpen = open) }
    }

    /** Jump the push in flight to its end state at once. */
    fun settle() {
        val st = _state.value
        val p = st.push ?: return
        job?.cancel()
        val c = st.stepData?.cands?.getOrNull(p.cand) ?: return
        when (c.outcome) {
            Outcome.FITS -> if (st.lastStep) {
                val before = st.progress.done
                save(st.progress.copy(done = before + st.lesson))
                val all = before.size == 7 && (before + st.lesson).size == 8
                set { it.copy(phase = if (all) Phase.ALL_DONE else Phase.DONE, push = null, ret = null, doneAt = 1, tug = false, firstTrip = false, reason = null) }
            } else set { it.copy(phase = Phase.PICK, step = it.step + 1, push = null, ret = null, tried = emptySet(), misses = 0, stepAt = 0, okAt = 0) }
            else -> set { it.copy(phase = Phase.PICK, push = null, ret = null, tried = it.tried + p.cand, misses = if (p.cand in it.tried) it.misses else it.misses + 1, reason = p.cand) }
        }
        log("FLOW settle -> ${_state.value.phase}")
    }

    fun onBackground() = settle()

    // ---------------------------------------------------------------- debug (same calls as the window, plus holds)

    fun freeze(at: Long?) { frozenAt = at; log("FLOW freeze $at") }

    fun markAllDone() = save(_state.value.progress.copy(done = (0..7).toSet()))

    fun setPhaseForDebug(phase: Phase) = set { it.copy(phase = phase, doneAt = now()) }
}

object Ease {
    fun inOut(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3 - 2 * u) }
    fun outQuart(t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return 1f - u * u * u * u }
    fun outCubic(t: Float): Float { val u = 1f - t.coerceIn(0f, 1f); return 1f - u * u * u }
    fun inQuad(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u }
    fun outBack(t: Float): Float { val u = t.coerceIn(0f, 1f) - 1f; val c = 1.70158f; return 1f + (c + 1) * u * u * u + c * u * u }
}

/** Storage port: the platform implements it with a file (SaveCodec), tests with memory. */
interface ProgressStore {
    /** The saved progress and whether the file was there but unreadable (S14). */
    fun load(): Pair<Progress, Boolean>
    fun save(p: Progress)
}

/** design dataModel.storage: one small JSON object, written by hand (org.json is not on the unit-test classpath). */
object SaveCodec {
    fun encode(p: Progress): String =
        "{\"v\":1,\"lessonIndex\":${p.lessonIndex},\"done\":[${p.done.sorted().joinToString(",")}],\"firstRunDone\":${p.firstRunDone}," +
            "\"hintsOn\":${p.hintsOn},\"soundOn\":${p.soundOn},\"lang\":\"${p.lang.name.lowercase()}\"}"

    fun decode(s: String): Progress {
        val t = s.trim()
        require(t.startsWith("{") && t.endsWith("}")) { "not an object" }
        fun raw(k: String): String = Regex("\"$k\"\\s*:\\s*(\\[[^]]*]|\"[^\"]*\"|[^,}]+)").find(t)?.groupValues?.get(1)?.trim() ?: error("missing $k")
        fun bool(k: String) = when (raw(k)) { "true" -> true; "false" -> false; else -> error("bad $k") }
        val lesson = raw("lessonIndex").toInt()
        require(lesson in 0..7)
        val done = raw("done").removePrefix("[").removeSuffix("]").split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toSet()
        require(done.all { it in 0..7 })
        val lang = Lang.valueOf(raw("lang").trim('"').uppercase())
        return Progress(lesson, done, bool("firstRunDone"), bool("hintsOn"), bool("soundOn"), lang)
    }
}
