package com.nodeloc.app.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` catches `Throwable`, and cancellation is a `Throwable`.
 *
 * So a job cancelled by a newer request — a refresh landing on a load, a screen
 * being left — was reported as though the request had failed: "couldn't load"
 * written into state that is about to be discarded, or on the sign-in screen,
 * "login failed" for the user who simply navigated away.
 *
 * Use this wherever the result reaches UI state, a toast, or a rendered list.
 * A `runCatching` around a cache write or a preference read can stay as it is;
 * swallowing a cancellation there costs nothing.
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (error: Throwable) {
    Result.failure(error)
}

/**
 * The same rule for a `catch (error: Throwable)` that already exists — one line
 * at the top of the block, rather than restructuring it.
 *
 * Public because `DiscourseClient.decode` is an inline function and cannot
 * reference anything less visible than itself.
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
