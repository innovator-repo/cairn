package app.cairn.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.cairn.data.Kind
import app.cairn.graph.ViewMode
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Colours the canvas needs from the theme. */
class MapColors(val surface: Color, val hairline: Color, val ink: Color, val inkSecondary: Color)

/**
 * The map. Flat, solid node colours on the page background: no glow, no stars, no orbit rings.
 * The only soft thing is a faint halo behind the selected node.
 */
@Composable
fun MapCanvas(
    c: MapController,
    colors: MapColors,
    onTapNode: (Int) -> Unit,
    onTapEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val tmp = remember { FloatArray(3) }
    val shape = remember { Path() }
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Brain map. Drag to turn or pan, pinch to zoom, tap a point for details." }
            .pointerInput(c) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f) c.pinch(zoom, centroid.x - size.width / 2f, centroid.y - size.height / 2f)
                    if (pan != Offset.Zero) c.drag(pan.x, pan.y)
                }
            }
            .pointerInput(c) {
                detectTapGestures { p ->
                    val hit = hitTest(c, p, size.width.toFloat(), size.height.toFloat(), density, tmp)
                    if (hit >= 0) onTapNode(hit) else onTapEmpty()
                }
            },
    ) {
        c.viewSize = min(size.width, size.height)
        drawMap(c, colors, measurer, tmp, shape)
    }
}

private fun nodeRadius(c: MapController, i: Int, density: Float): Float {
    val deg = c.graph.degree[i]
    return (3.2f + min(7f, deg * 1.15f)) * (0.75f + c.zoom * 0.35f) * density * 0.95f
}

/** How visible node [z] is: faded when it faces away on the globe. */
private fun visibility(globeMix: Float, depth: Float): Float {
    val away = ((0.05f - depth) / 0.2f).coerceIn(0f, 1f)
    return 1f - globeMix * away
}

private fun hitTest(c: MapController, p: Offset, w: Float, h: Float, density: Float, tmp: FloatArray): Int {
    var best = -1
    var bestD = Float.MAX_VALUE
    for (i in 0 until c.graph.size) {
        if (c.isolated >= 0 && c.graph.areaIndex[i] != c.isolated) continue
        c.position(i, tmp)
        Projection.toScreen(tmp[0], tmp[1], tmp[2], c.yaw, c.pitch, c.zoom, w, h, c.panX, c.panY, tmp)
        if (visibility(c.globeMix, tmp[2]) < 0.5f) continue
        val d = hypot(tmp[0] - p.x, tmp[1] - p.y)
        if (d < max(24f * density, nodeRadius(c, i, density) + 10f * density) && d < bestD) { bestD = d; best = i }
    }
    return best
}

private fun DrawScope.drawKind(kind: Kind, x: Float, y: Float, r: Float, color: Color, alpha: Float, shape: Path) {
    when (kind) {
        Kind.NOTE -> drawCircle(color, r, Offset(x, y), alpha)
        Kind.REPO -> drawRect(color, Offset(x - r, y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), alpha)
        Kind.FILE -> drawRect(color, Offset(x - r * .75f, y - r * .75f), androidx.compose.ui.geometry.Size(r * 1.5f, r * 1.5f), alpha)
        Kind.ISSUE -> {
            shape.rewind()
            shape.moveTo(x, y - r * 1.25f); shape.lineTo(x + r * 1.15f, y + r * .85f); shape.lineTo(x - r * 1.15f, y + r * .85f); shape.close()
            drawPath(shape, color, alpha)
        }
        Kind.LINK -> {
            shape.rewind()
            shape.moveTo(x, y - r * 1.2f); shape.lineTo(x + r * 1.1f, y); shape.lineTo(x, y + r * 1.2f); shape.lineTo(x - r * 1.1f, y); shape.close()
            drawPath(shape, color, alpha)
        }
    }
}

