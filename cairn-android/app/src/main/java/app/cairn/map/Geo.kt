package app.cairn.map

import androidx.compose.ui.graphics.Color
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The amber-gold family for the map only (the app around it stays neutral). Chosen with a hue shift
 * of roughly 25 degrees across the family so areas stay apart, on top of kind shapes and labels.
 */
object MapPalette {
    val BG = Color(0xFF020406)
    val DISC = Color(0xFF0A0803)
    val GOLD = Color(0xFFFFBE26)
    val AMBER = Color(0xFFFF9100)
    val HONEY = Color(0xFFFFD36B)
    val PALE = Color(0xFFFFE9A8)
    val CREAM = Color(0xFFFFF3D1)
    val MUTE = Color(0xFFB79A5A)
    /** Readable secondary text on the near-black map. */
    val SOFT = Color(0xFFE2CE9C)
    val LINE = Color(0x47FFBE26)

    /** Eight area colours, most distinct first: gold, copper, pale gold, bronze, amber, rust, honey, olive-cream. */
    val AREAS = longArrayOf(
        0xFFFFC233, 0xFFF27B28, 0xFFFFE9A8, 0xFFC98A3C, 0xFFFF9F1C, 0xFFD9632B, 0xFFE8B84A, 0xFFE6D28A,
    )
}

/**
 * Fixed geometry, built once: four nested wire-sphere shells, equator rings with tick marks, a few
 * hundred particles that sit on the shells, and the icosahedron that forms the nucleus.
 */
object Geo {
    const val SEG = 36
    val SHELL_R = floatArrayOf(1.0f, 0.78f, 0.55f, 0.32f)
    /** Counter-rotating, slow: radians per second. */
    val SHELL_SPIN = floatArrayOf(0.012f, -0.02f, 0.03f, -0.045f)
    val SHELL_ALPHA = floatArrayOf(0.20f, 0.15f, 0.15f, 0.18f)
    val SHELL_COLOR = arrayOf(MapPalette.GOLD, MapPalette.AMBER, MapPalette.HONEY, MapPalette.PALE)

    private fun range(n: Int, a: Float, b: Float) = FloatArray(n) { a + (b - a) * (if (n == 1) 0.5f else it.toFloat() / (n - 1)) }

    private fun shell(r: Float, lats: FloatArray, lons: FloatArray): FloatArray {
        val out = FloatArray((lats.size + lons.size) * (SEG + 1) * 3)
        var o = 0
        for (la in lats) {
            val y = sin(la) * r; val rr = cos(la) * r
            for (i in 0..SEG) { val a = i.toFloat() / SEG * (2 * PI).toFloat(); out[o++] = cos(a) * rr; out[o++] = y; out[o++] = sin(a) * rr }
        }
        for (lo in lons) {
            for (i in 0..SEG) {
                val a = (-PI / 2 + i.toDouble() / SEG * PI).toFloat()
                out[o++] = cos(a) * cos(lo) * r; out[o++] = sin(a) * r; out[o++] = cos(a) * sin(lo) * r
            }
        }
        return out
    }

    val shells: Array<FloatArray> = arrayOf(
        shell(SHELL_R[0], range(7, -1.25f, 1.25f), range(14, 0f, PI.toFloat())),
        shell(SHELL_R[1], range(5, -1.1f, 1.1f), range(10, 0f, PI.toFloat())),
        shell(SHELL_R[2], range(4, -1f, 1f), range(8, 0f, PI.toFloat())),
        shell(SHELL_R[3], range(3, -0.9f, 0.9f), range(6, 0f, PI.toFloat())),
    )

    // Equator tracks with ticks: radius, tilt about x, tilt about z, spin rad/s.
    val RING_R = floatArrayOf(1.0f, 0.82f, 0.64f)
    private val RING_TX = floatArrayOf(-0.2f, 0.3f, -0.2f)
    private val RING_TZ = floatArrayOf(-0.35f, 0f, 0.35f)
    val RING_SPIN = floatArrayOf(0.05f, -0.062f, 0.074f)
    const val RING_SEG = 120

