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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.sin

/**
 * What the map screen needs to draw and animate: the graph, the current view, the camera, the
 * selection and the morph between views. Drag has inertia, the idle globe turns slowly, and every
 * move eases out. Drawing is in [MapCanvas] and [Scene]; the maths in [Projector].
 */
class MapController(private val scope: CoroutineScope) {
    var graph by mutableStateOf(Graph.EMPTY); private set
    var view by mutableStateOf(ViewMode.GLOBE); private set

    var yaw by mutableFloatStateOf(DEFAULT_YAW); private set
    var pitch by mutableFloatStateOf(DEFAULT_PITCH); private set
    var roll by mutableFloatStateOf(0f); private set
    /** 1 at the default view; larger is closer. */
    var zoom by mutableFloatStateOf(1f); private set
    var panX by mutableFloatStateOf(0f); private set
    var panY by mutableFloatStateOf(0f); private set

    /** 0 = still at the previous view's positions, 1 = arrived. */
    var morph by mutableFloatStateOf(1f); private set
    /** 1 on the globe, 0 on a flat view; blends perspective and flat projection during a switch. */
    var globeMix by mutableFloatStateOf(1f); private set

    var selected by mutableIntStateOf(-1); private set
    /** Index of the isolated area, or -1. */
    var isolated by mutableIntStateOf(-1); private set
    var hits by mutableStateOf<Set<Int>>(emptySet()); private set

    val scene = Scene()

    /** Animation clock in seconds; advances every frame while motion is on, so the canvas redraws. */
    var clock by mutableFloatStateOf(0f); private set

    /** Debug overlay: shown when true. */
    var debug by mutableStateOf(false)
    /** False when the system animation scale is off: everything snaps, nothing spins. */
    var animate = true

    // Frame statistics for the debug overlay (plain fields: written during draw).
    var drawMsAvg = 0f; var drawMsMax = 0f; var frames = 0
    fun recordDraw(ns: Long) {
        val ms = ns / 1e6f
        drawMsAvg += (ms - drawMsAvg) * 0.1f
        drawMsMax = if (frames % 120 == 0) ms else maxOf(drawMsMax, ms)
        frames++
    }

    /** Set while something covers the map (the open sheet): the ambient clock stops, so nothing redraws. */
    var paused = false
    private var ambient = 0f

    private var from = FloatArray(0)
    private var job: Job? = null
    private var touching = false
    private var idle = 0f
    private var vYaw = 0f; private var vPitch = 0f; private var vPanX = 0f; private var vPanY = 0f
    private val tmp = FloatArray(3)

    private val flying get() = job?.isActive == true

    fun loadGraph(g: Graph) {
        job?.cancel()
        graph = g
        from = g.positions[view] ?: FloatArray(0)
        morph = 1f
        if (selected >= g.size) selected = -1
        if (isolated >= g.areas.size) isolated = -1
        hits = emptySet()
        scene.bind(g)
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
        for (i in 0 until n) { position(i, tmp); out[3 * i] = tmp[0]; out[3 * i + 1] = tmp[1]; out[3 * i + 2] = tmp[2] }
        return out
    }

    private fun stopInertia() { vYaw = 0f; vPitch = 0f; vPanX = 0f; vPanY = 0f }

    fun showView(v: ViewMode) {
        if (v == view) return
        val start = snapshot()
        job?.cancel(); stopInertia()
        from = start
        view = v
        val y0 = yaw; val p0 = pitch; val z0 = zoom; val x0 = panX; val yy0 = panY; val g0 = globeMix; val r0 = roll
        val globe = v == ViewMode.GLOBE
        val yT = if (globe) DEFAULT_YAW else 0f
        val pT = if (globe) DEFAULT_PITCH else 0f
        val gT = if (globe) 1f else 0f
        val zT = 1f
        if (!animate) {
            morph = 1f; yaw = yT; pitch = pT; zoom = zT; panX = 0f; panY = 0f; globeMix = gT; roll = 0f
            return
        }
        morph = 0f
        job = scope.launch {
            Animatable(0f).animateTo(1f, tween(DURATION_MS, easing = EASE)) {
                val e = value
                morph = e
                yaw = y0 + (yT - y0) * e; pitch = p0 + (pT - p0) * e; roll = r0 * (1 - e)
                zoom = z0 + (zT - z0) * e; panX = x0 * (1 - e); panY = yy0 * (1 - e)
                globeMix = g0 + (gT - g0) * e
            }
        }
    }

    fun select(i: Int) { selected = i; scene.setSelection(i) }
    fun isolate(area: Int) { isolated = if (isolated == area) -1 else area }
    fun highlight(h: Set<Int>) { hits = h; scene.setHits(h) }

