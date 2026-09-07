package org.marxreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import org.marxreader.app.ui.ReaderApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val container = application as ReaderApplication
        setContent {
            ReaderApp(container.repository, container.preferences)
        }
    }
}
