package app.cairn.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * The instrument around the data: wire-sphere shells, equator tracks with ticks, drifting particles,
 * the nucleus and the slow scan ring. Thin low-alpha lines and tiny dots only. Every part fades out
 * and shrinks as the camera closes in (`Scene.decorAlpha`), so close-up detail stays crisp.
 * Nothing allocates: one projector, one scratch array, one Path.
 */
internal fun DrawScope.drawShells(sc: Scene, path: Path, t5: FloatArray) {
    val alpha = sc.gm * sc.decorAlpha
    if (alpha < 0.02f) return
    val pr = sc.projector
    val per = Geo.SEG + 1
    for (k in 0 until 4) {
        if (Geo.SHELL_ALPHA[k] * alpha < 0.02f) continue
        val ang = sc.time * Geo.SHELL_SPIN[k]
        val c = cos(ang); val s = sin(ang)
        val v = Geo.shells[k]
        val count = v.size / 3
        path.rewind()
        var pen = false
        for (i in 0 until count) {
            if (i % per == 0) pen = false
            val x = v[3 * i]; val z = v[3 * i + 2]
            pr.project(x * c + z * s, v[3 * i + 1], -x * s + z * c, t5)
            if (t5[4] < 0.5f) { pen = false; continue }
            if (!pen) { path.moveTo(t5[0], t5[1]); pen = true } else path.lineTo(t5[0], t5[1])
        }
        drawPath(path, Geo.SHELL_COLOR[k], alpha = Geo.SHELL_ALPHA[k] * alpha, style = Stroke(0.8f * density))
    }
}

internal fun DrawScope.drawTracks(sc: Scene, path: Path, t5: FloatArray) {
    val alpha = sc.gm * sc.decorAlpha
    if (alpha < 0.02f) return
    val pr = sc.projector
    for (k in 0 until 3) {
        if (0.34f * alpha < 0.02f) continue
        val ang = sc.time * Geo.RING_SPIN[k]
        val c = cos(ang); val s = sin(ang)
        val v = Geo.rings[k]
        val total = v.size / 3
        path.rewind()
        var pen = false
        for (i in 0 until Geo.RING_OUTLINE) {
            val x = v[3 * i]; val z = v[3 * i + 2]
            pr.project(x * c + z * s, v[3 * i + 1], -x * s + z * c, t5)
            if (t5[4] < 0.5f) { pen = false; continue }
            if (!pen) { path.moveTo(t5[0], t5[1]); pen = true } else path.lineTo(t5[0], t5[1])
        }
        var i = Geo.RING_OUTLINE
        while (i + 1 < total) {
            var x = v[3 * i]; var z = v[3 * i + 2]
            pr.project(x * c + z * s, v[3 * i + 1], -x * s + z * c, t5)
            val ax = t5[0]; val ay = t5[1]; val okA = t5[4]
            x = v[3 * i + 3]; z = v[3 * i + 5]
            pr.project(x * c + z * s, v[3 * i + 4], -x * s + z * c, t5)
            if (okA > 0.5f && t5[4] > 0.5f) { path.moveTo(ax, ay); path.lineTo(t5[0], t5[1]) }
            i += 2
        }
        drawPath(path, if (k == 1) MapPalette.AMBER else MapPalette.GOLD, alpha = 0.34f * alpha, style = Stroke(0.9f * density))
    }
}

/** Particles are tiny round dots. They are batched into six native draw calls (three tones, front and back). */
internal class ParticleBatch {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val pts = Array(6) { FloatArray(2 * Geo.PN) }
    val count = IntArray(6)
    val argb = intArrayOf(
        MapPalette.GOLD.toArgb(), MapPalette.AMBER.toArgb(), MapPalette.PALE.toArgb(),
    )
}