    // ---- touch ----

    fun touchDown() { touching = true; idle = 0f; job?.cancel(); stopInertia() }

    /** [vx], [vy] are the release velocity in px/s; 0 for a tap or a pinch. */
    fun touchUp(vx: Float, vy: Float) {
        touching = false; idle = 0f
        if (!animate) return
        if (view == ViewMode.GLOBE) {
            val k = 1f / scene.projector.pixelsPerRadian()
            val cr = cos(roll); val sr = sin(roll)
            val ux = vx * cr + (-vy) * sr
            val uy = -vx * sr + (-vy) * cr
            vYaw = (ux * k).coerceIn(-MAX_SPIN, MAX_SPIN)
            vPitch = (-uy * k).coerceIn(-MAX_SPIN, MAX_SPIN)
        } else {
            vPanX = vx.coerceIn(-4000f, 4000f); vPanY = vy.coerceIn(-4000f, 4000f)
        }
    }

    /** One-finger drag. Turns the globe, or pans a flat view. */
    fun drag(dx: Float, dy: Float) {
        job?.cancel()
        if (view == ViewMode.GLOBE) {
            val k = 1f / scene.projector.pixelsPerRadian()
            val cr = cos(roll); val sr = sin(roll)
            val ux = dx * cr + (-dy) * sr
            val uy = -dx * sr + (-dy) * cr
            yaw += ux * k
            pitch = (pitch - uy * k).coerceIn(-MAX_PITCH, MAX_PITCH)
        } else {
            panX += dx; panY += dy
        }
    }

    private fun minZoom() = if (view == ViewMode.GLOBE) MIN_ZOOM_GLOBE else MIN_ZOOM_FLAT
    private fun maxZoom() = if (view == ViewMode.GLOBE) MAX_ZOOM_GLOBE else MAX_ZOOM_FLAT

    /** Pinch about a point [fx], [fy] measured from the canvas centre. On the globe the point under the fingers drifts to the centre. */
    fun pinch(factor: Float, fx: Float, fy: Float) {
        val z = (zoom * factor).coerceIn(minZoom(), maxZoom())
        val f = z / zoom
        if (view != ViewMode.GLOBE) {
            panX = fx + (panX - fx) * f; panY = fy + (panY - fy) * f
        } else if (f > 1f && globeMix > 0.9f) {
            val w = scene.width; val h = scene.height
            val frac = (1f - 1f / f).coerceIn(0f, 1f)
            // Drift towards the node nearest the fingers, else the surface point under them.
            val near = scene.nearestScreen(w / 2f + fx, h / 2f + fy, 140f * scene.density)
            val eng = if (near < 0) scene.nearestEngram(w / 2f + fx, h / 2f + fy, 140f * scene.density) else -1
            if (near >= 0) turnToward(scene.wx[near], scene.wy[near], scene.wz[near], frac)
            else if (eng >= 0) { scene.areaCentre(eng, tmp); turnToward(tmp[0], tmp[1], tmp[2], frac) }
            else if (scene.projector.surfacePoint(w / 2f + fx, h / 2f + fy, tmp)) turnToward(tmp[0], tmp[1], tmp[2], frac)
        }
        zoom = z
    }

    /** Two-finger twist, radians (clockwise positive). */
    fun twist(rad: Float) { roll -= rad }

    private fun turnToward(x: Float, y: Float, z: Float, frac: Float) {
        val lon = atan2(x, z)
        val lat = asin((y / hypot(hypot(x, y), z).coerceAtLeast(1e-6f)).coerceIn(-1f, 1f))
        yaw += wrap(-lon - yaw) * frac
        pitch += (lat.coerceIn(-MAX_PITCH, MAX_PITCH) - pitch) * frac
    }

    /** Called every frame while the map is on screen: inertia and the idle turn. */
    fun tick(dt: Float) {
        scene.motion = animate
        if (animate && !paused) {
            // Ambient motion (shells, particles, scan, packets) runs at about 30 fps to spare the battery.
            ambient += dt
            if (ambient >= AMBIENT_STEP) { scene.advance(ambient); clock += ambient; ambient = 0f }
        }
        if (touching || !animate) { if (touching) idle = 0f; return }
        idle += dt
        if (flying) return
        val moving = abs(vYaw) + abs(vPitch) + abs(vPanX) + abs(vPanY) > 0.02f
        if (moving) {
            val decay = exp(-dt * 3.2f)
            if (view == ViewMode.GLOBE) {
                yaw += vYaw * dt
                pitch = (pitch + vPitch * dt).coerceIn(-MAX_PITCH, MAX_PITCH)
            } else { panX += vPanX * dt; panY += vPanY * dt }
            vYaw *= decay; vPitch *= decay; vPanX *= decay; vPanY *= decay
            if (abs(vYaw) + abs(vPitch) < 0.01f) { vYaw = 0f; vPitch = 0f }
            if (abs(vPanX) + abs(vPanY) < 1f) { vPanX = 0f; vPanY = 0f }
        } else if (view == ViewMode.GLOBE && selected < 0 && hits.isEmpty() && idle > IDLE_DELAY && zoom < 2.5f) {
            yaw += IDLE_SPIN * dt
        }
    }

