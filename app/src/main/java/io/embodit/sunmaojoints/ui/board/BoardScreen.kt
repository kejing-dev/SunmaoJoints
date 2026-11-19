package io.embodit.sunmaojoints.ui.board

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.sunmaojoints.R
import io.embodit.sunmaojoints.domain.Flow
import io.embodit.sunmaojoints.domain.PlayState
import io.embodit.sunmaojoints.platform.Graph
import io.embodit.sunmaojoints.platform.TAG
import io.embodit.sunmaojoints.ui.board.input.BoardInputConfig
import io.embodit.sunmaojoints.ui.board.input.BoardInputListener
import io.embodit.sunmaojoints.ui.board.input.BoardInputLog
import io.embodit.sunmaojoints.ui.board.input.InputSource
import io.embodit.sunmaojoints.ui.board.input.TargetResolver
import io.embodit.sunmaojoints.ui.board.input.boardInput
import io.embodit.sunmaojoints.ui.control.exitApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.embodit.sunmaojoints.domain.Joints
import kotlin.math.abs

/** Runs the volume's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame()
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
}

/**
 * The display-case volume (default container). Opens the control window beside it through the level gate, shows the
 * turntable and the joint, and routes every pinch / trigger on an arrow, the turntable or the badge to [Flow] through
 * the factory board-input template (D50: hand ray, controller ray and eye-hand alike). Tap only (gate B decision 4).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val zh = stringResource(R.string.lang_code) == "zh"
    scene.zh = zh
    scene.now = { graph.flow.now() }
    scene.badgeWanted = !graph.window.panelOpen && graph.window.panelState != "never"
    scene.make = { job -> graph.scope.launch(Dispatchers.IO) { runCatching { job() }.onFailure { Log.w(TAG, "SCENE make failed: $it") } } }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    if (graph.window.panelState == "ON_DESTROY") graph.window.request("resume")
                }
                // design container.lifecycle: the volume closed by the system [x] ends the app (both containers, D44)
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> {
                    graph.window.onVolumeLifecycle("ON_DESTROY")
                    if (!graph.window.exiting) { Log.i(TAG, "VOLUME closed -> exit"); exitApp(context, graph) }
                }
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> graph.window.onVolumeLifecycle("ON_CREATE")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .boardInput(remember { BoardTargets() }, remember(graph, scene) { BoardListener(graph, scene) }, BoardInputConfig(dragStartM = 0.03f, logTag = TAG)),
        initial = { content, _ ->
            content.addEntity(scene.root)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val first = graph.flow.state.value.lesson
            val res = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create(first) }
            withContext(NonCancellable + Dispatchers.IO) { res.plaqueText(Joints.ALL[first].name.of(zh)); res.plaqueText(" ") }
            scene.install(res)
            Log.i(TAG, "STARTUP scene ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            // the rest in the background, one at a time: this lesson's lines, the other lessons, every other line
            // (2026-11-05 R2: no texture or mesh is made on the main thread at the first tap)
            graph.scope.launch(Dispatchers.IO) {
                val l = Joints.ALL[first]
                for (t in listOf(Joints.ASK, Joints.OK, Joints.LOCKED) + l.steps.flatMap { st -> st.cands.mapNotNull { it.sign } }) res.plaqueText(t.of(zh))
                for (li in Joints.ALL.indices) res.lesson(li)
                Prewarm.plaque(res, zh)
                Log.i(TAG, "STARTUP background warm-up done in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            }
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state)
        },
    )
}

/** Makes every plaque line's texture once, off the main thread. */
internal object Prewarm {
    fun plaque(res: SceneResources, zh: Boolean) {
        val all = io.embodit.sunmaojoints.domain.Joints.ALL.flatMap { l -> listOf(l.name) + l.steps.flatMap { s -> s.cands.mapNotNull { it.sign } } + listOfNotNull(l.tug) } +
            listOf(io.embodit.sunmaojoints.domain.Joints.OK, io.embodit.sunmaojoints.domain.Joints.LOCKED, io.embodit.sunmaojoints.domain.Joints.ASK, io.embodit.sunmaojoints.domain.Joints.ALL_DONE)
        for (t in all.distinct()) res.plaqueText(t.of(zh))
        res.plaqueText(" ")
    }
}

/** Hit entity (or an ancestor) -> the holder name the flow knows (arrows, turntable, badge). */
internal class BoardTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { BoardNames.hitOf(it) != BoardNames.NoHit }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.sunmaojoints.ui.board.input.resolvePath(path, ::targetOf)

        /** What a target stands for in the input log (`square=`). */
        fun squareOf(target: String?, st: PlayState): String = when (val h = BoardNames.hitOf(target)) {
            is BoardNames.ArrowHit -> st.stepData?.cands?.getOrNull(h.cand)?.let { "arrow${h.cand}:${it.piece}:${it.from}" } ?: "-"
            BoardNames.TurntableHit -> "turntable"
            BoardNames.BadgeHit -> "badge"
            BoardNames.NoHit -> "-"
        }
    }
}

/** Every resolved tap goes through [routeFlow] (the same calls the window and the debug broadcast make). */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<String> {
    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value)}")
        routeFlow(graph, target)
    }

    /** Tap only (gate B decision 4: 只点箭头，不捏住拖): a press that travels is still one tap on what it started on. */
    // gate:allow shared-drag 拖动不移动任何东西，只当成点一下（箭头 / 转台 / 圆牌）；门 B 用户定「不拖」，容器是设计包定的 Shared Space 体积
    override fun onDragStart(target: String, source: InputSource): Boolean {
        BoardInputLog.write(TAG, "INPUT drag-as-tap src=${source.label} target=$target -> square=${BoardTargets.squareOf(target, graph.flow.state.value)}")
        routeFlow(graph, target)
        return true
    }

    /** D50 hover: a hint only, never a state change. */
    override fun onHover(target: String?, source: InputSource) { scene.setHover(target) }
}

/** Holder name -> the one flow call (hit-replay unit tests drive it through [routeFlowCall]). */
internal fun routeFlow(graph: Graph, name: String?) {
    if (BoardNames.hitOf(name) == BoardNames.BadgeHit) { Log.i(TAG, "S13 badge -> reopen the control window"); graph.window.request("badge"); return }
    routeFlowCall(graph.flow, name)
}

internal fun routeFlowCall(flow: Flow, name: String?) {
    when (val h = BoardNames.hitOf(name)) {
        is BoardNames.ArrowHit -> flow.tap(h.cand)
        BoardNames.TurntableHit -> flow.turn()
        BoardNames.BadgeHit, BoardNames.NoHit -> Unit
    }
}
