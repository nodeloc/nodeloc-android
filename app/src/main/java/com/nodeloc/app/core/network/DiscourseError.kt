package com.nodeloc.app.core.network

import android.content.Context
import androidx.annotation.StringRes
import com.nodeloc.app.R
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Every failure the UI can see, already reduced to something a person can act
 * on. Status codes and stack traces never reach a screen — the mapping lives
 * here so the wording can't drift between call sites.
 */
/**
 * What Discourse said went wrong.
 *
 * `errors[]` is prose written for a person — "标题太短" — and the only
 * actionable thing in a rejected write. It is localised by the *site's* locale,
 * not the device's, which is the same bargain [AuthError.text] already makes
 * for sign-in failures.
 */
data class ServerFault(
    val messages: List<String> = emptyList(),
    /** Discourse's `error_type`, e.g. `not_logged_in`, `invalid_access`. */
    val type: String? = null,
    /** From a 429's `extras.wait_seconds`. */
    val waitSeconds: Int? = null,
)

sealed class DiscourseError(message: String? = null, cause: Throwable? = null) :
    IOException(message, cause) {

    data class BadResponse(
        val code: Int,
        val bodyPrefix: String? = null,
        val fault: ServerFault? = null,
    ) : DiscourseError("HTTP $code")

    /**
     * Cloudflare answered with a challenge instead of the API. Distinct from a
     * real permission error: `server: cloudflare` is on *every* response here,
     * so the tell is `cf-mitigated` or a 403 with an HTML body.
     */
    data object Challenged : DiscourseError("challenged") {
        private fun readResolve(): Any = Challenged
    }

    data class Decoding(val error: Throwable) : DiscourseError("decoding", error)

    data class Transport(val error: Throwable) : DiscourseError("transport", error)

    /** True when the device has no usable network, so screens can offer a retry. */
    val isOffline: Boolean
        get() = this is Transport &&
            (error is UnknownHostException || error is ConnectException || error is SSLException)

    val isTimeout: Boolean get() = this is Transport && error is SocketTimeoutException

    val isNotFound: Boolean get() = this is BadResponse && code == 404

    /**
     * A *display* predicate: both codes read as "you may not do that", which is
     * what [messageRes] says. For deciding whether the session itself is dead,
     * use [isNotLoggedIn] — a 403 is far more often a permission problem.
     */
    val isUnauthorized: Boolean get() = this is BadResponse && (code == 401 || code == 403)

    /**
     * Discourse *may* be saying this session is not usable.
     *
     * May, because `invalid_access` is its word for two different things: a
     * credential it will not accept, and a thing this reader is not allowed to
     * have. Whether it is the first is not decided here — see
     * `DiscourseClient.sessionIsGone`, which asks.
     */
    val isNotLoggedIn: Boolean
        get() = this is BadResponse && (fault?.type == "not_logged_in" || fault?.type == "invalid_access")

    /** No session at all, said outright: the one answer needing no second opinion. */
    val isSessionGone: Boolean
        get() = this is BadResponse && fault?.type == "not_logged_in"

    val isRateLimited: Boolean
        get() = this is BadResponse && (code == 429 || fault?.type == "rate_limit")

    val retryAfterSeconds: Int? get() = (this as? BadResponse)?.fault?.waitSeconds

    /**
     * Server prose fit to show as-is, or null to fall back to a resource.
     * Gated in `DiscourseClient.classify`, not here, so there is one place to
     * audit what is allowed through.
     */
    val serverMessage: String?
        get() = (this as? BadResponse)?.fault?.messages?.takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** Friendly, user-facing wording only. */
    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            is BadResponse -> when {
                code == 401 || code == 403 -> R.string.error_unauthorized
                code == 404 -> R.string.error_not_found
                isRateLimited -> R.string.error_rate_limited
                code >= 500 -> R.string.error_server
                else -> R.string.error_generic_request
            }
            Challenged -> R.string.error_challenged
            is Decoding -> R.string.error_decoding
            is Transport -> when {
                isOffline -> R.string.error_offline
                isTimeout -> R.string.error_timeout
                else -> R.string.error_transport
            }
        }
}

/** Friendly text for any throwable, so callers never have to branch. */
fun Throwable.friendlyMessage(context: Context): String {
    val error = this as? DiscourseError ?: return context.getString(R.string.error_action_failed)
    // The server's own words when it gave any: "request failed" is useless next
    // to "title is too short", and mapping one onto the other throws away the
    // only thing that tells the user what to change.
    error.serverMessage?.let { return it }
    error.retryAfterSeconds?.let { return context.getString(R.string.error_rate_limited_wait, it) }
    return context.getString(error.messageRes)
}

/** Whether an empty state should offer a retry rather than say "nothing here". */
fun Throwable.isOfflineError(): Boolean = (this as? DiscourseError)?.isOffline == true
