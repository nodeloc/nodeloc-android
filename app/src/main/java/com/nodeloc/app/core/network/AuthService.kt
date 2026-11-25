package com.nodeloc.app.core.network

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.nodeloc.app.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Login/signup outcomes the UI branches on.
 *
 * Two kinds of message: ours (a string resource, so it translates) and the
 * server's (already in the reader's language), passed through verbatim.
 */
sealed class AuthError(
    @StringRes val messageRes: Int? = null,
    val serverMessage: String? = null,
) : Exception(serverMessage) {
    data object Cancelled : AuthError(R.string.auth_error_cancelled)
    data object MissingCredentials : AuthError(R.string.auth_error_missing_fields)
    data object MissingPayload : AuthError(R.string.auth_error_missing_payload)

    /** Credentials were right but the account has 2FA: ask for the code. */
    data object SecondFactorRequired : AuthError(R.string.auth_error_second_factor)
    class LoginFailed(message: String) : AuthError(serverMessage = message)
    class SignupFailed(message: String) : AuthError(serverMessage = message)
    class SignupNeedsActivation(message: String) : AuthError(serverMessage = message)

    fun text(context: Context): String =
        serverMessage ?: messageRes?.let(context::getString) ?: context.getString(R.string.error_action_failed)
}

sealed interface SignupResult {
    data object SignedIn : SignupResult
    data class NeedsActivation(val message: String) : SignupResult
}

/**
 * Signing in against the native API (docs/specs/mobile-api.md, 登录).
 *
 * Every path ends the same way: a bearer token — the session's own
 * `nl_session` value — in [SecureStore] (Keystore encrypted), attached to each
 * request by [NodelocApi]. Social sign-in runs in a Custom Tab, never in the
 * app's own WebView: providers refuse embedded views, and the browser hands
 * back a one-time code that is exchanged here, with PKCE.
 */
