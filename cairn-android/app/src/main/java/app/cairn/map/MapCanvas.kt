package app.cairn.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.cairn.graph.Graph
import app.cairn.graph.ViewMode
import app.cairn.ui.drawKindGlyph
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/** Colours the canvas needs from the theme. */
class MapColors(val surface: Color, val hairline: Color, val ink: Color, val inkSecondary: Color)

/**
 * The map. Flat, solid node colours on the page background: no glow, no stars. Only thin low-alpha
 * guide lines and a faint halo behind the selected node. Gestures: drag turns or pans with
 * inertia, pinch zooms about the fingers, two fingers twist, double-tap zooms in.
 */
@Composable
fun MapCanvas(
    c: MapController,
    colors: MapColors,
    onTapNode: (Int) -> Unit,
    onTapEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val styles = remember(colors) { Styles(colors) }
    val path = remember { Path() }
    val t5 = remember { FloatArray(5) }
    val t5b = remember { FloatArray(5) }

    // Inertia and the idle turn. Only runs while animations are enabled.
    LaunchedEffect(c, c.animate) {
        if (!c.animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.05f)
                last = t
                c.tick(dt)
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("map")
            .semantics { contentDescription = "Brain map. Drag to turn or pan, pinch to zoom, twist to rotate, double-tap to zoom in, tap a point for details." }
            .pointerInput(c) {
                val slop = viewConfiguration.touchSlop
                var lastTapT = 0L
                var lastTap = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    c.touchDown()
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    var moved = false
                    var multi = false
                    var travelled = Offset.Zero
                    var ev: PointerEvent
                    do {
                        ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } > 1) multi = true
                        val z = ev.calculateZoom(); val p = ev.calculatePan()
                        val r = ev.calculateRotation(); val cen = ev.calculateCentroid()
                        if (!moved) {
                            travelled += p
                            if (travelled.getDistance() > slop || abs(z - 1f) > 0.02f || abs(r) > 2f) moved = true
                        }
                        if (moved) {
                            if (z != 1f) c.pinch(z, cen.x - size.width / 2f, cen.y - size.height / 2f)
                            if (multi && r != 0f) c.twist(r * (PI.toFloat() / 180f))
                            if (p != Offset.Zero) c.drag(p.x, p.y)
                            ev.changes.firstOrNull()?.let { vt.addPosition(it.uptimeMillis, it.position) }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (moved) {
                        val v = vt.calculateVelocity()
                        c.touchUp(if (multi) 0f else v.x, if (multi) 0f else v.y)
                    } else {
                        c.touchUp(0f, 0f)
                        val pos = down.position
                        val hit = c.scene.hit(pos.x, pos.y, density)
                        val dbl = down.uptimeMillis - lastTapT < 300 && (pos - lastTap).getDistance() < 48f * density
                        if (dbl) {
                            lastTapT = 0L
                            if (hit >= 0) c.zoomToNode(hit) else c.zoomAt(pos.x - size.width / 2f, pos.y - size.height / 2f)
                        } else {
                            lastTapT = down.uptimeMillis; lastTap = pos
                            if (hit >= 0) onTapNode(hit) else onTapEmpty()
                        }
                    }
                }
            },
    ) {
        val t0 = System.nanoTime()
        c.scene.frame(c, size.width, size.height, density)
        drawScene(c, colors, styles, measurer, path, t5, t5b)
        c.recordDraw(System.nanoTime() - t0)
        if (c.debug) drawDebug(c, styles, measurer)
    }
}

/** Text styles made once per theme; sizes are stepped so the text layout cache keeps hitting. */
private class Styles(val colors: MapColors) {
    val ink = Array(4) { TextStyle(fontSize = (10 + it).sp, color = colors.ink) }
    val sec = Array(4) { TextStyle(fontSize = (10 + it).sp, color = colors.inkSecondary) }
    val detail = Array(4) { TextStyle(fontSize = (9 + it).sp, color = colors.inkSecondary) }
    val cluster = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    val count = TextStyle(fontSize = 11.sp, color = colors.inkSecondary)
    val guide = TextStyle(fontSize = 10.sp, color = colors.inkSecondary)
    val debug = TextStyle(fontSize = 10.sp, color = colors.ink)
    val linkLabel = TextStyle(fontSize = 9.sp, color = colors.inkSecondary)
}

private val KIND_RINGS = floatArrayOf(.38f, .58f, .76f, .9f, 1.06f)
private val KIND_RING_NAMES = arrayOf("note", "repo", "file", "issue", "link")

