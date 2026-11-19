package io.embodit.sunmaojoints.ui.board

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural wood (design budget.textures: 程序条纹 when no CC0 scan is used): flat-sawn streaks running along u (the
 * piece's long axis, MeshGen), ring arcs drifting slowly, fine pores. 512 x 512, tileable along u and v.
 */
internal object WoodTexture {
    data class Tone(val early: Int, val late: Int, val rings: Float, val pores: Float)

    val BEECH = Tone(Color.rgb(0xE2, 0xC2, 0x94), Color.rgb(0xC0, 0x98, 0x66), 9f, 0.05f)
    val WALNUT = Tone(Color.rgb(0x78, 0x53, 0x37), Color.rgb(0x4E, 0x33, 0x1E), 7f, 0.18f)
    val TABLE = Tone(Color.rgb(0x46, 0x33, 0x25), Color.rgb(0x2C, 0x1F, 0x16), 6f, 0.15f)
    val MAT = Tone(Color.rgb(0xB8, 0x9C, 0x74), Color.rgb(0x94, 0x78, 0x52), 8f, 0.06f)
    val BLOCK = Tone(Color.rgb(0x63, 0x43, 0x2D), Color.rgb(0x3E, 0x28, 0x18), 6f, 0.15f)

    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    /** Tileable value noise with period [p] cells. */
    private fun noise(x: Float, y: Float, p: Int, s: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        fun h(i: Int, j: Int) = hash(Math.floorMod(i, p), Math.floorMod(j, p), s)
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        val a = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * ux
        val b = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * ux
        return a + (b - a) * uy
    }

    fun build(t: Tone, seed: Int, n: Int = 512): Bitmap {
        val px = IntArray(n * n)
        val er = Color.red(t.early); val eg = Color.green(t.early); val eb = Color.blue(t.early)
        val lr = Color.red(t.late); val lg = Color.green(t.late); val lb = Color.blue(t.late)
        for (y in 0 until n) for (x in 0 until n) {
            val u = x.toFloat() / n; val v = y.toFloat() / n
            val warp = 0.6f * noise(u * 4f, v * 4f, 4, seed) + 0.25f * noise(u * 8f, v * 16f, 8, seed + 1)
            val r = v * t.rings + warp + 0.15f * sin(6.2832f * u)
            val ring = r - floor(r)
            val late = (if (ring < 0.7f) ring / 0.7f * 0.35f else 0.35f + (ring - 0.7f) / 0.3f * 0.65f).let { it * it }
            val streak = 0.5f + 0.5f * noise(u * 32f, v * 96f, 32, seed + 2)
            val pore = if (hash(x, y, seed + 3) < t.pores * (0.3f + late)) 0.82f else 1f
            val k = (late * 0.85f + (streak - 0.5f) * 0.25f).coerceIn(0f, 1f)
            val shade = pore * (0.94f + 0.06f * sqrt(streak))
            fun mix(a: Int, b: Int) = ((a + (b - a) * k) * shade).toInt().coerceIn(0, 255)
            px[y * n + x] = Color.rgb(mix(er, lr), mix(eg, lg), mix(eb, lb))
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }
}
