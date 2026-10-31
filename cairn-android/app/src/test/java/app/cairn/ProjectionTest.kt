package app.cairn

import app.cairn.map.Projection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {
    private val out = FloatArray(3)

    @Test fun frontPointLandsInTheCentreAtZeroRotation() {
        Projection.toScreen(0f, 0f, 1f, 0f, 0f, 1f, 400f, 800f, 0f, 0f, out)
        assertEquals(200f, out[0], 1e-3f)
        assertEquals(400f, out[1], 1e-3f)
        assertEquals(1f, out[2], 1e-4f)
    }

    @Test fun backPointFacesAwayAndUpIsUpOnScreen() {
        Projection.toScreen(0f, 0f, -1f, 0f, 0f, 1f, 400f, 800f, 0f, 0f, out)
        assertTrue(out[2] < 0f)
        Projection.toScreen(0f, 1f, 0f, 0f, 0f, 1f, 400f, 800f, 0f, 0f, out)
        assertTrue(out[1] < 400f)
    }

    @Test fun yawBringsASidePointToTheFront() {
        // A point at +x (lon 90 degrees) faces the viewer after yaw = -90 degrees.
        Projection.rotate(1f, 0f, 0f, (-Math.PI / 2).toFloat(), 0f, out)
        assertEquals(0f, out[0], 1e-4f)
        assertEquals(1f, out[2], 1e-4f)
    }

    @Test fun zoomAndPanScaleAndShift() {
        Projection.toScreen(1f, 0f, 0f, 0f, 0f, 2f, 400f, 800f, 10f, 0f, out)
        assertEquals(200f + 10f + 160f * 2f, out[0], 1e-3f)
    }
}