private fun DrawScope.drawMap(c: MapController, colors: MapColors, tm: TextMeasurer, tmp: FloatArray, shape: Path) {
    val g = c.graph
    if (g.size == 0) return
    val w = size.width; val h = size.height
    val gm = c.globeMix
    val dens = density
    val small = TextStyle(fontSize = 11.sp, color = colors.inkSecondary)
    val rootX = w / 2f + c.panX; val rootY = h / 2f + c.panY
    val R = Projection.base(w, h) * c.zoom

    // Globe body: one flat disc and a hairline limb.
    if (gm > 0.02f) {
        drawCircle(colors.surface, R, Offset(rootX, rootY), alpha = gm)
        drawCircle(colors.hairline, R, Offset(rootX, rootY), alpha = gm, style = Stroke(1f * dens))
    }

    // Guides for the flat views.
    if (gm < 0.98f) {
        val a = 1f - gm
        when (c.view) {
            ViewMode.RINGS -> {
                val labels = listOf("note" to .38f, "repo" to .58f, "file" to .76f, "issue" to .9f, "link" to 1.06f)
                for ((name, rr) in labels) {
                    drawCircle(colors.hairline, rr * R, Offset(rootX, rootY), alpha = a, style = Stroke(1f * dens))
                    val t = tm.measure(name, small)
                    drawText(t, topLeft = Offset(rootX - t.size.width / 2f, rootY - rr * R - t.size.height - 2 * dens), alpha = a)
                }
            }
            ViewMode.TIMELINE -> {
                val older = tm.measure("older", small); val newer = tm.measure("newer", small)
                drawText(older, topLeft = Offset(rootX - R, rootY + R * 1.02f), alpha = a)
                drawText(newer, topLeft = Offset(rootX + R - newer.size.width, rootY + R * 1.02f), alpha = a)
            }
            else -> {}
        }
    }

    // Collect screen positions once.
    val n = g.size
    val sx = FloatArray(n); val sy = FloatArray(n); val vis = FloatArray(n); val depth = FloatArray(n)
    for (i in 0 until n) {
        c.position(i, tmp)
        Projection.toScreen(tmp[0], tmp[1], tmp[2], c.yaw, c.pitch, c.zoom, w, h, c.panX, c.panY, tmp)
        sx[i] = tmp[0]; sy[i] = tmp[1]; depth[i] = tmp[2]; vis[i] = visibility(gm, tmp[2])
    }

    val sel = c.selected
    val neighbours = if (sel >= 0) (g.neighbours(sel) + sel).toSet() else emptySet()
    val searching = c.hits.isNotEmpty()

    // Links: all of them in the Links view, otherwise only the selection's.
    val showAll = c.view == ViewMode.LINKS && sel < 0 && gm < 0.5f
    for (l in g.links) {
        val touches = sel >= 0 && (l[0] == sel || l[1] == sel)
        if (!touches && !showAll) continue
        val a = l[0]; val b = l[1]
        if (c.isolated >= 0 && (g.areaIndex[a] != c.isolated || g.areaIndex[b] != c.isolated) && !touches) continue
        val alpha = min(vis[a], vis[b]) * if (touches) 0.7f else 0.22f
        if (alpha < 0.03f) continue
        val col = Color(g.areas[g.areaIndex[if (touches) sel else a]].color)
        if (gm > 0.3f) {
            // Lift the arc away from the sphere so it does not cut through it.
            val mx = (sx[a] + sx[b]) / 2f; val my = (sy[a] + sy[b]) / 2f
            val lift = 1f + gm * 0.25f
            val cx = rootX + (mx - rootX) * lift; val cy = rootY + (my - rootY) * lift
            shape.rewind(); shape.moveTo(sx[a], sy[a]); shape.quadraticBezierTo(cx, cy, sx[b], sy[b])
            drawPath(shape, col, alpha, Stroke(1.6f * dens))
        } else {
            drawLine(col, Offset(sx[a], sy[a]), Offset(sx[b], sy[b]), strokeWidth = (if (touches) 1.6f else 1f) * dens, alpha = alpha)
        }
    }

    // Nodes, back to front.
    val order = (0 until n).sortedBy { depth[it] }
    for (i in order) {
        if (vis[i] < 0.02f) continue
        val dim = (c.isolated >= 0 && g.areaIndex[i] != c.isolated) ||
            (sel >= 0 && i !in neighbours) || (searching && i !in c.hits)
        val shade = 1f - gm * 0.45f * (1f - depth[i].coerceIn(0f, 1f))
        val a = vis[i] * shade * (if (dim) 0.16f else 1f)
        val r = nodeRadius(c, i, dens)
        val col = Color(g.areas[g.areaIndex[i]].color)
        if (i == sel) drawCircle(col, r * 2.6f, Offset(sx[i], sy[i]), alpha = 0.16f)
        drawKind(g.entries[i].kind, sx[i], sy[i], r, col, a, shape)
        if (i == sel || (searching && i in c.hits)) {
            drawCircle(colors.ink, r + 4f * dens, Offset(sx[i], sy[i]), alpha = if (i == sel) 0.9f else 0.6f, style = Stroke(1.5f * dens))
        }
    }

    // Labels: area names, then the selection and its first neighbours.
    val areaStyle = TextStyle(fontSize = 12.sp, color = colors.ink)
    fun label(i: Int, strong: Boolean) {
        if (vis[i] < 0.5f) return
        val title = g.entries[i].title.let { if (it.length > 26) it.take(25) + "…" else it }
        val t = tm.measure(title, TextStyle(fontSize = 11.sp, color = if (strong) colors.ink else colors.inkSecondary))
        val r = nodeRadius(c, i, dens)
        val x = (sx[i] - t.size.width / 2f).coerceIn(4f * dens, w - t.size.width - 4f * dens)
        drawRect(colors.surface, Offset(x - 4 * dens, sy[i] - r - t.size.height - 6 * dens), androidx.compose.ui.geometry.Size(t.size.width + 8 * dens, t.size.height + 2 * dens), alpha = 0.9f)
        drawText(t, topLeft = Offset(x, sy[i] - r - t.size.height - 5 * dens))
    }
    if (sel >= 0) {
        label(sel, true)
        g.neighbours(sel).take(4).forEach { label(it, false) }
    } else if (searching) {
        c.hits.take(6).forEach { label(it, false) }
    } else if (c.zoom < 2.2f) {
        val v = Projection.base(w, h)
        g.areas.forEachIndexed { ai, area ->
            if (c.isolated >= 0 && c.isolated != ai) return@forEachIndexed
            val (px, py, alpha) = areaLabelAnchor(c, ai, tmp, w, h, v)
            if (alpha < 0.05f) return@forEachIndexed
            val t = tm.measure(area.name, areaStyle)
            drawText(t, topLeft = Offset(px - t.size.width / 2f, py - t.size.height / 2f), alpha = alpha)
        }
    }
}

