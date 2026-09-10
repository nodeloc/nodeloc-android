package com.nodeloc.app.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A post saved from somewhere other than the screen showing it.
 *
 * Editing a topic leaves the reader for the composer, and the reader it comes
 * back to is the one it left: same view model, same posts, none of them aware
 * that one of them has just been rewritten. This is how it finds out.
 *
 * A single slot rather than a stream of events: two edits between one reader
 * and the next still mean the same reload, and a signal nobody was there to
 * hear is worth keeping — the reader consumes it when it next appears.
 */
object PostEdits {
    private val _saved = MutableStateFlow<Int?>(null)
    val saved: StateFlow<Int?> = _saved.asStateFlow()

    fun mark(postId: Int) {
        _saved.value = postId
    }

    /** Reads the slot and empties it, so one edit reloads one reader once. */
    fun consume(): Int? = _saved.value.also { _saved.value = null }
}
