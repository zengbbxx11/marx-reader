package org.marxreader.app

import android.app.Application
import org.marxreader.app.data.LibraryRepository
import org.marxreader.app.data.ReaderPreferences

/** One owner for local data and pending writes, independent of Activity recreation. */
class ReaderApplication : Application() {
    private val repositoryDelegate = lazy { LibraryRepository(this) }
    val repository by repositoryDelegate
    val preferences by lazy { ReaderPreferences(this) }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN remains available on Android 14+, unlike running-low notifications.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (repositoryDelegate.isInitialized()) repository.trimLoadedBooks()
        }
    }
}
