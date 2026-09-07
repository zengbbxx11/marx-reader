package org.marxreader.app

import android.app.Application
import org.marxreader.app.data.LibraryRepository
import org.marxreader.app.data.ReaderPreferences

/** One owner for local data and pending writes, independent of Activity recreation. */
class ReaderApplication : Application() {
    val repository by lazy { LibraryRepository(this) }
    val preferences by lazy { ReaderPreferences(this) }
}
