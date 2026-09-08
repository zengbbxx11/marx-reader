package org.marxreader.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

class ShelfNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectedHomeTabSurvivesActivityRecreation() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("书架").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("书架").performClick()
        composeRule.onNodeWithText("我的书架").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("我的书架").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("我的书架").assertIsDisplayed()
    }

    @Test
    fun shelfIsReachableFromBottomNavigation() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("书架").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("书架").performClick()
        composeRule.onNodeWithText("我的书架").assertIsDisplayed()
    }

    @Test
    fun localReadingDataIsReachableFromSettings() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("本地阅读数据"))
        composeRule.onNodeWithText("本地阅读数据").assertIsDisplayed()
    }
}
