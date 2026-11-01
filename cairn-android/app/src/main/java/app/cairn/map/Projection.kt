package app.cairn.map

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The camera. On the globe it is a real perspective camera looking at the unit sphere from distance
 * [distance] (set by the zoom), after the world is turned by yaw and pitch. On flat views, and while
 * morphing towards them, it blends to a plain scaled projection. One instance is reused every frame;
 * nothing here allocates.
 *
 * Zoom `u` is 1 at the default view; larger is closer. Distance is 1 + (DREF - 1) / u, so every
 * pinch step shrinks the gap to the surface by the same factor, which feels even at all depths.
 */
class Projector {
    private var cy = 1f; private var sy = 0f; private var cp = 1f; private var sp = 0f
    private var cr = 1f; private var sr = 0f
    private var ortho = 1f
    private var f = 1f
    var distance = DREF; private set
    private var gm = 1f
    private var cx = 0f; private var cyScr = 0f
    var silhouette = 1f; private set
    /** Screen centre of the globe, px. */
    val centreX: Float get() = cx
    val centreY: Float get() = cyScr

    fun set(yaw: Float, pitch: Float, roll: Float, u: Float, globeMix: Float, w: Float, h: Float, panX: Float, panY: Float) {
        cy = cos(yaw); sy = sin(yaw); cp = cos(pitch); sp = sin(pitch); cr = cos(roll); sr = sin(roll)
        val base = baseSize(w, h)
        ortho = base * u
        f = base * sqrt(DREF * DREF - 1f)
        distance = 1f + (DREF - 1f) / u
        gm = globeMix
        cx = w / 2f + panX; cyScr = h / 2f + panY
        silhouette = ortho + (f / sqrt(distance * distance - 1f) - ortho) * globeMix
    }

    /**
     * Projects a world point. out[0], out[1] are screen px; out[2] the depth after rotation
     * (positive faces the viewer); out[3] a size factor, 1 at the nearest surface and smaller with
     * distance; out[4] is 0 if the point is behind the camera.
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        var px = x1 * ortho
        var py = y2 * ortho
        var ds = 1f
        var ok = 1f
        if (gm > 0f) {
            val denom = distance - z2
            if (denom > NEAR) {
                val k = f / denom
                px += (x1 * k - px) * gm
                py += (y2 * k - py) * gm
                ds = 1f + ((distance - 1f) / denom - 1f) * gm
            } else if (gm > 0.5f) ok = 0f
        }
        out[0] = cx + px * cr - py * sr
        out[1] = cyScr - (px * sr + py * cr)
        out[2] = z2
        out[3] = ds
        out[4] = ok
    }

    /**
     * The point on the sphere under a screen position, in world coordinates (globe only).
     * Returns false if the position misses the sphere.
     */
    fun surfacePoint(sx: Float, sy2: Float, out: FloatArray): Boolean {
        val dx0 = sx - cx
        val dy0 = cyScr - sy2
        val rx = dx0 * cr + dy0 * sr
        val ry = -dx0 * sr + dy0 * cr
        val dx = rx / f; val dy = ry / f
        val a = dx * dx + dy * dy + 1f
        val disc = 4f * distance * distance - 4f * a * (distance * distance - 1f)
        if (disc < 0f) return false
        val t = (2f * distance - sqrt(disc)) / (2f * a)
        val vx = t * dx; val vy = t * dy; val vz = distance - t
        val y1 = vy * cp + vz * sp
        val z1 = -vy * sp + vz * cp
        out[0] = vx * cy - z1 * sy
        out[1] = y1
        out[2] = vx * sy + z1 * cy
        return true
    }

    /** Depth above which a point on the unit sphere is visible: beyond this it is past the limb, hidden by the globe itself. */
    val horizon: Float get() = 1f / distance

    /** Pixels per radian of turning at the surface nearest the viewer. */
    fun pixelsPerRadian(): Float = f / (distance - 1f)

    companion object {
        const val DREF = 2.2f
        const val NEAR = 0.02f
        fun baseSize(w: Float, h: Float): Float = min(w, h) * 0.40f
        fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        fun clampf(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
    }
}
