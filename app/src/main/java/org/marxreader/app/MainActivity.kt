package org.marxreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import org.marxreader.app.data.LibraryRepository
import org.marxreader.app.data.ReaderPreferences
import org.marxreader.app.ui.ReaderApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val repository = LibraryRepository(applicationContext)
        val preferences = ReaderPreferences(applicationContext)
        setContent {
            ReaderApp(repository, preferences)
        }
    }
}