private fun DrawScope.drawScene(
    c: MapController, colors: MapColors, st: Styles, tm: TextMeasurer, path: Path, t5: FloatArray, t5b: FloatArray,
) {
    val sc = c.scene
    val g = c.graph
    if (g.size == 0) return
    val w = size.width; val h = size.height
    val gm = sc.gm; val u = sc.u
    val pr = sc.projector
    val dens = density
    val cx = w / 2f + c.panX; val cy = h / 2f + c.panY

    // Globe body: one flat disc and a hairline limb.
    if (gm > 0.02f) {
        val rs = pr.silhouette
        drawCircle(colors.surface, rs, Offset(cx, cy), alpha = gm)
        drawCircle(colors.hairline, rs, Offset(cx, cy), alpha = gm, style = Stroke(1f * dens))
    }

    // Thin guide rings: around each area on the globe, and by kind on the Rings view.
    val ringAlpha = gm * 0.9f * (1f - Projector.smooth(1.4f, 3f, u))
    if (ringAlpha > 0.02f) {
        for (a in 0 until sc.areaCount) {
            path.rewind()
            var pen = false
            for (k in 0..Scene.RING_N) {
                val front = sc.ringPoint(a, k % Scene.RING_N, t5)
                if (front) { if (!pen) { path.moveTo(t5[0], t5[1]); pen = true } else path.lineTo(t5[0], t5[1]) } else pen = false
            }
            drawPath(path, Color(sc.areaColor[a]), alpha = 0.22f * ringAlpha * (if (c.isolated >= 0 && c.isolated != a) 0.3f else 1f), style = Stroke(1f * dens))
        }
    }
    if (gm < 0.98f) {
        val a = 1f - gm
        if (c.view == ViewMode.RINGS) {
            val base = Projector.baseSize(w, h) * u
            for (k in KIND_RINGS.indices) {
                val rr = KIND_RINGS[k] * base
                drawCircle(colors.hairline, rr, Offset(cx, cy), alpha = a, style = Stroke(1f * dens))
                val t = tm.measure(KIND_RING_NAMES[k], st.guide)
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, cy - rr - t.size.height - 2 * dens), alpha = a)
            }
        } else if (c.view == ViewMode.TIMELINE) {
            val base = Projector.baseSize(w, h) * u
            val older = tm.measure("older", st.guide); val newer = tm.measure("newer", st.guide)
            drawText(older, topLeft = Offset(cx - base, cy + base * 1.02f), alpha = a)
            drawText(newer, topLeft = Offset(cx + base - newer.size.width, cy + base * 1.02f), alpha = a)
        }
    }

    // Links: arcs on the globe when close, lines on flat views. All of them on the Links view.
    val sel = sc.selected
    val flatAll = c.view == ViewMode.LINKS && sel < 0 && gm < 0.5f
    val arcA = if (flatAll) 1f else sc.arcAlpha
    if (arcA > 0.02f || sel >= 0) {
        sc.arcsDrawn = 0
        for (pass in 0..1) {
            if (pass == 1 && sel < 0 && arcA <= 0.02f) break
            for (k in sc.la.indices) {
                val a = sc.la[k]; val b = sc.lb[k]
                val touches = sel >= 0 && (a == sel || b == sel)
                if (pass == 0 && !touches) continue
                if (pass == 1 && (touches || (sel >= 0) || arcA <= 0.02f)) continue
                val fa = minOf(sc.fa[a], sc.fa[b])
                if (fa < 0.05f) continue
                if (!touches && sc.arcsDrawn >= MAX_ARCS) break
                val col = Color(sc.colors[if (touches) sel else a])
                val alpha = fa * if (touches) 0.75f else 0.4f * arcA
                if (alpha < 0.03f) continue
                val width = (if (touches) 1.4f else 1f) * sc.linkWidth
                if (gm > 0.3f) {
                    sc.arcControl(a, b, t5)
                    path.rewind(); path.moveTo(sc.sx[a], sc.sy[a]); path.quadraticBezierTo(t5[0], t5[1], sc.sx[b], sc.sy[b])
                    drawPath(path, col, alpha, Stroke(width))
                    if (sc.expand > 0.3f && touchesFocus(sc, a, b) && sc.arcsDrawn < MAX_LINK_LABELS) {
                        val label = Graph.LINK_LABELS[sc.lt[k].coerceIn(0, Graph.LINK_LABELS.size - 1)]
                        val tt = tm.measure(label, st.linkLabel)
                        drawText(tt, topLeft = Offset(t5[0] - tt.size.width / 2f, t5[1] - tt.size.height / 2f), alpha = sc.expand * fa)
                    }
                } else {
                    drawLine(col, Offset(sc.sx[a], sc.sy[a]), Offset(sc.sx[b], sc.sy[b]), strokeWidth = width, alpha = alpha)
                }
                sc.arcsDrawn++
            }
        }
    }

    // Nodes, back to front.
    for (k in 0 until sc.n) {
        val i = sc.order[k]
        val a = sc.fa[i]
        if (a < 0.02f) continue
        val x = sc.sx[i]; val y = sc.sy[i]; val r = sc.rad[i]
        val col = Color(sc.colors[i])
        if (i == sel) drawCircle(col, r * 2.6f, Offset(x, y), alpha = 0.16f)
        drawKindGlyph(sc.kinds[i], x, y, r, col, a, path)
        if (i == sel || sc.isHit(i)) {
            drawCircle(colors.ink, r + 4f * dens, Offset(x, y), alpha = (if (i == sel) 0.9f else 0.6f) * minOf(1f, a * 2f), style = Stroke(1.5f * dens))
        }
    }

    // Cluster names at the far levels, cross-faded out as nodes take over.
    if (sc.clusterAlpha > 0.02f && sel < 0) {
        for (a in 0 until sc.areaCount) {
            val al = sc.areaA[a]
            if (al < 0.02f) continue
            val t = tm.measure(sc.areaName[a], st.cluster)
            val n = tm.measure(sc.areaTotal[a], st.count)
            drawText(t, topLeft = Offset(sc.areaX[a] - t.size.width / 2f, sc.areaY[a] - t.size.height / 2f), alpha = al)
            drawText(n, topLeft = Offset(sc.areaX[a] - n.size.width / 2f, sc.areaY[a] + t.size.height / 2f), alpha = al * 0.85f)
        }
    }

    // Node labels: short names, then full names with a snippet as the camera closes in.
    val sz = sc.labelSize
    for (k in 0 until sc.labelCount) {
        val i = sc.labelNode[k]
        val a = sc.fa[i]
        if (a < 0.1f) continue
        val forced = sc.labelForced[k]
        val full = sc.labelFull[k]
        val strong = i == sel
        val nameA = if (forced) 1f else sc.nameAlpha
        val showFull = full && (forced || sc.fullAlpha > 0.02f)
        val fullA = if (forced) 1f else sc.fullAlpha
        val x = sc.sx[i]; val r = sc.rad[i]
        val style = if (strong) st.ink[sz] else st.sec[sz]
        if (showFull) {
            val t = tm.measure(sc.fullTitle[i], style)
            val d = tm.measure(sc.detail[i], st.detail[sz])
            val tx = (x - t.size.width / 2f).coerceIn(4f * dens, maxOf(4f * dens, w - t.size.width - 4f * dens))
            val top = sc.sy[i] - r - t.size.height - d.size.height - 3f * dens
            drawRect(colors.surface, Offset(tx - 3 * dens, top - 1 * dens), androidx.compose.ui.geometry.Size(maxOf(t.size.width, d.size.width) + 6 * dens, t.size.height + d.size.height + 2 * dens), alpha = 0.85f * a * fullA)
            drawText(t, topLeft = Offset(tx, top), alpha = a * fullA * nameA)
            drawText(d, topLeft = Offset(tx, top + t.size.height), alpha = a * fullA * nameA)
        } else {
            val t = tm.measure(sc.shortTitle[i], style)
            val tx = (x - t.size.width / 2f).coerceIn(4f * dens, maxOf(4f * dens, w - t.size.width - 4f * dens))
            val ty = sc.sy[i] - r - t.size.height - 3f * dens
            drawRect(colors.surface, Offset(tx - 3 * dens, ty), androidx.compose.ui.geometry.Size(t.size.width + 6 * dens, t.size.height.toFloat()), alpha = 0.8f * a * nameA)
            drawText(t, topLeft = Offset(tx, ty), alpha = a * nameA)
        }
    }
}

private fun touchesFocus(sc: Scene, a: Int, b: Int) = sc.focusArea >= 0 && sc.areaOf[a] == sc.focusArea && sc.areaOf[b] == sc.focusArea

private fun DrawScope.drawDebug(c: MapController, st: Styles, tm: TextMeasurer) {
    val sc = c.scene
    val line = "draw %.1f ms (max %.1f) · %d nodes · %d labels · %d arcs · zoom %.1f".format(c.drawMsAvg, c.drawMsMax, sc.nodesDrawn, sc.labelCount, sc.arcsDrawn, c.zoom)
    val t = tm.measure(line, st.debug)
    drawRect(Color.Black, Offset(0f, 64f * density), androidx.compose.ui.geometry.Size(t.size.width + 12f * density, t.size.height + 6f * density), alpha = 0.6f)
    drawText(t, topLeft = Offset(6f * density, 67f * density), color = Color.White)
}

private const val MAX_ARCS = 160
private const val MAX_LINK_LABELS = 14
