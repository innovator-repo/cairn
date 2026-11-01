package app.cairn.map

import app.cairn.data.Kind
import app.cairn.graph.Graph
import app.cairn.graph.ViewMode
import app.cairn.map.Projector.Companion.smooth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the canvas draws, worked out once per frame into arrays that are allocated once per
 * graph: where each node is on screen, how big and how opaque, which labels fit, and how far the
 * level of detail has moved. Drawing then only reads these, so a frame allocates nothing.
 *
 * Level of detail follows the zoom `u` and cross-fades, so nothing pops:
 *  far (u < 1): area clusters with a big name and a count
 *  mid (u 1.3 to 2.2): single nodes with short names
 *  near (u 2 to 3): link arcs between connected nodes
 *  closer (u 3.2 to 4.5): full names, a one-line snippet and the link count
 *  very near (u 3.8 to 6): the focused cluster spreads out into its own space, links labelled
 */
class Scene {
    val projector = Projector()
    var width = 0f; var height = 0f; var density = 1f

    var n = 0; private set
    private var bound: Graph? = null

    // Per node (size n), allocated in bind.
    private var cur = FloatArray(0)
    var wx = FloatArray(0); private set
    var wy = FloatArray(0); private set
    var wz = FloatArray(0); private set
    var sx = FloatArray(0); private set
    var sy = FloatArray(0); private set
    var z2 = FloatArray(0); private set
    var rad = FloatArray(0); private set
    /** Final opacity per node: facing, depth, dimming and culling combined. */
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
    var detail: Array<String> = emptyArray(); private set
    private var nbMask = BooleanArray(0)
    private var hitMask = BooleanArray(0)
    var selected = -1; private set
    private var anyHit = false

    // Links.
    var la = IntArray(0); private set
    var lb = IntArray(0); private set
    var lt = IntArray(0); private set

    // Areas.
    var areaCount = 0; private set
    var areaName: Array<String> = emptyArray(); private set
    var areaTotal: Array<String> = emptyArray(); private set
    var areaColor = LongArray(0); private set
    private var areaSum = FloatArray(0)
    private var areaN = IntArray(0)
    var areaX = FloatArray(0); private set
    var areaY = FloatArray(0); private set
    var areaA = FloatArray(0); private set
    private var ring = FloatArray(0)

    // Level of detail, read by the canvas.
    var u = 1f; private set
    var gm = 1f; private set
    var clusterAlpha = 1f; private set
    var nameAlpha = 0f; private set
    var arcAlpha = 0f; private set
    var fullAlpha = 0f; private set
    var expand = 0f; private set
    var focusArea = -1; private set
    var nodeScale = 1f; private set
    var linkWidth = 1f; private set

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

