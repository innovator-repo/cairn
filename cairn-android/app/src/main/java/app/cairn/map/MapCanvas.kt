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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
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
class MapColors(val surface: Color, val hairline: Color, val ink: Color, val inkSecondary: Color, val mute: Color = inkSecondary)

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
    val bufs = remember { Bufs() }

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
            .clipToBounds()
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
                        val eng = if (hit < 0) c.scene.hitEngram(pos.x, pos.y, density) else -1
                        val dbl = down.uptimeMillis - lastTapT < 300 && (pos - lastTap).getDistance() < 48f * density
                        if (dbl) {
                            lastTapT = 0L
                            if (hit >= 0) c.zoomToNode(hit)
                            else if (eng >= 0) c.flyToArea(eng, 4f)
                            else c.zoomAt(pos.x - size.width / 2f, pos.y - size.height / 2f)
                        } else {
                            lastTapT = down.uptimeMillis; lastTap = pos
                            if (hit >= 0) onTapNode(hit) else if (eng >= 0) c.flyToArea(eng) else onTapEmpty()
                        }
                    }
                }
            },
    ) {
        val t0 = System.nanoTime()
        c.clock // read so the canvas redraws every frame while motion is on
        c.scene.frame(c, size.width, size.height, density)
        drawScene(c, colors, styles, measurer, bufs)
        c.recordDraw(System.nanoTime() - t0)
        if (c.debug) drawDebug(c, styles, measurer)
    }
}

/** Scratch objects reused every frame. */
private class Bufs {
    val path = Path()
    val glyph = Path()
    val t5 = FloatArray(5)
    val t5b = FloatArray(5)
    val nx = FloatArray(12)
    val ny = FloatArray(12)
    val nok = BooleanArray(12)
    val particles = ParticleBatch()
}

/** Text styles made once per theme; sizes are stepped so the text layout cache keeps hitting. */
private class Styles(val colors: MapColors) {
    val ink = Array(4) { TextStyle(fontSize = (10 + it).sp, color = colors.ink) }
    val sec = Array(4) { TextStyle(fontSize = (10 + it).sp, color = colors.inkSecondary) }
    val detail = Array(4) { TextStyle(fontSize = (9 + it).sp, color = colors.mute) }
    val cluster = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, letterSpacing = 1.2.sp)
    val count = TextStyle(fontSize = 10.sp, color = colors.inkSecondary)
    val guide = TextStyle(fontSize = 10.sp, color = colors.inkSecondary, letterSpacing = 1.sp)
    val debug = TextStyle(fontSize = 10.sp, color = colors.ink)
    val linkLabel = TextStyle(fontSize = 9.sp, color = colors.inkSecondary)
}

private val KIND_RINGS = floatArrayOf(.38f, .58f, .76f, .9f, 1.06f)
private val KIND_RING_NAMES = arrayOf("note", "repo", "file", "issue", "link")
private val DEPTH_TEXT = mapOf("FAR" to "DEPTH · FAR", "MID" to "DEPTH · MID", "NEAR" to "DEPTH · NEAR", "CLOSE" to "DEPTH · CLOSE")

