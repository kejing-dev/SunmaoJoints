package io.embodit.sunmaojoints.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.sunmaojoints.domain.Joints
import io.embodit.sunmaojoints.domain.Layout
import io.embodit.sunmaojoints.domain.Mat
import io.embodit.sunmaojoints.domain.V3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

private fun c4(argb: Long, a: Float = 1f) = Color4(((argb shr 16) and 255) / 255f, ((argb shr 8) and 255) / 255f, (argb and 255) / 255f, a)

/** Builds a MeshResource from [MeshData] (bounds from the positions). */
internal fun meshOf(name: String, d: MeshData): MeshResource {
    var lo = V3(1e9f, 1e9f, 1e9f); var hi = V3(-1e9f, -1e9f, -1e9f)
    for (p in d.pos) { lo = lo.min(p); hi = hi.max(p) }
    val c = (lo + hi) * 0.5f
    val h = (hi - lo) * 0.5f
    return MeshResource.createWithMeshModel(
        model = MeshModel(
            positions = d.pos.map { Vector3(it.x, it.y, it.z) }, triangleIndices = d.idx, normals = d.nrm.map { Vector3(it.x, it.y, it.z) },
            uv0 = d.uv.map { Vector2(it[0], it[1]) },
        ),
        bounds = BoundingBox(center = Vector3(c.x, c.y, c.z), halfExtent = Vector3(maxOf(h.x, 1e-3f), maxOf(h.y, 1e-3f), maxOf(h.z, 1e-3f))),
        name = name,
    )
}

/** One piece's meshes (null part = none of that material) and its wood. */
internal class PieceMesh(val wood: MeshResource, val woodMat: Mat, val gold: MeshResource?, val red: MeshResource?, val triangles: Int)