internal fun DrawScope.drawParticles(sc: Scene, t5: FloatArray, pb: ParticleBatch) {
    val alpha = sc.gm * 0.6f * sc.decorAlpha
    if (alpha < 0.02f || sc.detail > 0.97f) return
    val pr = sc.projector
    val w = size.width; val h = size.height
    java.util.Arrays.fill(pb.count, 0)
    var c = 0f; var s = 0f; var last = -1
    for (i in 0 until Geo.PN) {
        val sh = Geo.pShell[i]
        if (sh != last) { val a = sc.time * Geo.PSPIN[sh]; c = cos(a); s = sin(a); last = sh }
        val x = Geo.pX[i]; val z = Geo.pZ[i]
        pr.project(x * c + z * s, Geo.pY[i], -x * s + z * c, t5)
        if (t5[4] < 0.5f) continue
        val px = t5[0]; val py = t5[1]
        if (px < 0f || px > w || py < 0f || py > h) continue
        val bin = Geo.pTone[i] * 2 + (if (t5[2] > 0f) 1 else 0)
        val n = pb.count[bin]
        pb.pts[bin][2 * n] = px; pb.pts[bin][2 * n + 1] = py
        pb.count[bin] = n + 1
    }
    val shrink = 1f - 0.6f * sc.detail
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val paint = pb.paint
        for (bin in 0 until 6) {
            val n = pb.count[bin]
            if (n == 0) continue
            val front = bin % 2 == 1
            paint.color = pb.argb[bin / 2]
            paint.alpha = (255f * alpha * (if (front) 0.9f else 0.4f)).toInt().coerceIn(0, 255)
            paint.strokeWidth = (if (front) 2.2f else 1.6f) * density * shrink
            nc.drawPoints(pb.pts[bin], 0, 2 * n, paint)
        }
    }
}

internal fun DrawScope.drawNucleus(sc: Scene, path: Path, t5: FloatArray, tx: FloatArray, ty: FloatArray, tok: BooleanArray) {
    val alpha = sc.gm * (1f - 0.85f * sc.detail)
    if (alpha < 0.1f) return
    val pr = sc.projector
    for (k in 0 until 3) {
        val a1 = sc.time * Geo.NUC_SPIN[k]; val a2 = a1 * 0.6f + k
        val cy = cos(a1); val sy = sin(a1); val cx = cos(a2); val sx = sin(a2)
        val r = Geo.NUC_R[k]
        for (v in 0 until 12) {
            val x = Geo.icoV[3 * v] * r; val y = Geo.icoV[3 * v + 1] * r; val z = Geo.icoV[3 * v + 2] * r
            val x1 = x * cy + z * sy; val z1 = -x * sy + z * cy
            val y2 = y * cx - z1 * sx; val z2 = y * sx + z1 * cx
            pr.project(x1, y2, z2, t5)
            tx[v] = t5[0]; ty[v] = t5[1]; tok[v] = t5[4] > 0.5f
        }
        path.rewind()
        var e = 0
        while (e < Geo.icoE.size) {
            val a = Geo.icoE[e]; val b = Geo.icoE[e + 1]
            if (tok[a] && tok[b]) { path.moveTo(tx[a], ty[a]); path.lineTo(tx[b], ty[b]) }
            e += 2
        }
        drawPath(path, Geo.NUC_COLOR[k], alpha = Geo.NUC_ALPHA[k] * alpha, style = Stroke(1f * density))
    }
}

/** Two slow rings growing out from the centre and fading, like a scan. Only while motion is on. */
internal fun DrawScope.drawScan(sc: Scene) {
    if (!sc.motion) return
    val alpha = sc.gm * (1f - sc.detail)
    if (alpha < 0.02f) return
    val centre = Offset(sc.projector.centreX, sc.projector.centreY)
    val a = (sc.time % 7f) / 7f
    drawCircle(MapPalette.HONEY, sc.projector.silhouette * (0.12f + 0.9f * a), centre, alpha * 0.4f * (1f - a), Stroke(1f * density))
    val b = ((sc.time + 3.5f) % 9f) / 9f
    drawCircle(MapPalette.AMBER, sc.projector.silhouette * (0.15f + 0.85f * b), centre, alpha * 0.26f * (1f - b), Stroke(1f * density))
}

internal fun mix(a: Color, b: Color, t: Float): Color = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)
