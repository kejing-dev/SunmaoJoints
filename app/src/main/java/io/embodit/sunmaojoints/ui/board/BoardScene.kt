package io.embodit.sunmaojoints.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.sunmaojoints.domain.Joints
import io.embodit.sunmaojoints.domain.Layout
import io.embodit.sunmaojoints.domain.Phase
import io.embodit.sunmaojoints.domain.PlayState
import io.embodit.sunmaojoints.domain.Pose
import io.embodit.sunmaojoints.domain.Q
import io.embodit.sunmaojoints.domain.Frames
import io.embodit.sunmaojoints.domain.V3
import io.embodit.sunmaojoints.platform.TAG
import io.embodit.sunmaojoints.ui.board.input.GazeTargets
import kotlin.math.PI

/**
 * Every entity of the volume (design spatialLayout). Compose hands over [PlayState] through [apply]; everything that
 * moves is placed in [onFrame] from [Frames.frame] (ECS, not Compose).
 *
 * ```
 * root -> content (scaled with a user resize of the volume)
 * ├ keyLight, turntable (holder "turntable": collider + Interactable + HoverEffect), contact shadow, block, 2 mats
 * ├ piece:<id>   wood / gold / red children (no collider: pieces are not tapped, design E2)
 * ├ ghost:<i>    35 % copies of candidate i's piece (pivot child scales about its centre), no collider
 * ├ arrow:<i>    12 cm hit sphere + Interactable + HoverEffect, the opaque teal arrow as its child (board-input)
 * ├ flash        orange contact plate (E10)
 * ├ plaque board + its line (no collider: never stops a ray, design P2)
 * ├ mini (0.4x, the life-size one) / minis (S10: eight at 0.25x on the mats)
 * └ badge        "open the control window" (S13 only)
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val content = Entity().apply { setName("content") }
    private var res: SceneResources? = null

    private class PieceEnt(val holder: Entity, val wood: ModelEntity, var fading: Boolean = false)

    private var builtLesson = -1
    private var builtStep = -1
    private val pieces = HashMap<String, PieceEnt>()
    private val ghosts = HashMap<Int, Pair<Entity, Entity>>() // holder, pivot
    private val arrows = ArrayList<Entity>()
    private val arrowModels = ArrayList<ModelEntity>()
    private var turntable: Entity? = null
    private var block: Entity? = null
    private var flash: ModelEntity? = null
    private var plaqueText: ModelEntity? = null
    private var plaqueShown: String? = null
    private var mini: Entity? = null
    private val minis = ArrayList<Entity>()
    private var badge: Entity? = null

    private var state = PlayState()
    @Volatile var badgeWanted = false
    @Volatile var zh = true
    @Volatile private var hover: String? = null
    @Volatile private var hoverAt = 0L
    var now: () -> Long = { SystemClock.uptimeMillis() }
    /** Debug evidence: where each arrow stood in the last frame. */
    val lastArrows = HashMap<Int, V3>()
    var lastFrame: Frames.Frame? = null; private set

    init { root.addChild(content) }

    val ready: Boolean get() = res != null

    fun setHover(name: String?) {
        hoverAt = SystemClock.uptimeMillis()
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    private var regionScale = 1f

    /** The volume's real half height (m): content follows a uniform resize (design container.volume: Uniform). */
    fun setRegion(halfY: Float) {
        if (halfY < 0.05f) return
        val k = (halfY / (Layout.VOL_H / 2)).coerceIn(0.3f, 4f)
        if (kotlin.math.abs(k - regionScale) < 1e-3f) return
        regionScale = k
        content.transform().setScaleVector(Vector3(k, k, k))
        Log.i(TAG, "SCENE region halfY=$halfY scale=$k")
    }

    private fun place(e: Entity, p: Pose) {
        e.transform().setPosition(Vector3(p.pos.x, p.pos.y, p.pos.z))
        e.transform().setQuaternion(Quat(p.rot.x, p.rot.y, p.rot.z, p.rot.w))
    }

    private fun slab(name: String, mat: Material, c: V3, s: V3): ModelEntity {
        val r = res!!
        return ModelEntity(r.cube, mat).apply {
            setName(name); content.addChild(this)
            transform().setPosition(Vector3(c.x, c.y, c.z)); transform().setScaleVector(Vector3(s.x, s.y, s.z))
        }
    }

    fun install(r: SceneResources) {
        if (res != null) { r.close(); return }
        res = r
        // design lighting: a warm directional light from the upper right front (45°), moderate (B24)
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0.93f, 0.84f, 1f), 5500f, false))
            val q = Q.axisAngle(V3(0f, 1f, 0f), (45 * PI / 180).toFloat()) * Q.axisAngle(V3(1f, 0f, 0f), (-45 * PI / 180).toFloat())
            light.transform().setQuaternion(Quat(q.x, q.y, q.z, q.w))
            content.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
        // turntable: the drum is the click shape and the highlighted mesh (board-input holder)
        turntable = Entity().apply { setName(BoardNames.TURNTABLE) }.also { h ->
            val m = ModelEntity(r.drum, r.table).apply { setName("turntable:mesh") }
            GazeTargets.install(h, r.turntableShape, r.physics, m)
            content.addChild(h)
            h.transform().setPosition(Vector3(Layout.TURNTABLE_C.x, Layout.TURNTABLE_C.y, Layout.TURNTABLE_C.z))
        }
        ModelEntity(r.shadowQuad, r.shadow).apply { setName("shadow"); content.addChild(this); transform().setPosition(Vector3(0f, Layout.TABLE_Y + 0.001f, 0f)) }
        for ((i, c) in Layout.MAT_C.withIndex()) slab("mat:$i", r.mat, c, Layout.MAT_SIZE)
        block = slab("block", r.block, V3(0f, Layout.TABLE_Y + Layout.BLOCK_H / 2, 0f), V3(0.12f, Layout.BLOCK_H, 0.08f)).apply { enabled = false }
        slab("plaqueBoard", r.plaqueBoard, Layout.PLAQUE_C - V3(0f, 0f, Layout.PLAQUE_SIZE.z / 2), Layout.PLAQUE_SIZE)
        plaqueText = ModelEntity(r.plaqueQuad, r.cachedText(" ") ?: r.plaqueBoard).apply {
            setName("plaqueText"); content.addChild(this)
            transform().setPosition(Vector3(Layout.PLAQUE_C.x, Layout.PLAQUE_C.y, 0.0012f))
        }
        flash = slab("flash", r.flash, V3.ZERO, V3(0.01f, 0.01f, 0.01f)).apply { enabled = false }
        for (i in 0 until 3) {
            val h = Entity().apply { setName(BoardNames.arrow(i)); enabled = false }
            val m = ModelEntity(r.arrow, r.arrowMat).apply { setName("arrow:$i:mesh") }
            GazeTargets.install(h, r.arrowShape, r.physics, m)
            content.addChild(h)
            arrows += h; arrowModels += m
        }
        badge = Entity().apply { setName(BoardNames.BADGE); enabled = false }.also { h ->
            val disc = ModelEntity(r.badgeDisc, r.badge).apply { setName("badge:disc"); transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (PI / 2).toFloat())) }
            GazeTargets.install(h, r.badgeShape, r.physics, disc)
            h.addChild(ModelEntity(r.badgeQuad, r.badgeIcon).apply { setName("badge:icon"); transform().setPosition(Vector3(0f, 0f, 0.0045f)) })
            content.addChild(h)
            h.transform().setPosition(Vector3(Layout.BADGE_C.x, Layout.BADGE_C.y, Layout.BADGE_C.z))
        }
        Log.i(TAG, "SCENE shared resources in")
        onFrame()
    }

    /** Wood / gold / red meshes of piece [id] of lesson [li] under [parent]; returns the wood entity. */
    private fun addPieceModels(parent: Entity, li: Int, id: String, ghost: Boolean = false): ModelEntity {
        val r = res!!
        val m = r.piecesOf(li)!![id]!!
        val wood = ModelEntity(m.wood, if (ghost) r.ghostOf(m.woodMat) else r.woodOf(m.woodMat)).apply { setName("$id:wood") }
        parent.addChild(wood)
        m.gold?.let { parent.addChild(ModelEntity(it, if (ghost) r.ghostGold else r.gold).apply { setName("$id:gold") }) }
        m.red?.let { parent.addChild(ModelEntity(it, if (ghost) r.ghostRed else r.red).apply { setName("$id:red") }) }
        return wood
    }

    /** S10: the eight joints at 0.25x on the mats (made once every lesson's meshes are in). */
    private fun buildMinis() {
        val r = res ?: return
        if (minis.isNotEmpty() || !r.allLoaded) return
        for (li in Joints.ALL.indices) {
            val g = Entity().apply { setName("minis:$li"); enabled = false }
            for (pc in Joints.ALL[li].pieces) addPieceModels(g, li, pc.id)
            place(g, Layout.miniAllPose(li))
            g.transform().setScaleVector(Vector3(Layout.MINI_ALL_K, Layout.MINI_ALL_K, Layout.MINI_ALL_K))
            content.addChild(g); minis += g
        }
    }

    private fun build(li: Int) {
        pieces.values.forEach { it.holder.removeFromParent() }; pieces.clear()
        mini?.removeFromParent()
        val l = Joints.ALL[li]
        for (pc in l.pieces) {
            val h = Entity().apply { setName(BoardNames.piece(pc.id)) }
            val wood = addPieceModels(h, li, pc.id)
            content.addChild(h)
            pieces[pc.id] = PieceEnt(h, wood)
        }
        mini = Entity().apply { setName("mini") }.also { g ->
            for (pc in l.pieces) addPieceModels(g, li, pc.id)
            place(g, Pose(Layout.MINI_C, Q.yawDeg(-45f)))
            g.transform().setScaleVector(Vector3(Layout.MINI_K, Layout.MINI_K, Layout.MINI_K))
            content.addChild(g)
        }
        builtLesson = li
        builtStep = -1
        Log.i(TAG, "SCENE lesson ${li + 1} ${l.id} built")
    }

    private fun buildGhosts(li: Int, step: Int) {
        ghosts.values.forEach { it.first.removeFromParent() }; ghosts.clear()
        builtStep = step
        val st = Joints.ALL[li].steps.getOrNull(step) ?: return
        for ((i, c) in st.cands.withIndex()) {
            val h = Entity().apply { setName("ghost:$i"); enabled = false }
            val pivot = Entity().apply { setName("ghost:$i:pivot") }
            val inner = Entity().apply { setName("ghost:$i:inner") }
            addPieceModels(inner, li, c.piece, ghost = true)
            val ctr = Joints.ALL[li].piece(c.piece).bounds.centre * Layout.CM
            pivot.transform().setPosition(Vector3(ctr.x, ctr.y, ctr.z))
            inner.transform().setPosition(Vector3(-ctr.x, -ctr.y, -ctr.z))
            pivot.addChild(inner); h.addChild(pivot); content.addChild(h)
            ghosts[i] = h to pivot
        }
    }

    /** Background maker for resources the frame needs but are not in yet (set by BoardScreen). */
    var make: ((suspend () -> Unit) -> Unit)? = null
    private val asked = HashSet<String>()

    private fun want(li: Int) { val r = res ?: return; if (asked.add("lesson:$li")) make?.invoke { r.lesson(li) } }
    private fun wantText(t: String) { val r = res ?: return; if (asked.add("text:$t")) make?.invoke { r.plaqueText(t) } }

    fun apply(next: PlayState) {
        state = next
        onFrame()
    }

    fun onFrame() {
        val r = res ?: return
        val st = state
        val t = now()
        if (st.lesson != builtLesson) {
            if (r.piecesOf(st.lesson) == null) { want(st.lesson); return }
            build(st.lesson)
        }
        val stepKey = if (st.phase in setOf(Phase.PICK, Phase.PUSH, Phase.BLOCKED, Phase.TRAP)) st.step else -1
        if (stepKey != builtStep) buildGhosts(st.lesson, stepKey)
        val f = Frames.frame(st, t)
        lastFrame = f

        // turntable turns with the yaw (its grain shows the turn); block turns with the joint
        turntable?.transform()?.setQuaternion(Quat(f.yaw.x, f.yaw.y, f.yaw.z, f.yaw.w))
        block?.let { b -> f.block?.let { place(b, it); b.enabled = true } ?: run { b.enabled = false } }

        val shown = f.pieces.associateBy { it.id }
        for ((id, pe) in pieces) {
            val v = shown[id]
            pe.holder.enabled = v != null
            if (v != null) place(pe.holder, v.pose)
            // 透木 (signature): wood fades, gold / red stay solid; D24: remove the opacity component at 1.0
            if (f.wood < 0.999f) { pe.wood.components.set(OpacityControllerComponent(f.wood.coerceIn(0.05f, 1f))); pe.fading = true }
            else if (pe.fading) { pe.wood.components.remove(OpacityControllerComponent::class.java); pe.fading = false }
        }
        for ((i, g) in ghosts) {
            val v = f.ghosts.firstOrNull { it.cand == i }
            g.first.enabled = v != null && v.scale > 0.01f
            if (v != null) {
                place(g.first, v.pose)
                val s = v.scale.coerceAtLeast(0.01f)
                g.second.transform().setScaleVector(Vector3(s, s, s))
            }
        }
        val hoverArrow = hover?.takeIf { t - hoverAt < 1500 }?.let { BoardNames.arrowOf(it) }
        lastArrows.clear()
        for (i in arrows.indices) {
            val a = f.arrows.firstOrNull { it.cand == i }
            arrows[i].enabled = a != null
            if (a == null) continue
            lastArrows[i] = a.spot.pos
            place(arrows[i], Pose(a.spot.pos, a.rot))
            val hov = hoverArrow == i && f.live
            val s = a.scale * (if (hov) 1.08f else 1f)
            arrowModels[i].transform().setScaleVector(Vector3(s, s, s))
            val mat = when { a.glow || hov -> r.arrowHi; a.dim -> r.arrowDim; else -> r.arrowMat }
            arrowModels[i].components[ModelComponent::class.java]?.materials?.set(0, mat)
        }
        flash?.let { fl ->
            val fv = f.flash
            fl.enabled = fv != null
            if (fv != null) { place(fl, fv.pose); fl.transform().setScaleVector(Vector3(fv.size.x, fv.size.y, fv.size.z)) }
        }
        val line = f.plaque?.of(zh) ?: " "
        if (line != plaqueShown) {
            val mat = r.cachedText(line)
            if (mat != null) {
                plaqueShown = line
                plaqueText?.components?.get(ModelComponent::class.java)?.materials?.set(0, mat)
            } else wantText(line)
        }
        if (f.minisAll) buildMinis()
        mini?.enabled = f.mini
        minis.forEach { it.enabled = f.minisAll }
        badge?.enabled = badgeWanted
    }

    /** Debug hit replay (board-input template): the holder and its deepest child. */
    fun replayEntities(target: String): List<Entity> {
        val out = ArrayList<Entity>()
        fun deepest(e: Entity): Entity { var x = e; while (x.getChildren().isNotEmpty()) x = x.getChildren().first(); return x }
        fun visit(e: Entity) {
            if (runCatching { e.getName() }.getOrNull() == target && e.enabled) { out += e; deepest(e).takeIf { it !== e }?.let { out += it } }
            e.getChildren().forEach { visit(it) }
        }
        visit(root)
        return out
    }

    fun evidence(): String {
        val f = lastFrame
        return "lesson=${state.lesson + 1} phase=${state.phase.id} step=${state.step + 1} yaw=${state.yaw} scale=$regionScale " +
            "arrows=${lastArrows.entries.joinToString { "${it.key}@${it.value}" }} plaque=${f?.plaque?.zh} wood=${f?.wood} badge=$badgeWanted"
    }

    fun dispose() {
        res?.close()
        res = null
    }

}

/** Every holder name (one place). */
object BoardNames {
    const val TURNTABLE = "turntable"
    const val BADGE = "badge"
    fun arrow(i: Int) = "arrow:$i"
    fun piece(id: String) = "piece:$id"

    sealed interface Hit
    data class ArrowHit(val cand: Int) : Hit
    data object TurntableHit : Hit
    data object BadgeHit : Hit
    data object NoHit : Hit

    fun hitOf(name: String?): Hit = when {
        name == null -> NoHit
        name == TURNTABLE -> TurntableHit
        name == BADGE -> BadgeHit
        name.startsWith("arrow:") && name.count { it == ':' } == 1 -> name.substringAfter(':').toIntOrNull()?.takeIf { it in 0..2 }?.let { ArrowHit(it) } ?: NoHit
        else -> NoHit
    }

    fun arrowOf(name: String): Int? = (hitOf(name) as? ArrowHit)?.cand
}
