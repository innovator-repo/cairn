package app.cairn.map

import app.cairn.data.Kind
import app.cairn.graph.Graph
import app.cairn.graph.ViewMode
import app.cairn.map.Projector.Companion.smooth
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Everything the canvas draws, worked out once per frame into arrays that are allocated once per
 * graph: where each node is on screen, how big and how opaque, which labels fit, and how far the
 * level of detail has moved. Drawing then only reads these, so a frame allocates nothing.
 *
 * Two levels: an area is an "engram" (a larger ring node with a name) and its notes, repos, files
 * and issues are "memories" around it. Detail follows the zoom `u` and cross-fades, so nothing pops:
 *  FAR   (u under 1.4): engram nodes with soft glow and names; memories folded into their engram and faint
 *  MID   (1.4 to 3):    memories spread out and show short names; glow, particles and shells fade
 *  NEAR  (3 to 6):      link arcs appear, full names and a one-line snippet, crisp solid shapes
 *  CLOSE (over 6):      the focused cluster opens up with labelled links
 */
class Scene {
    val projector = Projector()
    var width = 0f; var height = 0f; var density = 1f

    var n = 0; private set
    private var bound: Graph? = null
    /** Seconds of animation clock; only advances while motion is on. */
    var time = 0f; private set
    var motion = false

    // Per node.
    private var cur = FloatArray(0)
    var wx = FloatArray(0); private set
    var wy = FloatArray(0); private set
    var wz = FloatArray(0); private set
    var sx = FloatArray(0); private set
    var sy = FloatArray(0); private set
    var z2 = FloatArray(0); private set
    var rad = FloatArray(0); private set
    /** Final opacity per node: facing, depth, dimming, memory fade and culling combined. */
    var fa = FloatArray(0); private set
    var order = IntArray(0); private set
    private var elev = FloatArray(0)
    private var lift = FloatArray(0)
    var colors = LongArray(0); private set
    var kinds: Array<Kind> = emptyArray(); private set
    var areaOf = IntArray(0); private set
    private var degree = IntArray(0)
    private var priority = IntArray(0)
    var shortTitle: Array<String> = emptyArray(); private set
    var fullTitle: Array<String> = emptyArray(); private set
    var detailLine: Array<String> = emptyArray(); private set
    private var nbMask = BooleanArray(0)
    private var hitMask = BooleanArray(0)
    var selected = -1; private set
    private var anyHit = false

    // Links between memories.
    var la = IntArray(0); private set
    var lb = IntArray(0); private set
    var lt = IntArray(0); private set

    // Engrams (areas).
    var areaCount = 0; private set
    var areaName: Array<String> = emptyArray(); private set
    var areaTotal: Array<String> = emptyArray(); private set
    var areaColor = LongArray(0); private set
    private var areaSum = FloatArray(0)
    private var areaN = IntArray(0)
    private var acx = FloatArray(0); private var acy = FloatArray(0); private var acz = FloatArray(0)
    var ex = FloatArray(0); private set
    var ey = FloatArray(0); private set
    var ez = FloatArray(0); private set
    var engX = FloatArray(0); private set
    var engY = FloatArray(0); private set
    var engR = FloatArray(0); private set
    var engA = FloatArray(0); private set
    var engLabelA = FloatArray(0); private set
    /** Area pairs joined by at least one link, for the long arcs between engrams. */
    var pairA = IntArray(0); private set
    var pairB = IntArray(0); private set

    // Level of detail, read by the canvas.
    var u = 1f; private set
    var gm = 1f; private set
    /** 0 far away, 1 close: glow, halos, particles and shells fade as this rises. */
    var detail = 0f; private set
    var depthName = "FAR"; private set
    var memoryAlpha = 1f; private set
    var glowAlpha = 1f; private set
    var decorAlpha = 1f; private set
    var nameAlpha = 0f; private set
    var arcAlpha = 0f; private set
    var fullAlpha = 0f; private set
    var expand = 0f; private set
    var focusArea = -1; private set
    var nodeScale = 1f; private set
    var linkWidth = 1f; private set
    var showEngrams = true; private set

    // Label placement (indices into the node arrays).
    var labelCount = 0; private set
    var labelNode = IntArray(MAX_LABELS); private set
    var labelFull = BooleanArray(MAX_LABELS); private set
    var labelForced = BooleanArray(MAX_LABELS); private set
    var labelSize = 0; private set
    private val lx0 = FloatArray(MAX_LABELS); private val ly0 = FloatArray(MAX_LABELS)
    private val lx1 = FloatArray(MAX_LABELS); private val ly1 = FloatArray(MAX_LABELS)
    var arcsDrawn = 0
    var nodesDrawn = 0; private set

