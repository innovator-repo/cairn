package app.cairn.map

import kotlin.math.cos
import kotlin.math.sin

/** Orthographic projection for the globe; with yaw and pitch at zero it is a plain flat view. */
object Projection {
    /** Pixels per unit at zoom 1. */
    fun base(w: Float, h: Float): Float = minOf(w, h) * 0.40f

    /** Rotates [x], [y], [z] by yaw (about the vertical axis) then pitch; result in [out]. */
    fun rotate(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, out: FloatArray) {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        out[0] = x1
        out[1] = y * cp - z1 * sp
        out[2] = y * sp + z1 * cp
    }

    /**
     * Screen position of a point. Returns x, y in [out] (px from the canvas origin) and depth in
     * out[2]: positive faces the viewer.
     */
    fun toScreen(
        x: Float, y: Float, z: Float, yaw: Float, pitch: Float, zoom: Float,
        w: Float, h: Float, panX: Float, panY: Float, out: FloatArray,
    ) {
        rotate(x, y, z, yaw, pitch, out)
        val r = base(w, h) * zoom
        val sx = w / 2f + panX + out[0] * r
        val sy = h / 2f + panY - out[1] * r
        val depth = out[2]
        out[0] = sx; out[1] = sy; out[2] = depth
    }
}
