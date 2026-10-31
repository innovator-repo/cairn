package app.cairn

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real UI on the JVM and writes PNGs when -Pshots=<dir> is passed (see docs/screenshots).
 * Without the property it only checks that the main screen and the view switches compose.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val dir = System.getProperty("cairn.shots") ?: return
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settle(ms: Long = 1500) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    @Test fun globeAndFlatViews() {
        Thread.sleep(1500) // seeding and graph build run off the main thread
        settle()
        shot("globe")
        rule.onNodeWithText("Areas").performClick(); settle(); shot("areas")
        rule.onNodeWithText("Rings").performClick(); settle(); shot("rings")
        rule.onNodeWithText("Links").performClick(); settle(); shot("links")
        rule.onNodeWithText("Timeline").performClick(); settle(); shot("timeline")
        rule.onNodeWithText("Globe").performClick(); settle()
    }
}