/** Where an area's name sits: the average of its nodes' screen positions, faded when facing away. */
private fun areaLabelAnchor(c: MapController, area: Int, tmp: FloatArray, w: Float, h: Float, @Suppress("UNUSED_PARAMETER") base: Float): Triple<Float, Float, Float> {
    var x = 0f; var y = 0f; var z = 0f; var k = 0
    val g = c.graph
    val sum = FloatArray(3)
    for (i in 0 until g.size) {
        if (g.areaIndex[i] != area) continue
        c.position(i, tmp)
        sum[0] += tmp[0]; sum[1] += tmp[1]; sum[2] += tmp[2]; k++
    }
    if (k == 0) return Triple(0f, 0f, 0f)
    val len = if (c.globeMix > 0.5f) max(1e-3f, sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2])) else k.toFloat()
    val mean = if (c.globeMix > 0.5f) floatArrayOf(sum[0] / len, sum[1] / len, sum[2] / len) else floatArrayOf(sum[0] / k, sum[1] / k, sum[2] / k)
    Projection.toScreen(mean[0], mean[1], mean[2], c.yaw, c.pitch, c.zoom, w, h, c.panX, c.panY, tmp)
    x = tmp[0]; y = tmp[1]; z = tmp[2]
    val alpha = visibility(c.globeMix, z) * (if (c.globeMix > 0.5f) ((z - 0.1f) * 1.6f).coerceIn(0f, 1f) else 1f)
    // Sit just above the cluster so the name does not cover its nodes.
    return Triple(x, y - Projection.base(w, h) * c.zoom * 0.2f, alpha)
}
