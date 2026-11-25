package com.nodeloc.app.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** `GET /api/v1/auth/captcha`. When off, only `enabled: false` (and `switch`) come back. */
@Serializable
data class ApiCaptchaConfig(
    val enabled: Boolean = false,
    /** `hcaptcha`, `turnstile` or `recaptcha`. */
    val provider: String = "",
    val siteKey: String = "",
    /** The provider's script, already carrying `render=explicit` and an onload hook for hCaptcha. */
    val script: String = "",
    val actions: Map<String, Boolean> = emptyMap(),
    /** `always`, or `after_failures` — then the server asks with `captcha_required` once it wants one. */
    val loginMode: String = "always",
)

/** The user closed the challenge instead of solving it. */
class CaptchaCancelled : Exception("captcha cancelled")

/**
 * Human verification for the four signed-out writes the server guards:
 * login, register, the register email code and password reset.
 *
 * The token comes from the provider's own web widget, so solving needs UI; the
 * network layer asks for one here and suspends, and the single [requests]
 * observer (`CaptchaHost`, mounted at the app root) shows it. That keeps the
 * auth code linear — ask, get a token, send it — instead of threading a
 * dialog through every screen that can sign someone in.
 */
object Captcha {

    enum class Action(val key: String) {
        Login("login"),
        Register("register"),
        RegisterCode("register_code"),
        PasswordReset("password_reset"),
    }

    class Request(val config: ApiCaptchaConfig, val action: Action, val result: CompletableDeferred<String?>)

    private val _requests = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = _requests.asStateFlow()

    private val lock = Mutex()
    @Volatile private var cached: ApiCaptchaConfig? = null
    @Volatile private var cachedAt = 0L

    /**
     * Admins switch this live from the settings page, so it is only trusted
     * for a few minutes; a refused request forces a fresh read anyway.
     */
    suspend fun config(api: NodelocApi, force: Boolean = false): ApiCaptchaConfig = lock.withLock {
        val fresh = System.currentTimeMillis() - cachedAt < 5 * 60_000
        cached?.takeIf { fresh && !force }?.let { return@withLock it }
        val config = try {
            api.get<ApiCaptchaConfig>("api/v1/auth/captcha")
        } catch (error: DiscourseError) {
            // Unknown is treated as off: the write itself will say if a token
            // was needed, and that answer brings us back here with force.
            ApiCaptchaConfig()
        }
        cached = config
        cachedAt = System.currentTimeMillis()
        config
    }

    private fun ApiCaptchaConfig.guards(action: Action): Boolean =
        enabled && siteKey.isNotBlank() && actions[action.key] == true

    /** Shows the challenge and waits for its token; throws [CaptchaCancelled] if dismissed. */
    private suspend fun solve(config: ApiCaptchaConfig, action: Action): String {
        val request = Request(config, action, CompletableDeferred())
        _requests.value = request
        try {
            return request.result.await() ?: throw CaptchaCancelled()
        } finally {
            _requests.compareAndSet(request, null)
        }
    }

    /**
     * Runs [block] with a token when [action] needs one. [upfront] solves before
     * the first attempt (the server is set to always ask); otherwise the first
     * attempt goes without and a `captcha_required` / `captcha_invalid` answer
     * gets one challenge and one retry. Tokens are single-use, so a retry always
     * needs a new one.
     */
    suspend fun <T> guarded(api: NodelocApi, action: Action, upfront: Boolean, block: suspend (token: String?) -> T): T {
        val config = config(api)
        val first = if (upfront && config.guards(action)) solve(config, action) else null
        return try {
            block(first)
        } catch (error: DiscourseError.BadResponse) {
            val type = error.fault?.type
            if (type != "captcha_required" && type != "captcha_invalid") throw error
            val current = config(api, force = true)
            if (current.siteKey.isBlank()) throw error
            block(solve(current, action))
        }
    }

    /** Login asks up front only when the site wants it every time. */
    suspend fun loginUpfront(api: NodelocApi): Boolean = config(api).loginMode != "after_failures"
}
