package app.cairn

import app.cairn.data.Entry
import app.cairn.data.Kind
import app.cairn.graph.Graph
import app.cairn.map.MapController
import app.cairn.map.Scene
import app.cairn.search.TfidfEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-frame scene: level of detail, hit rules and cost, with no Android and no drawing. */
class SceneTest {
    private val now = 1_800_000_000_000L
    private val w = 1080f
    private val h = 1500f
    private val dens = 3f

    private fun seeded(): Graph {
        val entries = app.cairn.data.Seed.notes.mapIndexed { i, s ->
            Entry("n$i", Kind.NOTE, s.area, s.title, "notes/${s.area}/$i.md", s.body, now - s.ageDays * 86_400_000L)
        }
        return Graph.build(entries, TfidfEmbedder(512), now)
    }

    private fun controller(g: Graph): MapController =
        MapController(CoroutineScope(Dispatchers.Unconfined)).also { it.animate = false; it.loadGraph(g) }

    @Test fun levelsOfDetailFollowTheZoomAndNeverPop() {
        val c = controller(seeded()); val sc = c.scene
        val names = ArrayList<String>()
        var lastDetail = -1f; var lastDecor = 2f; var lastMemory = -1f; var lastGlow = 2f; var lastNames = -1f; var lastFull = -1f
        repeat(4) { step ->
            if (step > 0) c.pinch(2f, 0f, 0f)
            sc.frame(c, w, h, dens)
            names += sc.depthName
            assertTrue(sc.detail >= lastDetail); lastDetail = sc.detail
            assertTrue("shells and particles fade as the camera closes in", sc.decorAlpha <= lastDecor); lastDecor = sc.decorAlpha
            assertTrue(sc.memoryAlpha >= lastMemory); lastMemory = sc.memoryAlpha
            assertTrue("glow only far out", sc.glowAlpha <= lastGlow); lastGlow = sc.glowAlpha
            assertTrue(sc.nameAlpha >= lastNames); lastNames = sc.nameAlpha
            assertTrue(sc.fullAlpha >= lastFull); lastFull = sc.fullAlpha
        }
        assertEquals(listOf("FAR", "MID", "NEAR", "CLOSE"), names)
        assertTrue("close-up is crisp: glow is gone and decor nearly so", sc.glowAlpha == 0f && sc.decorAlpha < 0.1f)
    }

    @Test fun farOnlyEngramsCanBeTappedAndCloseOnlyMemories() {
        val g = seeded()
        val c = controller(g); val sc = c.scene
        sc.frame(c, w, h, dens)
        assertEquals("one engram per area", g.areas.size, sc.areaCount)
        assertTrue("some engram faces the viewer", (0 until sc.areaCount).any { sc.engA[it] > 0.5f })
        for (a in 0 until sc.areaCount) if (sc.engA[a] > 0.5f) {
            assertEquals(-1, sc.hit(sc.engX[a], sc.engY[a], dens))
            assertEquals(a, sc.hitEngram(sc.engX[a], sc.engY[a], dens))
        }
        repeat(2) { c.pinch(2f, 0f, 0f) } // zoom 4
        sc.frame(c, w, h, dens)
        val i = (0 until sc.n).first { sc.fa[it] > 0.5f && sc.sx[it] in 100f..900f && sc.sy[it] in 200f..1300f }
        assertTrue("a memory can be tapped once the camera is near", sc.hit(sc.sx[i], sc.sy[i], dens) >= 0)
    }

    @Test fun memoriesFoldIntoTheirEngramWhenFarAndSpreadWhenNear() {
        val c = controller(seeded()); val sc = c.scene
        sc.frame(c, w, h, dens)
        fun spreadOf(area: Int): Float {
            var sx = 0f; var sy = 0f; var k = 0
            for (i in 0 until sc.n) if (sc.areaOf[i] == area && sc.z2[i] > 0.4f) { sx += sc.sx[i]; sy += sc.sy[i]; k++ }
            if (k < 3) return -1f
            val mx = sx / k; val my = sy / k
            var d = 0f
            for (i in 0 until sc.n) if (sc.areaOf[i] == area && sc.z2[i] > 0.4f) d += Math.hypot((sc.sx[i] - mx).toDouble(), (sc.sy[i] - my).toDouble()).toFloat()
            return d / k
        }
        val area = (0 until sc.areaCount).maxBy { sc.engA[it] }
        val far = spreadOf(area)
        c.pinch(1.0001f, 0f, 0f)
        // Compare at the same screen scale: spread relative to the globe's size on screen.
        val farRel = far / sc.projector.silhouette
        c.pinch(2f, 0f, 0f)
        sc.frame(c, w, h, dens)
        val midRel = spreadOf(area) / sc.projector.silhouette
        assertTrue("memories spread out relative to the globe as the camera nears ($farRel -> $midRel)", far < 0f || midRel > farRel)
    }

    @Test fun labelsAreCappedAndReservedSlotsAreMarked() {
        val c = controller(seeded()); val sc = c.scene
        repeat(2) { c.pinch(1.6f, 0f, 0f); sc.frame(c, w, h, dens) }
        assertTrue(sc.labelCount <= Scene.MAX_LABELS)
        for (k in 0 until sc.labelCount) assertTrue(sc.labelNode[k] == -1 || sc.labelNode[k] in 0 until sc.n)
    }

    @Test fun sceneFrameCostForAThousandNodesIsSmall() {
        val rnd = java.util.Random(3)
        val words = listOf("alpha", "beta", "gamma", "delta", "kanji", "tokyo", "gradle", "budget", "garden", "yoga", "route", "ticket")
        val entries = (0 until 1200).map { i ->
            Entry("e$i", Kind.entries[i % 5], "Area ${i % 8}", "Item $i ${words[rnd.nextInt(words.size)]}", "p/$i", words.shuffled(rnd).take(5).joinToString(" "), now - rnd.nextInt(100) * 86_400_000L)
        }
        val g = Graph.build(entries, TfidfEmbedder(256), now)
        val c = controller(g); val sc = c.scene
        repeat(30) { sc.frame(c, w, h, dens) } // warm up
        val t0 = System.nanoTime()
        repeat(100) { sc.frame(c, w, h, dens) }
        val ms = (System.nanoTime() - t0) / 1e6 / 100
        println("PERF scene frame ${g.size} nodes ${g.links.size} links: %.3f ms per frame".format(ms))
        assertTrue("a frame's worth of scene work must be a small part of 16 ms (was $ms)", ms < 6.0)
    }
}