    private fun tilt(x: Float, y: Float, z: Float, tx: Float, tz: Float, out: FloatArray, o: Int) {
        // rotate about x, then about z
        val y1 = y * cos(tx) - z * sin(tx); val z1 = y * sin(tx) + z * cos(tx)
        out[o] = x * cos(tz) - y1 * sin(tz); out[o + 1] = x * sin(tz) + y1 * cos(tz); out[o + 2] = z1
    }

    /** Ring outline then tick segments (pairs of points), all pre-tilted. */
    val rings: Array<FloatArray> = Array(3) { k ->
        val r = RING_R[k]
        val ticks = Math.round(r * 60f)
        val out = FloatArray((RING_SEG + 1 + ticks * 2) * 3)
        var o = 0
        for (i in 0..RING_SEG) { val a = i.toFloat() / RING_SEG * (2 * PI).toFloat(); tilt(cos(a) * r, 0f, sin(a) * r, RING_TX[k], RING_TZ[k], out, o); o += 3 }
        for (j in 0 until ticks) {
            val a = j.toFloat() / ticks * (2 * PI).toFloat()
            val len = if (j % 5 == 0) 0.035f else 0.015f
            tilt(cos(a) * r, 0f, sin(a) * r, RING_TX[k], RING_TZ[k], out, o); o += 3
            tilt(cos(a) * (r + len), 0f, sin(a) * (r + len), RING_TX[k], RING_TZ[k], out, o); o += 3
        }
        out
    }
    val RING_OUTLINE = RING_SEG + 1

    // Particles drifting on the shells.
    const val PN = 560
    val PSPIN = floatArrayOf(0.04f, -0.055f, 0.07f, -0.09f)
    val pShell = IntArray(PN)
    val pX = FloatArray(PN); val pY = FloatArray(PN); val pZ = FloatArray(PN)
    val pSize = FloatArray(PN)
    val pTone = IntArray(PN)

    init {
        val rnd = Random(7)
        val weights = floatArrayOf(0.38f, 0.28f, 0.2f, 0.14f)
        for (i in 0 until PN) {
            var u = rnd.nextFloat(); var s = 0
            while (s < 3 && u > weights[s]) { u -= weights[s]; s++ }
            val r = SHELL_R[s] * (1f + (rnd.nextFloat() - 0.5f) * 0.05f)
            val th = rnd.nextFloat() * 2 * PI.toFloat()
            val ph = Math.asin((rnd.nextFloat() * 2 - 1).toDouble()).toFloat() * 0.98f
            pShell[i] = s
            pX[i] = r * cos(ph) * cos(th); pY[i] = r * sin(ph); pZ[i] = r * cos(ph) * sin(th)
            pSize[i] = 0.7f + rnd.nextFloat() * 0.8f
            pTone[i] = if (rnd.nextFloat() > 0.7f) 2 else if (rnd.nextFloat() > 0.35f) 0 else 1
        }
    }

    // The nucleus: icosahedron outlines at three sizes.
    val NUC_R = floatArrayOf(0.10f, 0.17f, 0.25f)
    val NUC_SPIN = floatArrayOf(0.22f, -0.32f, 0.42f)
    val NUC_ALPHA = floatArrayOf(0.7f, 0.5f, 0.4f)
    val NUC_COLOR = arrayOf(MapPalette.CREAM, MapPalette.GOLD, MapPalette.AMBER)
    val icoV: FloatArray
    val icoE: IntArray

    init {
        val t = ((1 + sqrt(5.0)) / 2).toFloat()
        val raw = arrayOf(
            floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
            floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
            floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f),
        )
        icoV = FloatArray(36)
        for (i in 0 until 12) {
            val l = sqrt(raw[i][0] * raw[i][0] + raw[i][1] * raw[i][1] + raw[i][2] * raw[i][2])
            for (k in 0..2) icoV[3 * i + k] = raw[i][k] / l
        }
        val edges = ArrayList<Int>()
        val edgeLen = 2f / sqrt(1f + t * t)
        for (i in 0 until 12) for (j in i + 1 until 12) {
            val dx = icoV[3 * i] - icoV[3 * j]; val dy = icoV[3 * i + 1] - icoV[3 * j + 1]; val dz = icoV[3 * i + 2] - icoV[3 * j + 2]
            if (abs(sqrt(dx * dx + dy * dy + dz * dz) - edgeLen) < 0.01f) { edges += i; edges += j }
        }
        icoE = edges.toIntArray()
    }
}
