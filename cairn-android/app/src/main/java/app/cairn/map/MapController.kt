package app.cairn.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cairn.graph.Graph
import app.cairn.graph.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Holds what the map screen needs to draw and animate: the graph, the current view, the camera,
 * the selection and the morph between views. Pure state plus a few animations; the drawing is in
 * [MapCanvas] and the maths in [Projection].
 */
class MapController(private val scope: CoroutineScope) {
    var graph by mutableStateOf(Graph.EMPTY); private set
    var view by mutableStateOf(ViewMode.GLOBE); private set

    var yaw by mutableFloatStateOf(DEFAULT_YAW); private set
    var pitch by mutableFloatStateOf(DEFAULT_PITCH); private set
    var zoom by mutableFloatStateOf(1f); private set
    var panX by mutableFloatStateOf(0f); private set
    var panY by mutableFloatStateOf(0f); private set

    /** 0 = still at the previous view's positions, 1 = arrived. */
    var morph by mutableFloatStateOf(1f); private set
    /** 1 on the globe, 0 on a flat view; fades the sphere in and out during a switch. */
    var globeMix by mutableFloatStateOf(1f); private set

    var selected by mutableIntStateOf(-1); private set
    /** Index of the isolated area, or -1. */
    var isolated by mutableIntStateOf(-1); private set
    var hits by mutableStateOf<Set<Int>>(emptySet()); private set

    /** Canvas size in px, reported by the canvas; used to centre a fly-to. */
    var viewSize = 0f

    var animate = true

    private var from = FloatArray(0)
    private var job: Job? = null

    fun loadGraph(g: Graph) {
        job?.cancel()
        graph = g
        from = g.positions[view] ?: FloatArray(0)
        morph = 1f
        if (selected >= g.size) selected = -1
        if (isolated >= g.areas.size) isolated = -1
        hits = emptySet()
    }

    fun target(): FloatArray = graph.positions[view] ?: FloatArray(0)

    /** Position of node [i] now, mid-morph included. */
    fun position(i: Int, out: FloatArray) {
        val to = target()
        if (to.size < 3 * (i + 1)) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return }
        if (morph >= 1f || from.size != to.size) {
            out[0] = to[3 * i]; out[1] = to[3 * i + 1]; out[2] = to[3 * i + 2]
        } else {
            val e = morph
            for (k in 0..2) out[k] = from[3 * i + k] + (to[3 * i + k] - from[3 * i + k]) * e
        }
    }

    private fun snapshot(): FloatArray {
        val n = graph.size
        val out = FloatArray(3 * n)
        val tmp = FloatArray(3)
        for (i in 0 until n) { position(i, tmp); out[3 * i] = tmp[0]; out[3 * i + 1] = tmp[1]; out[3 * i + 2] = tmp[2] }
        return out
    }

    fun showView(v: ViewMode) {
        if (v == view) return
        val start = snapshot()
        job?.cancel()
        from = start
        view = v
        val y0 = yaw; val p0 = pitch; val z0 = zoom; val x0 = panX; val yy0 = panY; val g0 = globeMix
        val globe = v == ViewMode.GLOBE
        val yT = if (globe) DEFAULT_YAW else 0f
        val pT = if (globe) DEFAULT_PITCH else 0f
        val gT = if (globe) 1f else 0f
        if (!animate) {
            morph = 1f; yaw = yT; pitch = pT; zoom = 1f; panX = 0f; panY = 0f; globeMix = gT
            return
        }
        morph = 0f
        job = scope.launch {
            Animatable(0f).animateTo(1f, tween(DURATION_MS, easing = EASE)) {
                val e = value
                morph = e
                yaw = y0 + (yT - y0) * e; pitch = p0 + (pT - p0) * e
                zoom = z0 + (1f - z0) * e; panX = x0 * (1 - e); panY = yy0 * (1 - e)
                globeMix = g0 + (gT - g0) * e
            }
        }
    }

    fun select(i: Int) { selected = i }
    fun isolate(area: Int) { isolated = if (isolated == area) -1 else area }
    fun highlight(h: Set<Int>) { hits = h }

    fun stopMotion() { job?.cancel() }

    /** One-finger drag. Turns the globe, or pans a flat view. */
    fun drag(dx: Float, dy: Float) {
        job?.cancel()
        if (view == ViewMode.GLOBE) {
            val k = 1.1f / (Projection.base(viewSize, viewSize) * zoom)
            yaw += dx * k
            pitch = (pitch + dy * k).coerceIn(-MAX_PITCH, MAX_PITCH)
        } else {
            panX += dx; panY += dy
        }
    }

    /** Pinch zoom about a point [fx], [fy] measured from the canvas centre. */
    fun pinch(factor: Float, fx: Float, fy: Float) {
        val z = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = z / zoom
        if (view != ViewMode.GLOBE) { panX = fx + (panX - fx) * f; panY = fy + (panY - fy) * f }
        zoom = z
    }

    /** Glide the camera so node [i] is centred and closer. */
    fun flyTo(i: Int, minZoom: Float = 1.8f) {
        if (i < 0 || i >= graph.size) return
        job?.cancel()
        val p = FloatArray(3).also { position(i, it) }
        val y0 = yaw; val p0 = pitch; val z0 = zoom; val x0 = panX; val yy0 = panY
        val zT = maxOf(minZoom, zoom)
        val globe = view == ViewMode.GLOBE
        val lon = atan2(p[0], p[2])
        val lat = asin((p[1] / hypot(hypot(p[0], p[1]), p[2]).coerceAtLeast(1e-6f)).coerceIn(-1f, 1f))
        val yT = y0 + wrap(-lon - y0)
        val pT = lat.coerceIn(-MAX_PITCH, MAX_PITCH)
        val r = Projection.base(viewSize, viewSize) * zT
        val xT = -p[0] * r
        val yyT = p[1] * r
        if (!animate) {
            zoom = zT
            if (globe) { yaw = yT; pitch = pT } else { panX = xT; panY = yyT }
            return
        }
        job = scope.launch {
            Animatable(0f).animateTo(1f, tween(FLY_MS, easing = EASE)) {
                val e = value
                zoom = z0 + (zT - z0) * e
                if (globe) { yaw = y0 + (yT - y0) * e; pitch = p0 + (pT - p0) * e }
                else { panX = x0 + (xT - x0) * e; panY = yy0 + (yyT - yy0) * e }
            }
        }
    }

    fun fit() {
        job?.cancel()
        yaw = if (view == ViewMode.GLOBE) DEFAULT_YAW else 0f
        pitch = if (view == ViewMode.GLOBE) DEFAULT_PITCH else 0f
        zoom = 1f; panX = 0f; panY = 0f
    }

    private fun wrap(d: Float): Float {
        val two = (2 * PI).toFloat()
        return ((d + PI.toFloat()) % two + two) % two - PI.toFloat()
    }

    companion object {
        const val DEFAULT_YAW = 0.5f
        const val DEFAULT_PITCH = 0.3f
        const val MAX_PITCH = 1.3f
        const val MIN_ZOOM = 0.7f
        const val MAX_ZOOM = 3.6f
        const val DURATION_MS = 900
        const val FLY_MS = 600
        val EASE = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    }
}
