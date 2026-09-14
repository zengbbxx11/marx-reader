package org.marxreader.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.marxreader.app.data.LibraryRepository

@RunWith(AndroidJUnit4::class)
class ReaderRotationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun roundTripRotationKeepsTheSourcePage() {
        runBlocking { LibraryRepository(compose.activity.applicationContext).clearProgress() }
        waitForText("马克思")
        compose.onNodeWithText("马克思").performClick()
        waitForText("共产党宣言")
        compose.onNodeWithText("共产党宣言").performClick()
        waitForText("1/")
        compose.onNodeWithContentDescription("下一页").performClick()
        waitForText("2/")
        compose.onNodeWithContentDescription("下一页").performClick()
        waitForText("3/")
        try {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(20_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitForIdle()
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(20_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            waitForText("3/")
            compose.activityRule.scenario.recreate()
            waitForText("3/")
        } finally {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}