private fun DrawScope.drawScene(c: MapController, colors: MapColors, st: Styles, tm: TextMeasurer, b: Bufs) {
    val sc = c.scene
    val g = c.graph
    if (g.size == 0) return
    val path = b.path; val t5 = b.t5
    val w = size.width; val h = size.height
    val gm = sc.gm; val u = sc.u
    val pr = sc.projector
    val dens = density
    val cx = pr.centreX; val cy = pr.centreY

    // The globe body: a very faint warm disc and a hairline limb.
    if (gm > 0.02f) {
        drawCircle(MapPalette.DISC, pr.silhouette, Offset(cx, cy), alpha = gm * 0.9f)
        drawCircle(colors.hairline, pr.silhouette, Offset(cx, cy), alpha = gm * 0.7f, style = Stroke(1f * dens))
    }
    // The instrument: shells, tracks, particles, nucleus, scan. Fades out as the camera closes in.
    drawShells(sc, path, t5)
    drawTracks(sc, path, t5)
    drawParticles(sc, t5, b.particles)
    drawNucleus(sc, path, t5, b.nx, b.ny, b.nok)
    drawScan(sc)

    // Guides for the flat views.
    if (gm < 0.98f) {
        val a = 1f - gm
        val base = Projector.baseSize(w, h) * u
        if (c.view == ViewMode.RINGS) {
            for (k in KIND_RINGS.indices) {
                val rr = KIND_RINGS[k] * base
                drawCircle(colors.hairline, rr, Offset(cx, cy), alpha = a, style = Stroke(1f * dens))
                val t = tm.measure(KIND_RING_NAMES[k], st.guide)
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, cy - rr - t.size.height - 2 * dens), alpha = a)
            }
        } else if (c.view == ViewMode.TIMELINE) {
            val older = tm.measure("older", st.guide); val newer = tm.measure("newer", st.guide)
            drawText(older, topLeft = Offset(cx - base, cy + base * 1.02f), alpha = a)
            drawText(newer, topLeft = Offset(cx + base - newer.size.width, cy + base * 1.02f), alpha = a)
        }
    }

    val sel = sc.selected

    // Long arcs between engrams: visible far and mid, fading as memories take over.
    val engArc = sc.showEngrams && sel < 0 && gm > 0.3f
    if (engArc) {
        val a0 = (1f - Projector.smooth(2.2f, 4.2f, u)) * 0.4f
        if (a0 > 0.02f) for (k in sc.pairA.indices) {
            val a = sc.pairA[k]; val bb = sc.pairB[k]
            val al = minOf(sc.engA[a], sc.engA[bb]) * a0
            if (al < 0.02f) continue
            sc.arcControlPts(sc.ex[a], sc.ey[a], sc.ez[a], sc.ex[bb], sc.ey[bb], sc.ez[bb], t5)
            path.rewind(); path.moveTo(sc.engX[a], sc.engY[a]); path.quadraticBezierTo(t5[0], t5[1], sc.engX[bb], sc.engY[bb])
            drawPath(path, MapPalette.GOLD, al, Stroke(0.9f * dens))
        }
    }

    // Memory links: all on the Links view; otherwise only the selection's, plus a growing share when near.
    val flatAll = c.view == ViewMode.LINKS && sel < 0 && gm < 0.5f
    val arcA = if (flatAll) 1f else sc.arcAlpha
    sc.arcsDrawn = 0
    if (arcA > 0.02f || sel >= 0) {
        for (pass in 0..1) {
            if (pass == 1 && (sel >= 0 || arcA <= 0.02f)) break
            for (k in sc.la.indices) {
                val a = sc.la[k]; val bb = sc.lb[k]
                val touches = sel >= 0 && (a == sel || bb == sel)
                if (pass == 0 && !touches) continue
                val fa = minOf(sc.fa[a], sc.fa[bb])
                if (fa < 0.05f) continue
                if (!touches && sc.arcsDrawn >= MAX_ARCS) break
                val col = Color(sc.colors[if (touches) sel else a])
                val alpha = fa * if (touches) 0.8f else 0.42f * arcA
                if (alpha < 0.03f) continue
                val width = (if (touches) 1.3f else 0.9f) * sc.linkWidth
                if (gm > 0.3f) {
                    sc.arcControl(a, bb, t5)
                    val cxp = t5[0]; val cyp = t5[1]
                    path.rewind(); path.moveTo(sc.sx[a], sc.sy[a]); path.quadraticBezierTo(cxp, cyp, sc.sx[bb], sc.sy[bb])
                    drawPath(path, col, alpha, Stroke(width))
                    if (touches && sc.motion) {
                        // One or two small packets travelling along the selected node's links.
                        for (p in 0..1) {
                            val t = (sc.time * 0.22f + k * 0.37f + p * 0.5f) % 1f
                            val m = 1f - t
                            val px = m * m * sc.sx[a] + 2f * m * t * cxp + t * t * sc.sx[bb]
                            val py = m * m * sc.sy[a] + 2f * m * t * cyp + t * t * sc.sy[bb]
                            drawCircle(MapPalette.CREAM, 1.8f * dens, Offset(px, py), alpha = alpha)
                        }
                    }
                    if (sc.expand > 0.3f && touchesFocus(sc, a, bb) && sc.arcsDrawn < MAX_LINK_LABELS) {
                        val label = Graph.LINK_LABELS[sc.lt[k].coerceIn(0, Graph.LINK_LABELS.size - 1)]
                        val tt = tm.measure(label, st.linkLabel)
                        drawText(tt, topLeft = Offset(cxp - tt.size.width / 2f, cyp - tt.size.height / 2f), alpha = sc.expand * fa)
                    }
                } else {
                    drawLine(col, Offset(sc.sx[a], sc.sy[a]), Offset(sc.sx[bb], sc.sy[bb]), strokeWidth = width, alpha = alpha)
                }
                sc.arcsDrawn++
            }
        }
    }

    // Engram nodes: ring and core, with a soft glow that only exists far out.
    if (sc.showEngrams) for (a in 0 until sc.areaCount) {
        val al = sc.engA[a]
        if (al < 0.02f) continue
        val col = Color(sc.areaColor[a])
        val x = sc.engX[a]; val y = sc.engY[a]; val r = sc.engR[a]
        val glow = sc.glowAlpha * al
        if (glow > 0.02f) {
            // A stepped radial falloff from three circles: cheap, local, no blur pass.
            drawCircle(col, r * 3.6f, Offset(x, y), alpha = 0.05f * glow)
            drawCircle(col, r * 2.5f, Offset(x, y), alpha = 0.08f * glow)
            drawCircle(col, r * 1.7f, Offset(x, y), alpha = 0.13f * glow)
        }
        drawCircle(col, r * 0.5f, Offset(x, y), alpha = al)
        drawCircle(col, r, Offset(x, y), alpha = 0.9f * al, style = Stroke(1.4f * dens))
        drawCircle(col, r * 1.45f, Offset(x, y), alpha = 0.3f * al * (1f - sc.detail), style = Stroke(0.8f * dens))
    }

    // Memory nodes, back to front.
    for (k in 0 until sc.n) {
        val i = sc.order[k]
        val a = sc.fa[i]
        if (a < 0.02f) continue
        val x = sc.sx[i]; val y = sc.sy[i]; val r = sc.rad[i]
        val col = Color(sc.colors[i])
        // The faint halo behind the selected node (and a little glow only while far).
        if (i == sel) drawCircle(col, r * 2.6f, Offset(x, y), alpha = 0.14f * (1f - 0.5f * sc.detail))
        drawKindGlyph(sc.kinds[i], x, y, r, col, a, b.glyph)
        if (i == sel || sc.isHit(i)) {
            drawCircle(colors.ink, r + 4f * dens, Offset(x, y), alpha = (if (i == sel) 0.9f else 0.6f) * minOf(1f, a * 2f), style = Stroke(1.3f * dens))
            if (i == sel) drawCircle(colors.ink, r + 8f * dens, Offset(x, y), alpha = 0.35f, style = Stroke(0.8f * dens))
        }
    }

    // Engram names: kept while far and mid, quieter once memories take over.
    if (sc.showEngrams && sel < 0) for (a in 0 until sc.areaCount) {
        val al = sc.engLabelA[a]
        if (al < 0.02f) continue
        val t = tm.measure(sc.areaName[a].uppercase(), st.cluster)
        val n = tm.measure(sc.areaTotal[a], st.count)
        val top = sc.engY[a] + sc.engR[a] * 1.5f + 3f * dens
        drawText(t, topLeft = Offset(sc.engX[a] - t.size.width / 2f, top), alpha = al)
        drawText(n, topLeft = Offset(sc.engX[a] - n.size.width / 2f, top + t.size.height), alpha = al * 0.8f)
    }

    // Memory labels: short names, then full names with a snippet as the camera closes in.
    val sz = sc.labelSize
    for (k in 0 until sc.labelCount) {
        val i = sc.labelNode[k]
        if (i < 0) continue // a reserved slot for an engram name
        if (sc.fa[i] < 0.1f) continue
        val a = kotlin.math.sqrt(sc.fa[i]) // dimmed nodes keep a readable name
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
            val d = tm.measure(sc.detailLine[i], st.detail[sz])
            val bw = maxOf(t.size.width, d.size.width)
            val tx = (x - bw / 2f).coerceIn(4f * dens, maxOf(4f * dens, w - bw - 4f * dens))
            val top = sc.sy[i] - r - t.size.height - d.size.height - 3f * dens
            drawText(t, topLeft = Offset(tx, top), alpha = a * fullA * nameA)
            drawText(d, topLeft = Offset(tx, top + t.size.height), alpha = a * fullA * nameA)
        } else {
            val t = tm.measure(sc.shortTitle[i], style)
            val tx = (x - t.size.width / 2f).coerceIn(4f * dens, maxOf(4f * dens, w - t.size.width - 4f * dens))
            val ty = sc.sy[i] - r - t.size.height - 3f * dens
            drawText(t, topLeft = Offset(tx, ty), alpha = a * nameA)
        }
    }

    // A quiet depth readout.
    if (gm > 0.3f) {
        val d = tm.measure(DEPTH_TEXT.getValue(sc.depthName), st.guide)
        drawText(d, topLeft = Offset(12f * dens, 60f * dens), alpha = 0.8f)
    }
}

private fun touchesFocus(sc: Scene, a: Int, b: Int) = sc.focusArea >= 0 && sc.areaOf[a] == sc.focusArea && sc.areaOf[b] == sc.focusArea

private fun DrawScope.drawDebug(c: MapController, st: Styles, tm: TextMeasurer) {
    val sc = c.scene
    val line = "draw %.1f ms (max %.1f) · %d nodes · %d labels · %d arcs · zoom %.1f".format(c.drawMsAvg, c.drawMsMax, sc.nodesDrawn, sc.labelCount, sc.arcsDrawn, c.zoom)
    val t = tm.measure(line, st.debug)
    drawRect(Color.Black, Offset(0f, 78f * density), Size(t.size.width + 12f * density, t.size.height + 6f * density), alpha = 0.6f)
    drawText(t, topLeft = Offset(6f * density, 81f * density), color = Color.White)
}

private const val MAX_ARCS = 160
private const val MAX_LINK_LABELS = 14