class AuthService(
    private val context: Context,
    private val client: DiscourseClient,
    private val store: SecureStore,
) {
    private val auth get() = client.auth

    /** Restores a stored sign-in on launch. Returns true when signed in. */
    fun restore(): Boolean {
        val token = store.get(SecureStore.KEY_TOKEN) ?: return false
        auth.token = token
        auth.hasSession = false
        auth.username = store.get(SecureStore.KEY_USERNAME)
        return true
    }

    /**
     * Drops this device's proof of who it is. `SessionRepository` owns signing
     * out, which is more than this: every cache keyed to the account goes too,
     * and the push device is unregistered before [revokeRetired] ends the
     * session on the server — both with the token kept in `retiredToken`.
     */
    fun clearCredentials() {
        val token = auth.token
        // The trusted-device mark belongs to the phone, not to the account
        // that happened to be signed in.
        val trust = store.get(SecureStore.KEY_TRUST)
        store.clearAll()
        trust?.let { store.set(SecureStore.KEY_TRUST, it) }
        client.cookieJar.clear()
        auth.retiredToken = token ?: auth.retiredToken
        auth.token = null
        auth.hasSession = false
        auth.username = null
        auth.resetRejection()
    }

    /** Ends the signed-out session on the server; a failure leaves one that expires on its own. */
    suspend fun revokeRetired() {
        val token = auth.retiredToken ?: return
        auth.retiredToken = null
        runCatching { client.api.sendRaw("POST", "api/v1/auth/logout", bearer = token) }
    }

    private fun device(): String =
        "${android.os.Build.MANUFACTURER ?: ""} ${android.os.Build.MODEL ?: ""}".trim().ifEmpty { "Android" }

    /**
     * [secondFactorToken] is the TOTP or a backup code; the server takes either
     * in the same field. The first attempt goes without one: the server
     * answering `second_factor_required` is a prompt, not an error.
     */
    suspend fun login(
        identifier: String,
        password: String,
        secondFactorToken: String? = null,
        @Suppress("UNUSED_PARAMETER") secondFactorMethod: Int = 1,
    ) {
        val login = identifier.trim()
        if (login.isEmpty() || password.isEmpty()) throw AuthError.MissingCredentials
        val code = secondFactorToken?.trim()?.takeIf { it.isNotEmpty() }
        val body = try {
            // The second-factor step is not asked again: the server remembers
            // that this login passed the challenge on the first step, for ten
            // minutes. If that has lapsed it says so and gets a new token.
            val upfront = code == null && Captcha.loginUpfront(client.api)
            Captcha.guarded(client.api, Captcha.Action.Login, upfront) { captcha ->
                client.api.sendRaw(
                    "POST",
                    "api/v1/auth/login",
                    buildJsonObject {
                        put("login", login)
                        put("password", password)
                        code?.let { put("code", it) }
                        // Asking for a code at all means the account has 2FA; a
                        // phone that just passed it is the device to trust.
                        put("trust_device", code != null)
                        captcha?.let { put("captcha", it) }
                        put("device", device())
                    },
                    headers = store.get(SecureStore.KEY_TRUST)?.let { mapOf(TRUST_HEADER to it) }.orEmpty(),
                )
            }
        } catch (error: CaptchaCancelled) {
            throw AuthError.LoginFailed(context.getString(R.string.captcha_cancelled))
        } catch (error: DiscourseError.BadResponse) {
            when (error.fault?.type) {
                "second_factor_required" -> throw AuthError.SecondFactorRequired
                "second_factor_invalid" -> throw AuthError.LoginFailed(
                    error.serverMessage ?: context.getString(R.string.auth_error_bad_code),
                )
            }
            throw AuthError.LoginFailed(error.serverMessage ?: context.getString(error.messageRes))
        }
        val root = parse(body)
        (root?.get("trust_token") as? JsonPrimitive)?.contentOrNull?.let { store.set(SecureStore.KEY_TRUST, it) }
        adopt(root)
    }

    private fun parse(body: String): JsonObject? =
        runCatching { NodelocApi.json.parseToJsonElement(body).jsonObject }.getOrNull()

    /** Reads a sign-in answer, `{"user": {...}, "token": "..."}`, into the session. */
    private fun adopt(root: JsonObject?) {
        val token = (root?.get("token") as? JsonPrimitive)?.contentOrNull
            ?: throw AuthError.LoginFailed(context.getString(R.string.auth_error_no_session))
        val username = ((root["user"] as? JsonObject)?.get("username") as? JsonPrimitive)?.contentOrNull
            ?: throw AuthError.LoginFailed(context.getString(R.string.auth_error_incomplete))
        auth.resetRejection()
        auth.token = token
        auth.hasSession = false
        auth.username = username
        auth.retiredToken = null
        store.set(SecureStore.KEY_TOKEN, token)
        store.set(SecureStore.KEY_USERNAME, username)
    }

    /**
     * Without an email code the server creates the account and mails a
     * verification link — the "check your inbox" outcome this screen already
     * has. Where it signs the new account straight in (`logged_in`), so does the app.
     */
    suspend fun signup(
        username: String,
        name: String,
        email: String,
        password: String,
        @Suppress("UNUSED_PARAMETER") userFields: Map<Int, String> = emptyMap(),
    ): SignupResult {
        val user = username.trim()
        val mail = email.trim()
        if (user.isEmpty() || mail.isEmpty() || password.isEmpty()) throw AuthError.MissingCredentials
        val body = try {
            Captcha.guarded(client.api, Captcha.Action.Register, upfront = true) { captcha ->
                client.api.sendRaw(
                    "POST",
                    "api/v1/auth/register",
                    buildJsonObject {
                        put("username", user)
                        put("name", name.trim())
                        put("email", mail)
                        put("password", password)
                        inviteCodeField().forEach { (key, value) -> put(key, value) }
                        captcha?.let { put("captcha", it) }
                        put("device", device())
                    },
                )
            }
        } catch (error: CaptchaCancelled) {
            throw AuthError.SignupFailed(context.getString(R.string.captcha_cancelled))
        } catch (error: DiscourseError.BadResponse) {
            throw AuthError.SignupFailed(error.serverMessage ?: context.getString(error.messageRes))
        }
        val root = parse(body)
        val loggedIn = (root?.get("logged_in") as? JsonPrimitive)?.contentOrNull == "true"
        if (loggedIn && root?.get("token") != null) {
            adopt(root)
            return SignupResult.SignedIn
        }
        val message = (root?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: context.getString(R.string.auth_signup_activation_needed)
        return SignupResult.NeedsActivation(message)
    }

    /** The name behind a session the app did not learn one from at sign-in. */
    fun rememberUsername(username: String) {
        auth.username = username
        store.set(SecureStore.KEY_USERNAME, username)
    }

    /** Nothing to refresh: a bearer token carries no CSRF pair. */
    suspend fun refreshCsrfToken() = Unit

    /**
     * The browser sign-in's starting address: a provider's own [providerUrl]
     * from `/api/v1/auth/providers`, or the site's login page when null (the
     * user picks there). PKCE ties the code that comes back to this install.
     */
    fun buildAuthorizationUrl(providerUrl: String? = null): String {
        val verifier = Pkce.verifier()
        store.set(SecureStore.KEY_PKCE_VERIFIER, verifier)
        store.set(SecureStore.KEY_PKCE_STARTED_AT, System.currentTimeMillis().toString())
        val start = providerUrl?.takeIf { it.isNotBlank() }?.let { DiscourseConfig.absoluteUrl(it) }
            ?: "${DiscourseConfig.BASE_URL}/login"
        return Uri.parse(start).buildUpon()
            .appendQueryParameter("app", "android")
            .appendQueryParameter("redirect_uri", DiscourseConfig.AUTH_REDIRECT)
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .build()
            .toString()
    }

    /**
     * Handles `nodeloc://auth?code=…` (or `?error=cancelled`): trades the
     * one-time code for a session token.
     */
    suspend fun handleAuthRedirect(uri: Uri) {
        val verifier = store.get(SecureStore.KEY_PKCE_VERIFIER)
            ?.takeIf {
                val started = store.get(SecureStore.KEY_PKCE_STARTED_AT)?.toLongOrNull() ?: 0
                System.currentTimeMillis() - started < PENDING_TTL_MILLIS
            }
        store.clear(SecureStore.KEY_PKCE_VERIFIER, SecureStore.KEY_PKCE_STARTED_AT)
        if (uri.getQueryParameter("error") != null) throw AuthError.Cancelled
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: throw AuthError.MissingPayload
        val body = try {
            client.api.sendRaw(
                "POST",
                "api/v1/auth/app/exchange",
                buildJsonObject {
                    put("code", code)
                    verifier?.let { put("code_verifier", it) }
                    put("device", device())
                },
            )
        } catch (error: DiscourseError.BadResponse) {
            throw AuthError.LoginFailed(error.serverMessage ?: context.getString(error.messageRes))
        }
        adopt(parse(body))
    }

    private companion object {
        const val TRUST_HEADER = "X-Nodeloc-Trust"

        /** The code is good for two minutes; a little longer covers a slow sign-in page. */
        const val PENDING_TTL_MILLIS = 15 * 60 * 1000L
    }
}

/** RFC 7636 S256: kept free of Android types so it runs in a JVM test. */
internal object Pkce {
    fun verifier(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    fun challenge(verifier: String): String =
        base64Url(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The site's invite code, when it has one. Absent rather than empty when there
 * is none, so a site without codes never sees the field at all.
 */
internal fun inviteCodeField(code: String = DiscourseConfig.INVITE_CODE): List<Pair<String, String>> =
    code.takeIf { it.isNotBlank() }
        ?.let { listOf("invite_code" to it) }
        ?: emptyList()
