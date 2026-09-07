package org.marxreader.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.marxreader.app.ui.ReaderDestination

class ReaderDestinationLifetimeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun leavingReaderClearsItsViewModels() {
        val visible = mutableStateOf(true)
        val model = TrackedViewModel()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
        }
        composeRule.setContent {
            if (visible.value) ReaderDestination {
                viewModel<TrackedViewModel>(factory = factory)
            }
        }
        composeRule.runOnIdle { visible.value = false }
        composeRule.runOnIdle { assertTrue(model.cleared) }
    }

    private class TrackedViewModel : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }
}
