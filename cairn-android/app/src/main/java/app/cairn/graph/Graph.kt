package app.cairn.graph

import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.data.NoteStore
import app.cairn.search.Embedder
import app.cairn.search.dot
import java.util.Random
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class ViewMode(val label: String) {
    GLOBE("Globe"), RINGS("Rings"), AREAS("Areas"), LINKS("Links"), TIMELINE("Timeline")
}

/** An area: its flat colour and where it sits on the globe. */
data class AreaInfo(val name: String, val color: Long, val lat: Float, val lon: Float, val count: Int)

/**
 * Everything the map draws: the nodes, how they link, and where each node sits in every view.
 * Positions are x, y, z triples per node; globe positions lie on the unit sphere, the flat views
 * lie roughly in the unit square with a hair of z.
 */
class Graph(
    val entries: List<Entry>,
    val areas: List<AreaInfo>,
    val links: List<IntArray>,
    val degree: IntArray,
    val positions: Map<ViewMode, FloatArray>,
    val areaIndex: IntArray,
) {
    val size: Int get() = entries.size
    fun neighbours(i: Int): List<Int> = links.mapNotNull { if (it[0] == i) it[1] else if (it[1] == i) it[0] else null }

    companion object {
        val EMPTY = Graph(emptyList(), emptyList(), emptyList(), IntArray(0), ViewMode.entries.associateWith { FloatArray(0) }, IntArray(0))

        /** The amber-gold family used on the map, with a hue shift between areas. */
        val PALETTE = app.cairn.map.MapPalette.AREAS

        fun build(entries: List<Entry>, embedder: Embedder, now: Long = System.currentTimeMillis()): Graph {
            if (entries.isEmpty()) return EMPTY
            val n = entries.size
            val areas = buildAreas(entries)
            val areaIdx = IntArray(n) { i -> areas.indexOfFirst { it.name == entries[i].area } }
            val links = buildLinks(entries, areaIdx, embedder)
            val degree = IntArray(n)
            for (l in links) { degree[l[0]]++; degree[l[1]]++ }
            val globe = FloatArray(3 * n)
            val rings = FloatArray(3 * n)
            val areasPos = FloatArray(3 * n)
            val time = FloatArray(3 * n)
            val maxAge = max(1.0, entries.maxOf { (now - it.updatedAt) / NoteStore.DAY.toDouble() })
            // Tight, atom-like clusters; a big area (a GitHub account with hundreds of items) gets a wider one.
            val spreadBase = (0.34f / sqrt(areas.size.toFloat())).coerceIn(0.08f, 0.2f)
            for (i in 0 until n) {
                val e = entries[i]
                val a = areas[areaIdx[i]]
                val rnd = Random(e.id.hashCode().toLong() * 31 + 7)
                val c = vec(a.lat, a.lon)
                val e1 = floatArrayOf(cos(a.lon), 0f, -sin(a.lon))
                val e2 = floatArrayOf(c[1] * e1[2] - c[2] * e1[1], c[2] * e1[0] - c[0] * e1[2], c[0] * e1[1] - c[1] * e1[0])
                val spread = spreadBase * sqrt(a.count / 25f).coerceIn(0.8f, 2.5f)
                val x = gauss(rnd) * spread
                val y = gauss(rnd) * spread
                val p = FloatArray(3) { c[it] + e1[it] * x + e2[it] * y }
                val len = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
                for (k in 0..2) globe[3 * i + k] = p[k] / len

                val sector = areaIdx[i].toFloat() / areas.size * TAU - HALF_PI
                val ang = sector + (rnd.nextFloat() - .5f) * min(1.05f, TAU / areas.size * .8f)
                val rr = RING_RADIUS.getValue(e.kind)
                rings[3 * i] = cos(ang) * rr; rings[3 * i + 1] = sin(ang) * rr; rings[3 * i + 2] = (rnd.nextFloat() - .5f) * .06f

                val cr = if (areas.size == 1) 0f else .58f
                val cx = cos(sector) * cr
                val cy = sin(sector) * cr
                val r = sqrt(rnd.nextFloat()) * (if (areas.size == 1) .6f else .27f)
                val th = rnd.nextFloat() * TAU
                areasPos[3 * i] = cx + cos(th) * r; areasPos[3 * i + 1] = cy + sin(th) * r; areasPos[3 * i + 2] = (rnd.nextFloat() - .5f) * .06f

                val age = ((now - e.updatedAt) / NoteStore.DAY.toDouble()).coerceAtLeast(0.0)
                val row = min(.42f, 1.7f / max(1, areas.size))
                time[3 * i] = (1 - age / maxAge * 2).toFloat()
                time[3 * i + 1] = (areaIdx[i] - (areas.size - 1) / 2f) * row + (rnd.nextFloat() - .5f) * row * .7f
                time[3 * i + 2] = (rnd.nextFloat() - .5f) * .06f
            }
            val web = forceLayout(areasPos, links, n)
            return Graph(
                entries, areas, links, degree,
                mapOf(ViewMode.GLOBE to globe, ViewMode.RINGS to rings, ViewMode.AREAS to areasPos, ViewMode.LINKS to web, ViewMode.TIMELINE to time),
                areaIdx,
            )
        }

        private val RING_RADIUS = mapOf(Kind.NOTE to .38f, Kind.REPO to .58f, Kind.FILE to .76f, Kind.ISSUE to .9f, Kind.LINK to 1.06f)
        private const val TAU = (2 * PI).toFloat()
        private const val HALF_PI = (PI / 2).toFloat()

        fun vec(lat: Float, lon: Float) = floatArrayOf(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))

        private fun gauss(r: Random): Float {
            var u = 0f
            repeat(4) { u += r.nextFloat() }
            return (u - 2f) * 1.2f
        }

        fun buildAreas(entries: List<Entry>): List<AreaInfo> {
            val counts = entries.groupingBy { it.area }.eachCount()
            val names = counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { it })
            val n = names.size
            return names.mapIndexed { i, name ->
                val lat = if (n == 1) 0f else (asin(1f - 2f * (i + .5f) / n) * .6f)
                // Few areas share about 170 degrees of longitude so they all face the viewer at first; many use the full circle.
                val span = if (n <= 6) 0.95f * PI.toFloat() else TAU
                val lon = if (n == 1) 0f else (((i * 0.6180339f) % 1f) - .5f) * span
                AreaInfo(name, PALETTE[i % PALETTE.size], lat, lon, counts.getValue(name))
            }
        }

        fun buildLinks(entries: List<Entry>, areaIdx: IntArray, embedder: Embedder): List<IntArray> {
            val n = entries.size
            embedder.prepare(entries.map { it.searchable })
            val vecs = entries.map { embedder.embed(it.searchable) }
            val seen = HashSet<Long>()
            val out = ArrayList<IntArray>()
            fun add(a: Int, b: Int, type: Int = LINK_SIMILAR) {
                if (a == b) return
                val lo = min(a, b); val hi = max(a, b)
                if (seen.add(lo.toLong() * n + hi)) out += intArrayOf(lo, hi, type)
            }
            for (i in 0 until n) {
                val ranked = ArrayList<Pair<Int, Float>>()
                for (j in 0 until n) {
                    if (j == i) continue
                    val s = dot(vecs[i], vecs[j])
                    if (s >= MIN_LINK) ranked += j to (s + if (areaIdx[i] == areaIdx[j]) .05f else 0f)
                }
                ranked.sortByDescending { it.second }
                for ((j, _) in ranked.take(2)) add(i, j)
            }
            val repoByPath = entries.withIndex().filter { it.value.kind == Kind.REPO }.associate { it.value.path to it.index }
            val byTitle = entries.withIndex().associate { (i, e) -> e.title.lowercase() to i }
            val wiki = Regex("\\[\\[([^\\]]+)]]")
            for ((i, e) in entries.withIndex()) {
                for (m in wiki.findAll(e.text)) byTitle[m.groupValues[1].trim().lowercase()]?.let { add(i, it, LINK_WIKI) }
                if (e.kind == Kind.ISSUE || e.kind == Kind.FILE) {
                    val repo = if (e.kind == Kind.ISSUE) e.path.substringBefore('#') else e.path.split('/').take(2).joinToString("/")
                    repoByPath[repo]?.let { add(i, it, LINK_IN_REPO) }
                }
            }
            return out
        }

        const val MIN_LINK = 0.10f
        const val LINK_SIMILAR = 0
        const val LINK_WIKI = 1
        const val LINK_IN_REPO = 2
        val LINK_LABELS = arrayOf("similar", "links to", "in repo")

        /** A one-off force layout seeded from the Areas view, scaled to fit the unit square. */
        fun forceLayout(seed: FloatArray, links: List<IntArray>, n: Int): FloatArray {
            val px = FloatArray(n) { seed[3 * it] * 1.3f }
            val py = FloatArray(n) { seed[3 * it + 1] * 1.3f }
            val vx = FloatArray(n); val vy = FloatArray(n)
            val fx = FloatArray(n); val fy = FloatArray(n)
            val iterations = if (n > 500) 120 else 260
            repeat(iterations) {
                for (i in 0 until n) { fx[i] = -px[i] * .004f; fy[i] = -py[i] * .004f }
                for (i in 0 until n) for (j in i + 1 until n) {
                    val dx = px[i] - px[j]; val dy = py[i] - py[j]
                    val d = max(.01f, sqrt(dx * dx + dy * dy))
                    if (d < .9f) {
                        val f = .006f / (d * d)
                        fx[i] += dx / d * f; fy[i] += dy / d * f; fx[j] -= dx / d * f; fy[j] -= dy / d * f
                    }
                }
                for (l in links) {
                    val a = l[0]; val b = l[1]
                    val dx = px[b] - px[a]; val dy = py[b] - py[a]
                    val d = max(.01f, sqrt(dx * dx + dy * dy))
                    val f = (d - .2f) * .04f
                    fx[a] += dx / d * f; fy[a] += dy / d * f; fx[b] -= dx / d * f; fy[b] -= dy / d * f
                }
                for (i in 0 until n) {
                    vx[i] = (vx[i] + fx[i]) * .8f; vy[i] = (vy[i] + fy[i]) * .8f
                    px[i] += vx[i]; py[i] += vy[i]
                }
            }
            val ext = (0 until n).map { max(kotlin.math.abs(px[it]), kotlin.math.abs(py[it])) }.sorted()
            val m = max(.001f, ext[(ext.size * .92f).toInt().coerceAtMost(n - 1)])
            val out = FloatArray(3 * n)
            val rnd = Random(5)
            for (i in 0 until n) {
                out[3 * i] = (px[i] / m * .95f).coerceIn(-1f, 1f)
                out[3 * i + 1] = (py[i] / m * .95f).coerceIn(-1f, 1f)
                out[3 * i + 2] = (rnd.nextFloat() - .5f) * .06f
            }
            return out
        }
    }
}
