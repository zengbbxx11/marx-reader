package org.marxreader.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

internal fun Screen.stateKey(): String = when (this) {
    Screen.Home -> "home"
    is Screen.AuthorDetail -> "author-$authorId"
    is Screen.BookDetail -> "book-$bookId"
    is Screen.Reader -> "reader-$bookId"
}

/** Full book contents live only as long as the visible reader destination. */
@Composable
internal fun ReaderDestination(destinationKey: Any = Unit, content: @Composable () -> Unit) {
    val owner = remember(destinationKey) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