    private val t5 = FloatArray(5)
    private val t3 = FloatArray(3)

    fun advance(dt: Float) { if (motion) time += dt }

    fun bind(g: Graph) {
        bound = g
        n = g.size
        cur = FloatArray(3 * n); wx = FloatArray(n); wy = FloatArray(n); wz = FloatArray(n)
        sx = FloatArray(n); sy = FloatArray(n); z2 = FloatArray(n); rad = FloatArray(n); fa = FloatArray(n)
        order = IntArray(n) { it }
        degree = g.degree.copyOf()
        areaOf = g.areaIndex.copyOf()
        elev = FloatArray(n) { 0.02f + 0.04f * min(1f, degree[it] / 6f) }
        lift = FloatArray(n) { ((g.entries[it].id.hashCode() ushr 3) % 100) / 100f * 0.03f }
        colors = LongArray(n) { g.areas[areaOf[it]].color }
        kinds = Array(n) { g.entries[it].kind }
        priority = (0 until n).sortedByDescending { degree[it] }.toIntArray()
        shortTitle = Array(n) { cut(g.entries[it].title, 16) }
        fullTitle = Array(n) { cut(g.entries[it].title, 34) }
        detailLine = Array(n) { "${cut(g.entries[it].text.ifBlank { g.entries[it].path }, 44)} · ${degree[it]} links" }
        nbMask = BooleanArray(n); hitMask = BooleanArray(n); selected = -1; anyHit = false
        la = IntArray(g.links.size) { g.links[it][0] }
        lb = IntArray(g.links.size) { g.links[it][1] }
        lt = IntArray(g.links.size) { g.links[it].getOrElse(2) { 0 } }
        areaCount = g.areas.size
        areaName = Array(areaCount) { g.areas[it].name }
        areaTotal = Array(areaCount) { "${g.areas[it].count}" }
        areaColor = LongArray(areaCount) { g.areas[it].color }
        areaSum = FloatArray(3 * areaCount); areaN = IntArray(areaCount)
        acx = FloatArray(areaCount); acy = FloatArray(areaCount); acz = FloatArray(areaCount)
        ex = FloatArray(areaCount); ey = FloatArray(areaCount); ez = FloatArray(areaCount)
        engX = FloatArray(areaCount); engY = FloatArray(areaCount); engR = FloatArray(areaCount)
        engA = FloatArray(areaCount); engLabelA = FloatArray(areaCount)
        val seen = HashSet<Int>()
        val pa = ArrayList<Int>(); val pb = ArrayList<Int>()
        for (k in la.indices) {
            val x = areaOf[la[k]]; val y = areaOf[lb[k]]
            if (x == y) continue
            val lo = min(x, y); val hi = max(x, y)
            if (seen.add(lo * 64 + hi)) { pa += lo; pb += hi }
        }
        pairA = pa.toIntArray(); pairB = pb.toIntArray()
    }

    private fun cut(s: String, max: Int) = if (s.length > max) s.take(max - 1) + "…" else s

    fun setSelection(i: Int) {
        selected = i
        java.util.Arrays.fill(nbMask, false)
        if (i in 0 until n) {
            nbMask[i] = true
            for (k in la.indices) { if (la[k] == i) nbMask[lb[k]] = true else if (lb[k] == i) nbMask[la[k]] = true }
        }
    }

    fun setHits(h: Set<Int>) {
        java.util.Arrays.fill(hitMask, false)
        for (i in h) if (i in 0 until n) hitMask[i] = true
        anyHit = h.isNotEmpty()
    }

    fun isNeighbour(i: Int) = i in 0 until n && nbMask[i]
    fun isHit(i: Int) = i in 0 until n && hitMask[i]