    // ---- camera moves ----

    /** Glide the camera so node [i] is centred, at zoom at least [minZoom]. */
    fun flyTo(i: Int, minZoom: Float = 3f) {
        if (i < 0 || i >= graph.size) return
        position(i, tmp)
        flyToPoint(tmp[0], tmp[1], tmp[2], minZoom)
    }

    /** Fly to an area's engram and open it up. */
    fun flyToArea(a: Int, minZoom: Float = 2.6f) {
        if (a < 0 || a >= graph.areas.size) return
        scene.areaCentre(a, tmp)
        select(-1)
        flyToPoint(tmp[0], tmp[1], tmp[2], minZoom)
    }

    private fun flyToPoint(px: Float, py: Float, pz: Float, minZoom: Float) {
        job?.cancel(); stopInertia()
        val y0 = yaw; val p0 = pitch; val z0 = zoom; val x0 = panX; val yy0 = panY; val r0 = roll
        val zT = maxOf(minZoom, zoom).coerceAtMost(maxZoom())
        val globe = view == ViewMode.GLOBE
        val lon = atan2(px, pz)
        val lat = asin((py / hypot(hypot(px, py), pz).coerceAtLeast(1e-6f)).coerceIn(-1f, 1f))
        val yT = y0 + wrap(-lon - y0)
        val pT = lat.coerceIn(-MAX_PITCH, MAX_PITCH)
        val r = Projector.baseSize(scene.width, scene.height) * zT
        val xT = -px * r
        val yyT = py * r
        if (!animate) {
            zoom = zT; roll = 0f
            if (globe) { yaw = yT; pitch = pT } else { panX = xT; panY = yyT }
            return
        }
        job = scope.launch {
            Animatable(0f).animateTo(1f, tween(FLY_MS, easing = EASE)) {
                val e = value
                zoom = z0 + (zT - z0) * e; roll = r0 * (1 - e)
                if (globe) { yaw = y0 + (yT - y0) * e; pitch = p0 + (pT - p0) * e }
                else { panX = x0 + (xT - x0) * e; panY = yy0 + (yyT - yy0) * e }
            }
        }
    }

    /** Double tap on a node: select it and zoom in close. */
    fun zoomToNode(i: Int) { select(i); flyTo(i, 4.5f) }

    /** Double tap on empty space: zoom in about that point. */
    fun zoomAt(fx: Float, fy: Float) {
        job?.cancel(); stopInertia()
        val z0 = zoom
        val zT = (zoom * 2f).coerceIn(minZoom(), maxZoom())
        if (!animate) { pinch(zT / z0, fx, fy); return }
        job = scope.launch {
            var last = z0
            Animatable(z0).animateTo(zT, tween(350, easing = EASE)) { pinch(value / last, fx, fy); last = value }
        }
    }

    fun fit() {
        job?.cancel(); stopInertia()
        val globe = view == ViewMode.GLOBE
        yaw = if (globe) DEFAULT_YAW else 0f
        pitch = if (globe) DEFAULT_PITCH else 0f
        roll = 0f; zoom = 1f; panX = 0f; panY = 0f
    }

    private fun wrap(d: Float): Float {
        val two = (2 * PI).toFloat()
        return ((d + PI.toFloat()) % two + two) % two - PI.toFloat()
    }

    companion object {
        const val DEFAULT_YAW = 0.5f
        const val DEFAULT_PITCH = 0.3f
        const val MAX_PITCH = 1.3f
        const val MIN_ZOOM_GLOBE = 0.3f
        const val MAX_ZOOM_GLOBE = 12f
        const val MIN_ZOOM_FLAT = 0.7f
        const val MAX_ZOOM_FLAT = 6f
        const val MAX_SPIN = 6f
        const val IDLE_DELAY = 2.5f
        const val IDLE_SPIN = 0.05f
        const val AMBIENT_STEP = 1f / 30f
        const val DURATION_MS = 900
        const val FLY_MS = 700
        val EASE = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
    }
}
