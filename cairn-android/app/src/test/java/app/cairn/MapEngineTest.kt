package app.cairn

import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.data.Shape
import app.cairn.graph.Graph
import app.cairn.map.Projector
import app.cairn.search.TfidfEmbedder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEngineTest {
    private val out = FloatArray(5)

    private fun cam(yaw: Float = 0f, pitch: Float = 0f, roll: Float = 0f, u: Float = 1f, gm: Float = 1f) =
        Projector().also { it.set(yaw, pitch, roll, u, gm, 400f, 800f, 0f, 0f) }

    @Test fun everyKindHasItsOwnShape() {
        assertEquals(Kind.entries.size, Kind.entries.map { it.shape }.toSet().size)
        assertEquals(Shape.CIRCLE, Kind.NOTE.shape)
        assertEquals(Shape.SQUARE, Kind.REPO.shape)
        assertEquals(Shape.TRIANGLE, Kind.ISSUE.shape)
        assertEquals(Shape.DIAMOND, Kind.LINK.shape)
        assertEquals(Shape.PAGE, Kind.FILE.shape)
    }

    @Test fun frontPointIsCentredAndFacesTheViewer() {
        cam().project(0f, 0f, 1f, out)
        assertEquals(200f, out[0], 1e-2f)
        assertEquals(400f, out[1], 1e-2f)
        assertEquals(1f, out[2], 1e-4f)
        assertEquals(1f, out[3], 1e-3f)
        assertEquals(1f, out[4], 0f)
    }

    @Test fun perspectiveShrinksWithDistanceAndHidesTheBack() {
        val c = cam()
        c.project(0f, 0f, 1f, out); val near = out[3]
        c.project(0f, 0f, -1f, out)
        assertTrue(out[2] < 0f)
        assertTrue(out[3] < near * 0.6f)
    }

    @Test fun upIsUpAndRotationBringsASidePointToTheFront() {
        cam().project(0f, 1f, 0f, out)
        assertTrue(out[1] < 400f)
        cam(yaw = (-Math.PI / 2).toFloat()).project(1f, 0f, 0f, out)
        assertEquals(1f, out[2], 1e-4f)
        assertEquals(200f, out[0], 1e-2f)
    }

    @Test fun zoomMovesTheCameraCloserContinuously() {
        var last = 0f
        for (u in listOf(0.4f, 1f, 2f, 4f, 8f, 12f)) {
            val c = cam(u = u)
            c.project(0.3f, 0f, Math.sqrt(1 - 0.09).toFloat(), out)
            val off = Math.abs(out[0] - 200f)
            assertTrue("offset grows as the camera nears", off > last)
            last = off
            assertTrue(c.distance > 1.05f) // never inside the sphere
        }
    }

    @Test fun flatProjectionIsPlainAndScalesWithZoom() {
        cam(gm = 0f).project(1f, 0f, 0f, out)
        assertEquals(200f + 160f, out[0], 1e-2f)
        cam(gm = 0f, u = 2f).project(1f, 0f, 0f, out)
        assertEquals(200f + 320f, out[0], 1e-2f)
    }

    @Test fun rollRotatesTheScreen() {
        cam(roll = (Math.PI / 2).toFloat(), gm = 0f).project(1f, 0f, 0f, out)
        assertEquals(200f, out[0], 1e-2f)
        assertTrue(out[1] < 400f) // the right-hand point swung up
    }

    @Test fun surfacePointInvertsProjection() {
        val c = cam(yaw = 0.3f, pitch = 0.2f, u = 2.5f)
        val p = floatArrayOf(0.1f, 0.1f, 0f); p[2] = Math.sqrt(1.0 - 0.01 - 0.01).toFloat()
        val q = FloatArray(5)
        // Rotate the sample into view first, then ask where on the sphere that pixel lands.
        c.project(p[0], p[1], p[2], q)
        val w = FloatArray(3)
        assertTrue("sample must face the viewer past the limb", q[2] > c.horizon)
        run {
            assertTrue(c.surfacePoint(q[0], q[1], w))
            assertEquals(p[0], w[0], 2e-3f); assertEquals(p[1], w[1], 2e-3f); assertEquals(p[2], w[2], 2e-3f)
        }
        assertTrue(!cam().surfacePoint(0f, 0f, w)) // the corner of the screen misses the globe
    }

    @Test fun horizonMovesInAsTheCameraCloses() {
        assertTrue(cam(u = 0.4f).horizon < cam(u = 1f).horizon)
        assertTrue(cam(u = 1f).horizon < cam(u = 8f).horizon)
        assertTrue(cam(u = 12f).horizon < 1f)
    }

    @Test fun projectionCostForAThousandNodesIsSmall() {
        val c = cam(u = 3f)
        val n = 1500
        val xs = FloatArray(n) { (it % 37) / 40f - 0.4f }
        var sink = 0f
        repeat(50) { for (i in 0 until n) { c.project(xs[i], xs[(i * 7) % n], 0.8f, out); sink += out[0] } } // warm up
        val t0 = System.nanoTime()
        repeat(200) { for (i in 0 until n) { c.project(xs[i], xs[(i * 7) % n], 0.8f, out); sink += out[0] } }
        val ms = (System.nanoTime() - t0) / 1e6 / 200
        println("PERF project 1500 nodes: %.3f ms per frame (sink %.0f)".format(ms, sink))
        assertTrue("projection should take well under a frame", ms < 8.0)
    }

    @Test fun seedHasEnoughNodesAndEveryAreaHasLinks() {
        val now = System.currentTimeMillis()
        val entries = app.cairn.data.Seed.notes.mapIndexed { i, s ->
            Entry("n$i", Kind.NOTE, s.area, s.title, "notes/${s.area}/$i.md", s.body, now - s.ageDays * 86_400_000L)
        }
        assertTrue(entries.size >= 150)
        val t0 = System.nanoTime()
        val g = Graph.build(entries, TfidfEmbedder(512), now)
        println("PERF graph build ${entries.size} nodes, ${g.links.size} links: ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(g.links.size > entries.size / 2)
        assertTrue(g.areas.size >= 6)
    }
}