    /** Work out the whole frame. */
    fun frame(c: MapController, w: Float, h: Float, dens: Float) {
        val g = c.graph
        if (g !== bound) bind(g)
        width = w; height = h; density = dens
        u = c.zoom; gm = c.globeMix
        val pr = projector
        pr.set(c.yaw, c.pitch, c.roll, u, gm, w, h, c.panX, c.panY)

        detail = smooth(1.0f, 4.0f, u)
        depthName = if (u < 1.4f) "FAR" else if (u < 3f) "MID" else if (u < 6f) "NEAR" else "CLOSE"
        memoryAlpha = 0.12f + 0.88f * smooth(0.9f, 1.8f, u)
        glowAlpha = 1f - smooth(1.2f, 3f, u)
        decorAlpha = 1f - 0.95f * detail
        nameAlpha = smooth(1.3f, 2.2f, u)
        arcAlpha = smooth(2.0f, 3.2f, u)
        fullAlpha = smooth(3.2f, 4.5f, u)
        // The focused cluster opens as you come near, then settles so the close view stays populated.
        expand = smooth(2.6f, 4.5f, u) * (1f - smooth(4.5f, 8f, u)) * gm
        val spread = smooth(0.9f, 2.0f, u)
        nodeScale = (0.55f + 0.4f * sqrt(u)).coerceIn(0.5f, 2.4f)
        linkWidth = (0.6f + 0.2f * ln(1f + u)) * dens
        labelSize = if (u < 2f) 0 else if (u < 3.5f) 1 else if (u < 6f) 2 else 3
        showEngrams = gm > 0.5f || c.view == ViewMode.AREAS

        // Current positions (mid-morph) and the area centres.
        java.util.Arrays.fill(areaSum, 0f); java.util.Arrays.fill(areaN, 0)
        for (i in 0 until n) {
            c.position(i, t3)
            cur[3 * i] = t3[0]; cur[3 * i + 1] = t3[1]; cur[3 * i + 2] = t3[2]
            val a = areaOf[i]
            areaSum[3 * a] += t3[0]; areaSum[3 * a + 1] += t3[1]; areaSum[3 * a + 2] += t3[2]; areaN[a]++
        }
        for (a in 0 until areaCount) {
            if (areaN[a] == 0) continue
            val d = areaN[a].toFloat()
            var mx = areaSum[3 * a] / d; var my = areaSum[3 * a + 1] / d; var mz = areaSum[3 * a + 2] / d
            val len = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-4f)
            val s = 1f + (1f / len - 1f) * gm
            mx *= s; my *= s; mz *= s
            acx[a] = mx; acy[a] = my; acz[a] = mz
            val r = 1f + 0.05f * gm
            ex[a] = mx * r; ey[a] = my * r; ez[a] = mz * r
        }

        focusArea = -1
        if (expand > 0.001f) focusArea = if (selected in 0 until n) areaOf[selected] else facingArea()
        var selCx = 0f; var selCy = 0f; var selCz = 0f
        val openAroundSelected = focusArea >= 0 && selected in 0 until n && areaOf[selected] == focusArea
        if (openAroundSelected) {
            val sl = sqrt(cur[3 * selected] * cur[3 * selected] + cur[3 * selected + 1] * cur[3 * selected + 1] + cur[3 * selected + 2] * cur[3 * selected + 2]).coerceAtLeast(1e-4f)
            selCx = cur[3 * selected] / sl; selCy = cur[3 * selected + 1] / sl; selCz = cur[3 * selected + 2] / sl
        }