/**
 * Everything the volume draws, made once off the main thread (creating a resource waits on the renderer; factory
 * memory note "dynamic mesh ANR"): the 21 pieces of all eight joints, the turntable, mats, block, plaque, arrows,
 * badge and every material (design palette).
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()
    fun <T : Resource> keep(r: T): T { r.toGlobal(); synchronized(all) { all += r }; return r }
    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    /** pieces[lesson][pieceId] */
    lateinit var pieces: List<Map<String, PieceMesh>>; private set
    lateinit var cube: MeshResource; private set
    lateinit var drum: MeshResource; private set
    lateinit var badgeDisc: MeshResource; private set
    lateinit var arrow: MeshResource; private set
    lateinit var plaqueQuad: MeshResource; private set
    lateinit var badgeQuad: MeshResource; private set
    lateinit var shadowQuad: MeshResource; private set

    lateinit var woodLight: Material; private set
    lateinit var woodDark: Material; private set
    lateinit var gold: Material; private set
    lateinit var red: Material; private set
    lateinit var ghostLight: Material; private set
    lateinit var ghostDark: Material; private set
    lateinit var ghostGold: Material; private set
    lateinit var ghostRed: Material; private set
    lateinit var table: Material; private set
    lateinit var mat: Material; private set
    lateinit var block: Material; private set
    lateinit var plaqueBoard: Material; private set
    lateinit var arrowMat: Material; private set
    lateinit var arrowHi: Material; private set
    lateinit var arrowDim: Material; private set
    lateinit var flash: Material; private set
    lateinit var badge: Material; private set
    lateinit var badgeIcon: Material; private set
    lateinit var shadow: Material; private set

    lateinit var physics: PhysicsMaterialResource; private set
    lateinit var arrowShape: ShapeResource; private set
    lateinit var turntableShape: ShapeResource; private set
    lateinit var badgeShape: ShapeResource; private set

    private val textMats = HashMap<String, Material>()

    fun ghostOf(m: Mat): Material = when (m) { Mat.LIGHT -> ghostLight; Mat.DARK -> ghostDark; Mat.GOLD -> ghostGold; Mat.RED -> ghostRed }
    fun woodOf(m: Mat): Material = if (m == Mat.DARK) woodDark else woodLight

    /** The plaque's line as a material (cached; call off the main thread: design text3d, ivory on walnut). */
    fun plaqueText(text: String): Material = synchronized(textMats) {
        textMats.getOrPut(text) { unlit(0xFFFFFFFF, tex = keep(TextureResource.create(plaqueBitmap(text)))) .let { keep(it) } }
    }

    companion object {
        private fun unlit(argb: Long, opacity: Float = 1f, tex: TextureResource? = null, cull: MaterialCullingMode = MaterialCullingMode.BACK): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f || tex != null) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(c4(argb))
                if (opacity < 1f) setOpacity(opacity)
                tex?.let { setBaseColorTexture(it) }
                setCullingMode(cull)
                setApplyToneMapping(false)
            }

        private fun pbr(argb: Long, rough: Float, tex: TextureResource? = null, opacity: Float = 1f, emissive: Color4? = null) =
            PhysicallyBasedMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(c4(argb)); tex?.let { setBaseColorTexture(it) }; setRoughness(rough); setMetallic(0f)
                if (opacity < 1f) setOpacity(opacity)
                emissive?.let { setEmissiveColor(it) }
            }

        /** The plaque line: centred, ivory #F4E9D8, 62 px on a 1024 x 116 canvas (0.44 x 0.05 m: cap height about 2.8 cm). */
        fun plaqueBitmap(text: String): Bitmap {
            val b = Bitmap.createBitmap(1024, 116, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.rgb(0xF4, 0xE9, 0xD8); textSize = 62f; textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            while (p.measureText(text) > 1000f && p.textSize > 30f) p.textSize -= 2f
            val fm = p.fontMetrics
            Canvas(b).drawText(text, 512f, 58f - (fm.ascent + fm.descent) / 2, p)
            return b
        }

        /** The badge face: a white "window" mark on transparent (design E9: 窗口图标). */
        fun badgeBitmap(): Bitmap {
            val n = 128
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f }
            c.drawRoundRect(RectF(30f, 36f, 98f, 92f), 10f, 10f, p)
            p.style = Paint.Style.FILL
            c.drawRect(30f, 36f, 98f, 52f, p)
            return b
        }

        fun shadowBitmap(): Bitmap {
            val n = 128
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(0x80000000.toInt(), 0x38000000, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) }
            Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
            return b
        }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T = r.keep(this)
            val io = Dispatchers.IO
            val tex = listOf(WoodTexture.BEECH to 7, WoodTexture.WALNUT to 13, WoodTexture.TABLE to 29, WoodTexture.MAT to 41, WoodTexture.BLOCK to 53)
                .map { (t, s) -> async(io) { val bmp = WoodTexture.build(t, s); TextureResource.create(bmp).keep().also { bmp.recycle() } } }
            val shadowTex = async(io) { TextureResource.create(shadowBitmap()).keep() }
            val badgeTex = async(io) { TextureResource.create(badgeBitmap()).keep() }
            listOf(
                async(io) {
                    r.pieces = Joints.ALL.map { l ->
                        l.pieces.associate { pc ->
                            val parts = MeshGen.piece(pc)
                            pc.id to PieceMesh(
                                meshOf("${l.id}.${pc.id}", parts.wood).keep(), parts.woodMat,
                                if (parts.gold.empty) null else meshOf("${l.id}.${pc.id}.gold", parts.gold).keep(),
                                if (parts.red.empty) null else meshOf("${l.id}.${pc.id}.red", parts.red).keep(),
                                parts.triangles,
                            )
                        }
                    }
                },
                async(io) { r.cube = meshOf("cube", MeshGen.cube()).keep() },
                async(io) { r.drum = meshOf("turntable", MeshGen.drum(Layout.TABLE_R, 0.10f, 48)).keep() },
                async(io) { r.badgeDisc = meshOf("badge", MeshGen.drum(Layout.BADGE_D / 2, 0.008f, 32)).keep() },
                async(io) { r.arrow = meshOf("arrow", MeshGen.arrow()).keep() },
                async(io) { r.plaqueQuad = meshOf("plaque", MeshGen.quadXY(Layout.PLAQUE_SIZE.x, Layout.PLAQUE_SIZE.y)).keep() },
                async(io) { r.badgeQuad = meshOf("badgeIcon", MeshGen.quadXY(0.06f, 0.06f)).keep() },
                async(io) { r.shadowQuad = meshOf("shadow", MeshGen.quadXZ(0.34f, 0.34f)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
                // gate B decision 4: every arrow's hit sphere is 11 cm (>= 3° from the seated eye, LayoutTest)
                async(io) { r.arrowShape = ShapeResource.createSphere(Layout.ARROW_HIT_D / 2).keep() },
                async(io) { r.badgeShape = ShapeResource.createSphere(0.045f).keep() },
            ).awaitAll()
            r.turntableShape = runCatching { ShapeResource.createConvexMesh(r.drum).keep() }
                .getOrElse { ShapeResource.createBox(Vector3(0.34f, 0.10f, 0.34f)).keep() }
            val (beech, walnut, tableTex, matTex, blockTex) = tex.awaitAll()
            r.woodLight = pbr(0xFFFFFFFF, 0.62f, beech).keep()
            r.woodDark = pbr(0xFFFFFFFF, 0.6f, walnut).keep()
            // tenon gold / mortise vermilion (design palette E3: 传统描金、朱漆), a touch of glow so they read in the x-ray
            r.gold = pbr(0xFFE3B341, 0.45f, emissive = Color4(0.18f, 0.12f, 0.02f, 1f)).keep()
            r.red = pbr(0xFFC8452C, 0.55f, emissive = Color4(0.14f, 0.03f, 0.01f, 1f)).keep()
            r.ghostLight = pbr(0xFFD9B88A, 0.6f, opacity = 0.35f).keep()
            r.ghostDark = pbr(0xFF6B4A32, 0.6f, opacity = 0.35f).keep()
            r.ghostGold = pbr(0xFFE3B341, 0.5f, opacity = 0.35f).keep()
            r.ghostRed = pbr(0xFFC8452C, 0.5f, opacity = 0.35f).keep()
            r.table = pbr(0xFFFFFFFF, 0.6f, tableTex).keep()
            r.mat = pbr(0xFFFFFFFF, 0.7f, matTex).keep()
            r.block = pbr(0xFFFFFFFF, 0.6f, blockTex).keep()
            r.plaqueBoard = pbr(0xFF3A2A1E, 0.6f).keep()
            // stone teal, the only colour you can tap (design palette); hover / hint #3FB5B0
            r.arrowMat = unlit(0xFF1F8A8A).keep()
            r.arrowHi = unlit(0xFF3FB5B0).keep()
            r.arrowDim = unlit(0xFF2C6463).keep()
            r.flash = unlit(0xFFFF8A1F).keep()
            r.badge = unlit(0xFF1F8A8A).keep()
            r.badgeIcon = unlit(0xFFFFFFFF, tex = badgeTex.await()).keep()
            r.shadow = unlit(0xFFFFFFFF, tex = shadowTex.await(), cull = MaterialCullingMode.NONE).keep()
            r
        }
    }
}
