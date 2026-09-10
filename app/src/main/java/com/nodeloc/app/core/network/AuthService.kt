package com.nodeloc.app.core.network

import com.nodeloc.app.core.model.HoneypotResponse
import com.nodeloc.app.core.util.rethrowIfCancellation
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.nodeloc.app.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * Login/signup outcomes the UI branches on.
 *
 * Two kinds of message: ours (a string resource, so it translates) and the
 * server's (already localised by Discourse, passed through verbatim).
 */
sealed class AuthError(
    @StringRes val messageRes: Int? = null,
    val serverMessage: String? = null,
) : Exception(serverMessage) {
    data object KeyGeneration : AuthError(R.string.auth_error_key_generation)
    data object MissingPayload : AuthError(R.string.auth_error_missing_payload)

    /**
     * The site answered, but this install no longer holds the key it encrypted
     * to — the flow was abandoned, or took longer than the window allows.
     * Distinct from [MissingPayload], where the redirect carried nothing.
     */
    data object LostKeyPair : AuthError(R.string.auth_error_lost_keypair)
    data object DecryptFailed : AuthError(R.string.auth_error_decrypt)
    data object NonceMismatch : AuthError(R.string.auth_error_nonce)
    data object Cancelled : AuthError(R.string.auth_error_cancelled)
    data object MissingCredentials : AuthError(R.string.auth_error_missing_fields)

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

@Serializable private data class CsrfResponse(val csrf: String)

@Serializable private data class LoggedInUser(val username: String)

/**
 * `POST /session` answers HTTP 200 even when login fails — the outcome lives in
 * the body. A wrong password, an unactivated account, a social-only account and
 * a required second factor all arrive as 200 with an `error`/`reason`.
 */
@Serializable
private data class SessionLoginResponse(
    val error: String? = null,
    val failed: String? = null,
    val reason: String? = null,
    val totp_enabled: Boolean? = null,
    val backup_enabled: Boolean? = null,
    val user: LoggedInUser? = null,
)

@Serializable
private data class SignupResponse(
    val success: Boolean? = null,
    val active: Boolean? = null,
    val message: String? = null,
)

@Serializable
private data class AuthMessageResponse(
    val message: String? = null,
    val error: String? = null,
    val reason: String? = null,
    val errors: List<String>? = null,
)

@Serializable private data class UserApiKeyPayload(val key: String, val nonce: String)

/**
 * Both sign-in paths: the website session (username/password, with TOTP or a
 * backup code) and Discourse's User API Key flow — the same mechanism the
 * official app uses — where the site encrypts the key to a keypair generated
 * here and redirects to `nodeloc://auth`.
 */
class AuthService(
    private val context: Context,
    private val client: DiscourseClient,
    private val store: SecureStore,
) {
    private val auth get() = client.auth

    // Kept between starting the OAuth flow and handling its redirect.
    private var pendingKeyPair: KeyPair? = null
    private var pendingNonce: String = ""

    private val laxJson = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

    // ------------------------------------------------------------- restore

    /** Restores a stored sign-in on launch. Returns true when signed in. */
    fun restore(): Boolean {
        val key = store.get(SecureStore.KEY_USER_API)
        val hasCookies = client.cookieJar.hasSessionCookie()
        if (key == null && !hasCookies) return false
        auth.userApiKey = key
        auth.hasSession = hasCookies
        auth.csrfToken = store.get(SecureStore.KEY_CSRF)
        auth.username = store.get(SecureStore.KEY_USERNAME)
        return true
    }

    /**
     * Drops this device's proof of who it is. Named for what it does rather
     * than for the user-facing act: signing out is more than this, and
     * `SessionRepository` owns it — every other cache keyed to the account has
     * to go at the same time, or the next account inherits it.
     */
    fun clearCredentials() {
        store.clearAll()
        client.cookieJar.clear()
        auth.userApiKey = null
        auth.hasSession = false
        auth.csrfToken = null
        auth.username = null
        // Last, not first: between resetting the latch and nulling these there
        // is a window — two disk writes wide — where a second parallel 403
        // would pass the "sign out once" guard and do it all again.
        auth.resetRejection()
    }

    // ------------------------------------------------ username / password

    /**
     * [secondFactorToken] is the OTP (method 1) or a backup code (method 2).
     * The first attempt goes without one; the server answering
     * `invalid_second_factor` is a *prompt*, not an error, and must not throw
     * the user out of the form.
     */
    suspend fun login(
        identifier: String,
        password: String,
        secondFactorToken: String? = null,
        secondFactorMethod: Int = 1,
    ) {
        val login = identifier.trim()
        val secret = password.trim()
        if (login.isEmpty() || secret.isEmpty()) throw AuthError.MissingCredentials

        // The jar has to start empty — Discourse issues CSRF against a fresh
        // `_forum_session` — but a wrong password must not evict whoever is
        // already signed in, and a 2FA prompt is a retry of this same call.
        val previousCookies = client.cookieJar.snapshot()
        val previousAuth = auth.snapshot()
        client.cookieJar.clear()
        auth.userApiKey = null
        auth.hasSession = false
        auth.csrfToken = null
        auth.username = null

        try {
            val csrf = fetchCsrfToken()
            auth.csrfToken = csrf

            val form = mutableListOf(
                "login" to login,
                "password" to secret,
                "second_factor_method" to secondFactorMethod.toString(),
                "timezone" to client.timezone,
            )
            secondFactorToken?.let { form += "second_factor_token" to it }

            val body = client.formItems("POST", "session", form)
            val result = runCatching { laxJson.decodeFromString<SessionLoginResponse>(body) }.getOrNull()

            if (result?.reason == "invalid_second_factor") {
                // No token yet: the server is asking for one. With a token: it was wrong.
                if (secondFactorToken == null) throw AuthError.SecondFactorRequired
                throw AuthError.LoginFailed(result.error ?: context.getString(R.string.auth_error_bad_code))
            }
            (result?.error ?: result?.failed)?.let { throw AuthError.LoginFailed(it) }
            val username = result?.user?.username
                ?: throw AuthError.LoginFailed(context.getString(R.string.auth_error_incomplete))

            if (!client.cookieJar.hasSessionCookie()) {
                throw AuthError.LoginFailed(context.getString(R.string.auth_error_no_session))
            }

            // Only a success is allowed to touch what is stored.
            auth.resetRejection()
            auth.userApiKey = null
            auth.hasSession = true
            auth.username = username
            store.set(SecureStore.KEY_USER_API, null)
            store.set(SecureStore.KEY_CSRF, csrf)
            store.set(SecureStore.KEY_USERNAME, username)
        } catch (error: Throwable) {
            client.cookieJar.restoreSnapshot(previousCookies)
            auth.restore(previousAuth)
            throw error
        }
    }

    /** There is no 6-digit code path: unactivated accounts get an email link. */
    suspend fun signup(
        username: String,
        name: String,
        email: String,
        password: String,
        userFields: Map<Int, String> = emptyMap(),
    ): SignupResult {
        val user = username.trim()
        val mail = email.trim()
        val secret = password.trim()
        if (user.isEmpty() || mail.isEmpty() || secret.isEmpty()) throw AuthError.MissingCredentials

        // Same bargain as login: a clean jar for CSRF, put back on failure so a
        // rejected signup does not sign out whoever was already here.
        val previousCookies = client.cookieJar.snapshot()
        val previousAuth = auth.snapshot()
        client.cookieJar.clear()
        auth.userApiKey = null
        auth.hasSession = false
        auth.csrfToken = null
        auth.username = null

        val body = try {
            auth.csrfToken = fetchCsrfToken()
            // Fetched on the same session as the POST, because that is where
            // the server keeps the answers it will check against. Not made
            // optional: without these fields the signup is guaranteed to do
            // nothing while reporting success, so failing here is the honest
            // outcome.
            val honeypot = honeypotFields(client.honeypot())
                ?: throw AuthError.SignupFailed(context.getString(R.string.auth_error_session_start))
            client.formItems(
                "POST",
                "users",
                listOf(
                    "username" to user,
                    "name" to name.trim().ifEmpty { user },
                    "email" to mail,
                    "password" to secret,
                    "timezone" to client.timezone,
                ) + honeypot + inviteCodeField() + userFieldItems(userFields),
            )
        } catch (error: DiscourseError.BadResponse) {
            client.cookieJar.restoreSnapshot(previousCookies)
            auth.restore(previousAuth)
            // serverMessage first: it is parsed from the whole body, where
            // `messageFrom` only ever saw the truncated prefix.
            throw AuthError.SignupFailed(
                error.serverMessage
                    ?: messageFrom(error.bodyPrefix)
                    ?: context.getString(error.messageRes),
            )
        } catch (error: Throwable) {
            client.cookieJar.restoreSnapshot(previousCookies)
            auth.restore(previousAuth)
            throw error
        }

        val signup = runCatching { laxJson.decodeFromString<SignupResponse>(body) }.getOrNull()
        val message = signup?.message ?: messageFrom(body) ?: context.getString(R.string.auth_signup_activation_needed)
        if (signup?.success == false) {
            client.cookieJar.restoreSnapshot(previousCookies)
            auth.restore(previousAuth)
            throw AuthError.SignupFailed(message)
        }

        if (signup?.active == true) {
            // An active account means Discourse already signed this device in,
            // so a failure here leaves a live cookie for the new account behind
            // a UI that says signup failed. The inner login() restores only to
            // what signup established; this restores to what the device had.
            try {
                login(user, secret)
            } catch (error: Throwable) {
                client.cookieJar.restoreSnapshot(previousCookies)
                auth.restore(previousAuth)
                throw error
            }
            return SignupResult.SignedIn
        }
        // An account awaiting activation is not a session: leave the device as
        // it was rather than half-signed-in as somebody who cannot post yet.
        client.cookieJar.restoreSnapshot(previousCookies)
        auth.restore(previousAuth)
        return SignupResult.NeedsActivation(message)
    }

    private suspend fun fetchCsrfToken(): String {
        val body = client.getRaw("session/csrf.json")
        return runCatching { laxJson.decodeFromString<CsrfResponse>(body).csrf }
            .getOrElse { throw AuthError.LoginFailed(context.getString(R.string.auth_error_session_start)) }
    }

    /**
     * A sign-in that finished in the website's own window.
     *
     * The jar has just been handed the `_t` that flow produced, but a cookie on
     * its own is not a session to the rest of the app: [DiscourseAuth
     * .isAuthenticated] gates every request — `refresh()` does not even ask the
     * server who we are without it — and the CSRF token gates every write. The
     * adopted cookie arrives with neither, which is why a completed Google
     * sign-in still reported itself as unfinished.
     */
    suspend fun adoptWebSession(): Boolean {
        if (!client.cookieJar.hasSessionCookie()) return false
        // A different account until proven otherwise, for the same reason
        // handleAuthRedirect clears it: a leftover name makes the sign-in path
        // believe nothing changed and skip the purge.
        auth.username = null
        auth.resetRejection()
        auth.userApiKey = null
        auth.hasSession = true
        store.set(SecureStore.KEY_USER_API, null)
        // `_forum_session`, which the token is bound to, stayed behind in the
        // WebView's store — so this fetch is OkHttp getting a matched pair of
        // its own rather than a refresh of anything.
        refreshCsrfToken()
        return true
    }

    /** The name behind a session the app did not learn one from at sign-in. */
    fun rememberUsername(username: String) {
        auth.username = username
        store.set(SecureStore.KEY_USERNAME, username)
    }

    /** Refreshes the CSRF token after a cookie session is restored. */
    suspend fun refreshCsrfToken() {
        runCatching { fetchCsrfToken() }.onSuccess {
            auth.csrfToken = it
            store.set(SecureStore.KEY_CSRF, it)
        }
    }

    private fun messageFrom(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val parsed = runCatching { laxJson.decodeFromString<AuthMessageResponse>(body) }.getOrNull()
        return parsed?.message ?: parsed?.error ?: parsed?.reason ?: parsed?.errors?.joinToString("\n")
    }

    // --------------------------------------------- User API Key (website OAuth)

    /**
     * Builds the authorization URL and stashes the private key. The caller
     * opens it in a Custom Tab; the site redirects back to `nodeloc://auth`
     * with the key RSA-encrypted to [pendingKeyPair].
     */
    fun buildAuthorizationUrl(): String {
        val pair = generateKeyPair() ?: throw AuthError.KeyGeneration
        pendingKeyPair = pair
        pendingNonce = randomNonce()
        val pem = publicKeyPem(pair)

        // The Custom Tab belongs to another app, so this process can be killed
        // behind it — and then the redirect arrives with nothing to decrypt it.
        // Keystore-wrapped, and gone the moment the flow ends either way.
        store.set(SecureStore.KEY_AUTH_PRIVATE_KEY, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
        store.set(SecureStore.KEY_AUTH_NONCE, pendingNonce)
        store.set(SecureStore.KEY_AUTH_STARTED_AT, System.currentTimeMillis().toString())

        return "${DiscourseConfig.BASE_URL}/user-api-key/new".toUri().buildUpon()
            .appendQueryParameter("application_name", DiscourseConfig.APP_NAME)
            .appendQueryParameter("client_id", DiscourseConfig.clientId(context))
            .appendQueryParameter("scopes", DiscourseConfig.SCOPES)
            .appendQueryParameter("public_key", pem)
            .appendQueryParameter("nonce", pendingNonce)
            .appendQueryParameter("auth_redirect", DiscourseConfig.AUTH_REDIRECT)
            .build()
            .toString()
    }

    /** Handles the `nodeloc://auth?payload=…` redirect. */
    suspend fun handleAuthRedirect(uri: Uri) {
        try {
            val raw = uri.getQueryParameter("payload") ?: throw AuthError.MissingPayload
            val privateKey = pendingPrivateKey() ?: throw AuthError.LostKeyPair
            val nonce = pendingNonce.ifEmpty { store.get(SecureStore.KEY_AUTH_NONCE).orEmpty() }

            // URL decoding can turn '+' into a space; base64 needs it back.
            val base64 = raw.replace(" ", "+")
            val clear = try {
                val encrypted = Base64.decode(base64, Base64.DEFAULT)
                Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                    init(Cipher.DECRYPT_MODE, privateKey)
                    doFinal(encrypted)
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                throw AuthError.DecryptFailed
            }

            val payload = runCatching { laxJson.decodeFromString<UserApiKeyPayload>(String(clear)) }
                .getOrElse { throw AuthError.DecryptFailed }
            if (nonce.isEmpty() || payload.nonce != nonce) throw AuthError.NonceMismatch

            // Cleared before the new key lands: this is a different account
            // until proven otherwise, and leaving the previous name here made
            // the sign-in path believe nothing had changed.
            auth.username = null
            auth.resetRejection()
            auth.userApiKey = payload.key
            auth.hasSession = false
            store.set(SecureStore.KEY_USER_API, payload.key)

            runCatching { client.currentUser() }.onSuccess {
                auth.username = it.currentUser.username
                store.set(SecureStore.KEY_USERNAME, it.currentUser.username)
            }
        } finally {
            // Every exit, including the failures: a half-finished flow left on
            // disk is a private key with nothing to decrypt.
            clearPendingAuth()
        }
    }

    /**
     * The in-memory keypair, or the persisted one when the process was killed
     * behind the Custom Tab.
     *
     * The window exists because closing the tab sends no signal — there is no
     * cancellation callback to hang cleanup on, so age is the only way to tell
     * an interrupted flow from an abandoned one.
     */
    private fun pendingPrivateKey(): PrivateKey? {
        pendingKeyPair?.private?.let { return it }

        val startedAt = store.get(SecureStore.KEY_AUTH_STARTED_AT)?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - startedAt > PENDING_AUTH_TTL_MILLIS) return null
        val encoded = store.get(SecureStore.KEY_AUTH_PRIVATE_KEY) ?: return null

        return runCatching {
            KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(encoded, Base64.NO_WRAP)))
        }.getOrNull()
    }

    private fun clearPendingAuth() {
        pendingKeyPair = null
        pendingNonce = ""
        store.clear(
            SecureStore.KEY_AUTH_PRIVATE_KEY,
            SecureStore.KEY_AUTH_NONCE,
            SecureStore.KEY_AUTH_STARTED_AT,
        )
    }

    private fun generateKeyPair(): KeyPair? = runCatching {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }.getOrNull()

    private fun randomNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private companion object {
        /** Long enough for a slow sign-in, short enough that an abandoned one expires. */
        const val PENDING_AUTH_TTL_MILLIS = 10 * 60 * 1000L
    }

    /** Java already exports RSA public keys as X.509 SubjectPublicKeyInfo. */
    private fun publicKeyPem(pair: KeyPair): String {
        val encoded = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
        val wrapped = encoded.chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----\n"
    }
}