        val margin = 24f * dens
        var drawn = 0
        for (i in 0 until n) {
            val a0 = areaOf[i]
            var x = cur[3 * i]; var y = cur[3 * i + 1]; var z = cur[3 * i + 2]
            val inFocus = a0 == focusArea
            // Memories fold towards their engram when far and spread out as the camera nears.
            var k = 1f - (1f - spread) * 0.7f * gm
            var cx = acx[a0]; var cy = acy[a0]; var cz = acz[a0]
            if (inFocus) {
                k *= 1f + expand * EXPAND
                if (openAroundSelected) { cx = selCx; cy = selCy; cz = selCz }
            }
            x = cx + (x - cx) * k; y = cy + (y - cy) * k; z = cz + (z - cz) * k
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-4f)
            val s = 1f + (1f / l - 1f) * gm
            val r = 1f + elev[i] * gm + (if (inFocus) expand * lift[i] else 0f)
            x *= s * r; y *= s * r; z *= s * r
            wx[i] = x; wy[i] = y; wz[i] = z
            pr.project(x, y, z, t5)
            sx[i] = t5[0]; sy[i] = t5[1]; z2[i] = t5[2]
            val ds = t5[3]
            val away = ((pr.horizon - 0.02f - t5[2]) / 0.2f).coerceIn(0f, 1f)
            val face = 1f - gm * away
            val shade = 1f - gm * 0.45f * ((1f - t5[2]) / (1f - pr.horizon + 0.05f)).coerceIn(0f, 1f)
            rad[i] = (2.6f + min(5f, degree[i].toFloat())) * nodeScale * max(0.3f, Math.pow(ds.toDouble(), 0.6).toFloat()) * dens * 0.9f
            var a = face * shade * t5[4] * (1f - (1f - memoryAlpha) * gm)
            if (t5[0] < -margin || t5[0] > w + margin || t5[1] < -margin || t5[1] > h + margin) a = 0f
            if (c.isolated >= 0 && areaOf[i] != c.isolated) a *= 0.14f
            else if (anyHit && !hitMask[i]) a *= 0.16f
            else if (selected >= 0 && !nbMask[i]) a *= 0.5f
            else if (focusArea >= 0 && areaOf[i] != focusArea) a *= 1f - 0.6f * expand
            fa[i] = a
            if (a > 0.02f) drawn++
        }
        nodesDrawn = drawn

        // Back to front. Insertion sort: the order changes little from one frame to the next.
        for (k in 1 until n) {
            val key = order[k]; val z = z2[key]
            var j = k - 1
            while (j >= 0 && z2[order[j]] > z) { order[j + 1] = order[j]; j-- }
            order[j + 1] = key
        }

        // Engram nodes and their name labels.
        for (a in 0 until areaCount) {
            if (areaN[a] == 0 || !showEngrams) { engA[a] = 0f; engLabelA[a] = 0f; continue }
            pr.project(ex[a], ey[a], ez[a], t5)
            engX[a] = t5[0]; engY[a] = t5[1]
            val ds = t5[3]
            engR[a] = (6f + min(7f, sqrt(areaN[a].toFloat()))) * dens * max(0.4f, Math.pow(ds.toDouble(), 0.5).toFloat()) * (1f - 0.45f * detail)
            val away = ((pr.horizon + 0.02f - t5[2]) / 0.25f).coerceIn(0f, 1f)
            var alpha = (1f - gm * away) * t5[4]
            if (c.isolated >= 0 && c.isolated != a) alpha *= 0.2f
            if (focusArea >= 0 && a != focusArea) alpha *= 1f - 0.5f * expand
            engA[a] = alpha
            engLabelA[a] = alpha * (1f - 0.75f * smooth(2.5f, 5f, u))
        }

        placeLabels(c, w, h)
    }

    private fun facingArea(): Int {
        var best = -1; var bz = -2f
        for (a in 0 until areaCount) {
            if (areaN[a] == 0) continue
            projector.project(acx[a], acy[a], acz[a], t5)
            if (t5[2] > bz) { bz = t5[2]; best = a }
        }
        return best
    }

    private fun fontPx(dens: Float) = (10f + labelSize) * dens

    private fun addLabel(i: Int, full: Boolean, forced: Boolean, w: Float, h: Float): Boolean {
        if (labelCount >= MAX_LABELS) return false
        val fp = fontPx(density)
        val text = if (full) fullTitle[i] else shortTitle[i]
        // A full label is two lines; its second line (the snippet) is usually the wider one.
        val chars = if (full && fullAlpha > 0.3f) max(text.length.toFloat(), detailLine[i].length * 0.9f) else text.length.toFloat()
        val tw = chars * fp * 0.52f + 8f * density
        val th = fp * 1.35f * (if (full && fullAlpha > 0.3f) 2f else 1f)
        val x0 = (sx[i] - tw / 2f).coerceIn(4f * density, max(4f * density, w - tw - 4f * density))
        val y0 = sy[i] - rad[i] - th - 3f * density
        // Keep names out of the bands the search field and the legend cover.
        if (!forced && (y0 < TOP_RESERVE * density || y0 + th > h - BOTTOM_RESERVE * density)) return false
        if (y0 < 0f || y0 + th > h) return false
        if (!forced) for (k in 0 until labelCount) {
            if (x0 < lx1[k] && x0 + tw > lx0[k] && y0 < ly1[k] && y0 + th > ly0[k]) return false
        }
        lx0[labelCount] = x0; ly0[labelCount] = y0; lx1[labelCount] = x0 + tw; ly1[labelCount] = y0 + th
        labelNode[labelCount] = i; labelFull[labelCount] = full; labelForced[labelCount] = forced
        labelCount++
        return true
    }

    private fun placeLabels(c: MapController, w: Float, h: Float) {
        labelCount = 0
        if (n == 0) return
        val sel = selected
        // Reserve room for the engram names so memory names do not run into them.
        var reserved = 0
        if (showEngrams && sel < 0) for (a in 0 until areaCount) {
            if (engLabelA[a] < 0.3f || labelCount >= MAX_LABELS) continue
            val bw = (areaName[a].length * 11f + 12f) * density
            val x0 = engX[a] - bw / 2f; val y0 = engY[a] + engR[a] * 1.5f
            lx0[labelCount] = x0; ly0[labelCount] = y0; lx1[labelCount] = x0 + bw; ly1[labelCount] = y0 + 32f * density
            labelNode[labelCount] = -1; labelFull[labelCount] = false; labelForced[labelCount] = true
            labelCount++; reserved++
        }
        if (sel in 0 until n && fa[sel] > 0.3f) {
            addLabel(sel, true, true, w, h)
            var k = 0
            for (j in la.indices) {
                if (k >= 4) break
                val o = if (la[j] == sel) lb[j] else if (lb[j] == sel) la[j] else -1
                if (o >= 0 && fa[o] > 0.3f && addLabel(o, false, false, w, h)) k++
            }
        } else if (anyHit) {
            var k = 0
            for (i in priority) { if (k >= 6) break; if (hitMask[i] && fa[i] > 0.3f && addLabel(i, false, false, w, h)) k++ }
        } else if (nameAlpha > 0.02f) {
            val cap = min(MAX_LABELS - 1, reserved + (5f + 6f * u).toInt())
            val cx = w / 2f; val cy = h / 2f; val central = 0.5f * min(w, h)
            var fullCount = 0
            for (i in priority) {
                if (labelCount >= cap) break
                if (fa[i] < 0.5f) continue
                val full = fullAlpha > 0.02f && fullCount < 8 && hypot(sx[i] - cx, sy[i] - cy) < central
                if (addLabel(i, full, false, w, h) && full) fullCount++
            }
        }
    }

    /** The node under a screen position, or -1. Uses the last frame. */
    fun hit(x: Float, y: Float, dens: Float): Int {
        if (gm > 0.5f && memoryAlpha < 0.4f) return -1 // far away only the engrams can be tapped
        var best = -1; var bestD = Float.MAX_VALUE
        for (k in n - 1 downTo 0) {
            val i = order[k]
            if (fa[i] < 0.14f) continue
            val d = hypot(sx[i] - x, sy[i] - y)
            if (d < max(24f * dens, rad[i] + 10f * dens) && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** The engram (area) under a screen position, or -1. */
    fun hitEngram(x: Float, y: Float, dens: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (a in 0 until areaCount) {
            if (engA[a] < 0.3f) continue
            val d = hypot(engX[a] - x, engY[a] - y)
            if (d < max(30f * dens, engR[a] + 12f * dens) && d < bestD) { bestD = d; best = a }
        }
        return best
    }

    /** The engram nearest a screen position within [maxPx], or -1. */
    fun nearestEngram(x: Float, y: Float, maxPx: Float): Int {
        var best = -1; var bd = maxPx
        for (a in 0 until areaCount) {
            if (engA[a] < 0.3f) continue
            val d = hypot(engX[a] - x, engY[a] - y)
            if (d < bd) { bd = d; best = a }
        }
        return best
    }

    /** The unit-ish world position of an area's centre, for flying to it. */
    fun areaCentre(a: Int, out: FloatArray) { out[0] = acx[a]; out[1] = acy[a]; out[2] = acz[a] }

    /** The visible node nearest a screen position within [maxPx], or -1. */
    fun nearestScreen(x: Float, y: Float, maxPx: Float): Int {
        var best = -1; var bd = maxPx
        for (i in 0 until n) {
            if (fa[i] < 0.3f) continue
            val d = hypot(sx[i] - x, sy[i] - y)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /** Control point for an arc between two world points, lifted off the surface. */
    fun arcControlPts(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, out: FloatArray) {
        val mx = (ax + bx) / 2f; val my = (ay + by) / 2f; val mz = (az + bz) / 2f
        if (gm < 0.5f) { projector.project(mx, my, mz, out); return }
        val l = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-4f)
        val chord = hypot(hypot(ax - bx, ay - by), az - bz)
        val r = 1.08f + 0.35f * chord
        projector.project(mx / l * r, my / l * r, mz / l * r, out)
    }

    fun arcControl(a: Int, b: Int, out: FloatArray) = arcControlPts(wx[a], wy[a], wz[a], wx[b], wy[b], wz[b], out)

    companion object {
        const val MAX_LABELS = 64
        const val TOP_RESERVE = 88f
        const val BOTTOM_RESERVE = 100f
        const val EXPAND = 0.9f
    }
}
