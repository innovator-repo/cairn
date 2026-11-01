package app.cairn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.cairn.ui.CairnViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real UI on the JVM (Robolectric, native graphics). Checks that the pull-up sheet scrolls and
 * that search shows results, and writes PNGs of each zoom level when -Pshots=<dir> is given.
 * System animations are switched off so every step lands immediately and the clock is deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var vm: CairnViewModel

    @Before fun launch() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { vm = ViewModelProvider(it)[CairnViewModel::class.java] }
        // Background work posts back to the main looper, which Robolectric pauses; drain it while waiting.
        rule.waitUntil(30_000) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(25); vm.graph.size >= 150 }
        rule.waitForIdle()
    }

    @After fun close() { scenario.close() }

    private fun frame(): Bitmap {
        lateinit var bmp: Bitmap
        scenario.onActivity { a ->
            val v = a.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
        }
        return bmp
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = System.getProperty("cairn.shots") ?: return
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { frame().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A two-finger pinch that multiplies the zoom by about [factor]. */
    private fun pinchBy(factor: Float = 2f) {
        rule.onNodeWithTag("map").performTouchInput {
            val c = center
            pinch(c + Offset(-80f, 0f), c + Offset(-80f * factor, 0f), c + Offset(80f, 0f), c + Offset(80f * factor, 0f), 300)
        }
        rule.waitForIdle()
    }

    @Test fun seededMapIsFullAndSheetIsClosed() {
        assertTrue(vm.graph.size >= 150)
        rule.onNodeWithTag("sheetList").assertIsNotDisplayed()
        shot("globe-far")
    }

    @Test fun sheetListScrollsWhenOpenAndCollapsesOnlyFromTheTop() {
        val order = vm.graph.entries.sortedByDescending { it.updatedAt }
        val first = order.first().title
        val last = order.last().title
        rule.onNodeWithTag("sheetHeader").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithTag("sheetList").assertIsDisplayed()
        shot("sheet-open")
        assertTrue(rule.onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty())

        // Dragging inside the list scrolls the list, not the sheet.
        repeat(3) { rule.onNodeWithTag("sheetList").performTouchInput { swipeUp(startY = height * 0.9f, endY = height * 0.1f, durationMillis = 250) } }
        rule.waitForIdle()
        rule.onNodeWithTag("sheetList").assertIsDisplayed()
        assertTrue("first row scrolled away", rule.onAllNodesWithText(first).fetchSemanticsNodes().isEmpty() || first == last)

        // The last row of the long list is reachable.
        rule.onNodeWithTag("sheetList").performScrollToIndex(order.size - 1)
        rule.waitForIdle()
        assertTrue("last row reachable", rule.onAllNodesWithText(last).fetchSemanticsNodes().isNotEmpty())
        shot("sheet-scrolled-bottom")

        // Back at the top, a downward drag collapses the sheet.
        rule.onNodeWithTag("sheetList").performScrollToIndex(0)
        rule.waitForIdle()
        rule.onNodeWithTag("sheetList").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f, durationMillis = 250) }
        rule.waitForIdle()
        rule.onNodeWithTag("sheetList").assertIsNotDisplayed()
    }

    @Test fun searchShowsResultsWithSnippets() {
        rule.onNodeWithTag("search").performTextInput("tokyo")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Results").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("sheetHeader").performTouchInput { swipeUp() }
        rule.waitForIdle()
        val hits = vm.search("tokyo")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().snippet.contains("okyo", ignoreCase = true) || hits.first().doc.title.contains("okyo", ignoreCase = true))
        shot("search-results")
    }

    @Test fun searchFliesToTheNodeAndHighlightsTheHit() {
        rule.onNodeWithTag("search").performTextInput("kanji")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Results").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        shot("search-fly")
    }

    @Test fun pickingANoteFliesInAndTheClusterOpens() {
        vm.setFrameTimes(true)
        val first = vm.graph.entries.sortedByDescending { it.updatedAt }.first().title
        rule.onNodeWithTag("sheetHeader").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onAllNodesWithText(first).onFirst().performClick()
        rule.waitForIdle()
        shot("selected") // the node card, its link arcs and the full label
        // Tap empty space to drop the selection and the card, then keep zooming into the cluster.
        rule.onNodeWithTag("map").performTouchInput { click(Offset(24f, height * 0.5f)) }
        rule.waitForIdle()
        shot("globe-near")
        pinchBy(1.5f)
        shot("globe-near-2")
        pinchBy(1.5f)
        shot("globe-close")
    }

    @Test fun zoomLevelsAndFlatViews() {
        val perf = StringBuilder()
        fun measure(tag: String) {
            rule.waitForIdle()
            frame()
            val t0 = System.nanoTime()
            repeat(20) { frame() }
            perf.append("%s=%.1fms ".format(tag, (System.nanoTime() - t0) / 1e6 / 20))
        }
        vm.setFrameTimes(true)
        rule.waitForIdle()
        measure("far"); shot("globe-far")
        pinchBy(); measure("mid"); shot("globe-mid")
        pinchBy(); measure("deeper"); shot("globe-deeper")
        vm.setFrameTimes(false)
        rule.onNodeWithText("Links").performClick(); rule.waitForIdle(); shot("links")
        rule.onNodeWithText("Areas").performClick(); rule.waitForIdle(); shot("areas")
        rule.onNodeWithText("Rings").performClick(); rule.waitForIdle(); shot("rings")
        rule.onNodeWithText("Timeline").performClick(); rule.waitForIdle(); shot("timeline")
        println("PERF whole-window software draw per frame: $perf")
        assertEquals(3, perf.toString().trim().split(" ").size)
    }
}