/**
 * The site's custom fields, in the shape `create` reads them back out of:
 * `params.dig(:user_fields, field.id.to_s)`. A required one left out fails the
 * whole registration, and the value has to match the field's declared option
 * exactly — the server looks it up rather than accepting what it is given.
 */
internal fun userFieldItems(values: Map<Int, String>): List<Pair<String, String>> =
    values.filterValues { it.isNotBlank() }
        .map { (id, value) -> "user_fields[$id]" to value }

/**
 * The site's invite code, when it has one.
 *
 * Sent as a field rather than held back for a site that needs it: Discourse
 * only compares it when `invite_code` is set, and `params.require` counts a
 * blank one as missing — so an empty code has to be absent, not empty.
 */
internal fun inviteCodeField(code: String = DiscourseConfig.INVITE_CODE): List<Pair<String, String>> =
    code.takeIf { it.isNotBlank() }
        ?.let { listOf("invite_code" to it) }
        ?: emptyList()

/**
 * The two fields that tell Discourse a signup came from a form.
 *
 * Without them `respond_to_suspicious_request` answers `{"success": true}` and
 * the message about an activation email — and creates nothing, and sends
 * nothing. A signup that silently does not happen is the worst possible
 * failure, and it looks identical to the real thing from here.
 *
 * `password_confirmation` carries the honeypot value rather than the password:
 * the field is bait, and a script that fills it in the obvious way fails. The
 * challenge goes back reversed, which is simply the shape the server checks
 * (`params[:challenge] != challenge_value.try(:reverse)`).
 */
internal fun honeypotFields(honeypot: HoneypotResponse?): List<Pair<String, String>>? {
    val value = honeypot?.value?.takeIf { it.isNotEmpty() } ?: return null
    val challenge = honeypot.challenge?.takeIf { it.isNotEmpty() } ?: return null
    return listOf(
        "password_confirmation" to value,
        "challenge" to challenge.reversed(),
    )
}
