package dev.cyh.aamp.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code aamp runs on its two hot paths, so it is compiled ahead of time and laid out
 * together in the dex files:
 *
 * - The Android Auto path: the playback service starting, the saved library loading, and voice
 *   requests being parsed and matched, driven the way Android Auto's are, through the session.
 * - The phone screen: start, the album grid scrolled, each tab, an album page, and search.
 *
 * Needs music on the device. Run with `./gradlew :app:generateBaselineProfile`, naming the device
 * in ANDROID_SERIAL when more than one is connected.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        device.executeShellCommand("pm grant $PACKAGE android.permission.READ_MEDIA_AUDIO")
        device.executeShellCommand("pm grant $PACKAGE android.permission.READ_MEDIA_IMAGES")

        for (query in VOICE_QUERIES) voice(query)
        device.executeShellCommand("cmd media_session dispatch pause")

        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Albums")), TIMEOUT_MS)
        repeat(3) { fling(Direction.DOWN) }
        fling(Direction.UP)
        for (tab in TABS) {
            retrying { device.findObject(By.text(tab))?.click() }
            device.waitForIdle()
            fling(Direction.DOWN)
        }
        retrying { device.findObject(By.text("Albums"))?.click() }
        device.waitForIdle()
        // Open the first album, then come back.
        retrying { device.findObject(By.scrollable(true))?.findObject(By.clickable(true))?.click() }
        device.wait(Until.hasObject(By.desc("Back")), TIMEOUT_MS)
        device.pressBack()
        // Search: building the index, then typing, a word at a time.
        device.wait(Until.hasObject(By.desc("Search")), TIMEOUT_MS)
        retrying { device.findObject(By.desc("Search"))?.click() }
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MS)
        for (text in listOf("m", "et", "al", "%sone")) {
            device.executeShellCommand("input text $text")
            Thread.sleep(SEARCH_SETTLE_MS)
        }
        device.pressBack()
        device.pressBack()
    }

    private fun MacrobenchmarkScope.fling(direction: Direction) = retrying { device.findObject(By.scrollable(true))?.fling(direction) }

    /** Runs [action] again with fresh objects when Compose has replaced the ones it found. */
    private fun MacrobenchmarkScope.retrying(action: () -> Unit) {
        repeat(RETRIES) {
            try {
                action()
                return
            } catch (_: StaleObjectException) {
                device.waitForIdle()
            }
        }
    }

    /** Replays a voice request through the same session path Android Auto uses. */
    private fun MacrobenchmarkScope.voice(query: String) {
        device.executeShellCommand(
            "am start -W -n $PACKAGE/.voice.PlayFromSearchActivity -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query '$query'",
        )
        Thread.sleep(VOICE_SETTLE_MS)
    }

    private companion object {
        const val PACKAGE = "dev.cyh.aamp"
        const val TIMEOUT_MS = 5_000L
        const val VOICE_SETTLE_MS = 1_500L
        const val RETRIES = 3
        const val SEARCH_SETTLE_MS = 400L
        val TABS = listOf("Artists", "Songs", "Genres", "Playlists")

        /** Requests that exercise each kind of resolution, whether or not the library has a match. */
        val VOICE_QUERIES = listOf(
            "shuffle",
            "play the album kill em all by metallica",
            "play one from the album and justice for all",
            "play songs by the band metallica",
            "play some rock music",
            "play my road trip playlist",
        )
    }
}