    fun bind(g: Graph) {
        bound = g
        n = g.size
        cur = FloatArray(3 * n); wx = FloatArray(n); wy = FloatArray(n); wz = FloatArray(n)
        sx = FloatArray(n); sy = FloatArray(n); z2 = FloatArray(n); rad = FloatArray(n); fa = FloatArray(n)
        order = IntArray(n) { it }
        degree = g.degree.copyOf()
        areaOf = g.areaIndex.copyOf()
        elev = FloatArray(n) { 0.025f + 0.05f * min(1f, degree[it] / 6f) }
        lift = FloatArray(n) { ((g.entries[it].id.hashCode() ushr 3) % 100) / 100f * 0.03f }
        colors = LongArray(n) { g.areas[areaOf[it]].color }
        kinds = Array(n) { g.entries[it].kind }
        priority = (0 until n).sortedByDescending { degree[it] }.toIntArray()
        shortTitle = Array(n) { cut(g.entries[it].title, 16) }
        fullTitle = Array(n) { cut(g.entries[it].title, 34) }
        detail = Array(n) { "${cut(g.entries[it].text.ifBlank { g.entries[it].path }, 44)} · ${degree[it]} links" }
        nbMask = BooleanArray(n); hitMask = BooleanArray(n); selected = -1; anyHit = false
        la = IntArray(g.links.size) { g.links[it][0] }
        lb = IntArray(g.links.size) { g.links[it][1] }
        lt = IntArray(g.links.size) { g.links[it].getOrElse(2) { 0 } }
        areaCount = g.areas.size
        areaName = Array(areaCount) { g.areas[it].name }
        areaTotal = Array(areaCount) { "${g.areas[it].count}" }
        areaColor = LongArray(areaCount) { g.areas[it].color }
        areaSum = FloatArray(3 * areaCount); areaN = IntArray(areaCount)
        areaX = FloatArray(areaCount); areaY = FloatArray(areaCount); areaA = FloatArray(areaCount)
        val spread = (0.55f / sqrt(max(1, areaCount).toFloat())).coerceIn(0.12f, 0.3f)
        val rho = (spread * 1.9f).coerceIn(0.22f, 0.6f)
        ring = FloatArray(areaCount * RING_N * 3)
        for (a in 0 until areaCount) {
            val lat = g.areas[a].lat; val lon = g.areas[a].lon
            val c = Graph.vec(lat, lon)
            val e1 = floatArrayOf(cos(lon), 0f, -sin(lon))
            val e2 = floatArrayOf(c[1] * e1[2] - c[2] * e1[1], c[2] * e1[0] - c[0] * e1[2], c[0] * e1[1] - c[1] * e1[0])
            for (k in 0 until RING_N) {
                val th = k.toFloat() / RING_N * (2 * PI).toFloat()
                val ct = cos(th); val st = sin(th)
                val p = FloatArray(3) { c[it] * cos(rho) + (e1[it] * ct + e2[it] * st) * sin(rho) }
                val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
                for (d in 0..2) ring[(a * RING_N + k) * 3 + d] = p[d] / l * 1.0f
            }
        }
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

        clusterAlpha = 1f - smooth(0.9f, 1.6f, u)
        nameAlpha = smooth(1.3f, 2.2f, u)
        arcAlpha = smooth(2.0f, 3.0f, u)
        fullAlpha = smooth(3.2f, 4.5f, u)
        expand = smooth(3.8f, 6f, u) * gm
        nodeScale = (0.55f + 0.45f * sqrt(u)).coerceIn(0.5f, 2.6f)
        linkWidth = (0.6f + 0.25f * kotlin.math.ln(1f + u)) * dens
        labelSize = if (u < 2f) 0 else if (u < 3.5f) 1 else if (u < 6f) 2 else 3

        // Current positions (mid-morph) and the area centres.
        java.util.Arrays.fill(areaSum, 0f); java.util.Arrays.fill(areaN, 0)
        for (i in 0 until n) {
            c.position(i, t3)
            cur[3 * i] = t3[0]; cur[3 * i + 1] = t3[1]; cur[3 * i + 2] = t3[2]
            val a = areaOf[i]
            areaSum[3 * a] += t3[0]; areaSum[3 * a + 1] += t3[1]; areaSum[3 * a + 2] += t3[2]; areaN[a]++
        }
        for (a in 0 until areaCount) if (areaN[a] > 0) {
            val d = areaN[a].toFloat()
            areaSum[3 * a] = areaSum[3 * a] / d; areaSum[3 * a + 1] = areaSum[3 * a + 1] / d; areaSum[3 * a + 2] = areaSum[3 * a + 2] / d
        }

        // Which cluster is in focus: the selected node's, else the one facing the viewer.
        focusArea = -1
        if (expand > 0.001f) {
            focusArea = if (selected in 0 until n) areaOf[selected] else facingArea()
        }
        var cenX = 0f; var cenY = 0f; var cenZ = 0f
        if (focusArea >= 0) {
            val l = sqrt(areaSum[3 * focusArea].let { it * it } + areaSum[3 * focusArea + 1].let { it * it } + areaSum[3 * focusArea + 2].let { it * it }).coerceAtLeast(1e-4f)
            cenX = areaSum[3 * focusArea] / l; cenY = areaSum[3 * focusArea + 1] / l; cenZ = areaSum[3 * focusArea + 2] / l
            // Open the cluster around the selected node so it stays where the camera is looking.
            if (selected in 0 until n && areaOf[selected] == focusArea) {
                val sl = sqrt(cur[3 * selected] * cur[3 * selected] + cur[3 * selected + 1] * cur[3 * selected + 1] + cur[3 * selected + 2] * cur[3 * selected + 2]).coerceAtLeast(1e-4f)
                cenX = cur[3 * selected] / sl; cenY = cur[3 * selected + 1] / sl; cenZ = cur[3 * selected + 2] / sl
            }
        }

        val margin = 24f * dens
        var drawn = 0
        for (i in 0 until n) {
            var x = cur[3 * i]; var y = cur[3 * i + 1]; var z = cur[3 * i + 2]
            val e = elev[i] * gm
            if (focusArea >= 0 && areaOf[i] == focusArea) {
                val k = 1f + expand * EXPAND
                var qx = cenX + (x - cenX) * k; var qy = cenY + (y - cenY) * k; var qz = cenZ + (z - cenZ) * k
                val l = sqrt(qx * qx + qy * qy + qz * qz).coerceAtLeast(1e-4f)
                val r = 1f + e + expand * lift[i]
                x = qx / l * r; y = qy / l * r; z = qz / l * r
            } else {
                val r = 1f + e
                x *= r; y *= r; z *= r
            }
            wx[i] = x; wy[i] = y; wz[i] = z
            pr.project(x, y, z, t5)
            sx[i] = t5[0]; sy[i] = t5[1]; z2[i] = t5[2]
            val ds = t5[3]
            val away = ((pr.horizon - 0.02f - t5[2]) / 0.2f).coerceIn(0f, 1f)
            val face = 1f - gm * away
            val shade = 1f - gm * 0.45f * ((1f - t5[2]) / (1f - pr.horizon + 0.05f)).coerceIn(0f, 1f)
            rad[i] = (3f + min(6f, degree[i].toFloat())) * nodeScale * max(0.3f, Math.pow(ds.toDouble(), 0.6).toFloat()) * dens * 0.95f
            var a = face * shade * t5[4]
            if (t5[0] < -margin || t5[0] > w + margin || t5[1] < -margin || t5[1] > h + margin) a = 0f
            if (c.isolated >= 0 && areaOf[i] != c.isolated) a *= 0.16f
            else if (selected >= 0 && !nbMask[i]) a *= 0.16f
            else if (anyHit && !hitMask[i]) a *= 0.16f
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

        // Area label anchors.
        for (a in 0 until areaCount) {
            if (areaN[a] == 0) { areaA[a] = 0f; continue }
            var mx = areaSum[3 * a]; var my = areaSum[3 * a + 1]; var mz = areaSum[3 * a + 2]
            val len = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-4f)
            val s = 1f + (1f / len - 1f) * gm
            mx *= s; my *= s; mz *= s
            val r = 1f + 0.05f * gm
            pr.project(mx * r, my * r, mz * r, t5)
            areaX[a] = t5[0]; areaY[a] = t5[1] - 0.12f * pr.silhouette * clusterAlpha
            val away = ((pr.horizon + 0.05f - t5[2]) / 0.3f).coerceIn(0f, 1f)
            var alpha = clusterAlpha * (1f - gm * away) * t5[4]
            if (c.isolated >= 0 && c.isolated != a) alpha *= 0.2f
            areaA[a] = alpha
        }

        placeLabels(c, w, h, dens)
    }

    private fun facingArea(): Int {
        var best = -1; var bz = -2f
        for (a in 0 until areaCount) {
            if (areaN[a] == 0) continue
            val l = sqrt(areaSum[3 * a] * areaSum[3 * a] + areaSum[3 * a + 1] * areaSum[3 * a + 1] + areaSum[3 * a + 2] * areaSum[3 * a + 2]).coerceAtLeast(1e-4f)
            projector.project(areaSum[3 * a] / l, areaSum[3 * a + 1] / l, areaSum[3 * a + 2] / l, t5)
            if (t5[2] > bz) { bz = t5[2]; best = a }
        }
        return best
    }

    private fun fontPx(dens: Float) = (10f + labelSize) * dens

    private fun addLabel(i: Int, full: Boolean, forced: Boolean, w: Float, h: Float): Boolean {
        if (labelCount >= MAX_LABELS) return false
        val fp = fontPx(density)
        val text = if (full) fullTitle[i] else shortTitle[i]
        val tw = text.length * fp * 0.52f + 8f * density
        val th = fp * 1.35f * (if (full && fullAlpha > 0.3f) 2f else 1f)
        val x0 = (sx[i] - tw / 2f).coerceIn(4f * density, max(4f * density, w - tw - 4f * density))
        val y0 = sy[i] - rad[i] - th - 3f * density
        if (y0 < 0f || y0 + th > h) return false
        if (!forced) for (k in 0 until labelCount) {
            if (x0 < lx1[k] && x0 + tw > lx0[k] && y0 < ly1[k] && y0 + th > ly0[k]) return false
        }
        lx0[labelCount] = x0; ly0[labelCount] = y0; lx1[labelCount] = x0 + tw; ly1[labelCount] = y0 + th
        labelNode[labelCount] = i; labelFull[labelCount] = full; labelForced[labelCount] = forced
        labelCount++
        return true
    }

    private fun placeLabels(c: MapController, w: Float, h: Float, dens: Float) {
        labelCount = 0
        if (n == 0) return
        val sel = selected
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
            val cap = min(MAX_LABELS - 1, (5f + 6f * u).toInt())
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
        var best = -1; var bestD = Float.MAX_VALUE
        for (k in n - 1 downTo 0) {
            val i = order[k]
            if (fa[i] < 0.14f) continue
            val d = hypot(sx[i] - x, sy[i] - y)
            if (d < max(24f * dens, rad[i] + 10f * dens) && d < bestD) { bestD = d; best = i }
        }
        return best
    }

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

    /** Fills [out] with this area's guide ring as screen points; returns how many are in front. */
    fun ringPoint(a: Int, k: Int, out: FloatArray): Boolean {
        val o = (a * RING_N + k) * 3
        projector.project(ring[o], ring[o + 1], ring[o + 2], out)
        return out[2] > projector.horizon * gm && out[4] > 0f
    }

    /** Control point for an arc between two nodes, lifted off the surface. */
    fun arcControl(a: Int, b: Int, out: FloatArray) {
        val mx = (wx[a] + wx[b]) / 2f; val my = (wy[a] + wy[b]) / 2f; val mz = (wz[a] + wz[b]) / 2f
        if (gm < 0.5f) { projector.project(mx, my, mz, out); return }
        val l = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-4f)
        val chord = hypot(hypot(wx[a] - wx[b], wy[a] - wy[b]), wz[a] - wz[b])
        val r = 1.08f + 0.35f * chord
        projector.project(mx / l * r, my / l * r, mz / l * r, out)
    }

    companion object {
        const val MAX_LABELS = 48
        const val RING_N = 40
        const val EXPAND = 1.8f
    }
}
