package com.nodeloc.app.core.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed string storage for the few secrets the app holds: the User
 * API Key, the website session cookies, the CSRF token and the username.
 *
 * A hand-rolled AES/GCM wrapper rather than `security-crypto`, which is
 * unmaintained: the key never leaves the Keystore, and the payloads here are
 * small enough that a 40-line helper is the whole requirement.
 */
class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    fun set(key: String, value: String?) {
        if (value == null) {
            prefs.edit { remove(key) }
        } else {
            prefs.edit { putString(key, encrypt(value)) }
        }
    }

    /** The cookie jar's view of this store. */
    val cookieStorage: CookieStorage = object : CookieStorage {
        override fun read(): String? = get(KEY_COOKIES)
        override fun write(value: String?) = set(KEY_COOKIES, value)
    }

    fun clear(vararg keys: String) {
        prefs.edit { keys.forEach { remove(it) } }
    }

    /**
     * Everything, including keys added later. Sign-out uses this rather than
     * naming keys, so a secret introduced next year cannot outlive the account
     * it belonged to just because someone forgot to add it to a list.
     */
    fun clearAll() {
        prefs.edit { clear() }
    }

    /**
     * Cached and guarded because generation is not idempotent: two threads
     * reaching first use together would each `generateKey()` under the same
     * alias, and whichever lost would have already encrypted with a key the
     * Keystore no longer holds — its ciphertext is then undecryptable forever,
     * which surfaces as a silent sign-out.
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun secretKey(): SecretKey = cachedKey ?: synchronized(this) {
        cachedKey ?: loadOrCreateKey().also { cachedKey = it }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray())
        val payload = cipher.iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val payload = Base64.decode(stored, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val body = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body))
    }

    companion object {
        private const val PREFS = "nodeloc.secure"
        private const val ALIAS = "nodeloc.secret"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12

        /**
         * The native API's bearer token. A new key rather than the 1.x
         * `user_api_key`: whatever an older build left there is a Discourse
         * credential this server has never issued.
         */
        const val KEY_TOKEN = "api_token"
        const val KEY_COOKIES = "session_cookies"
        const val KEY_USERNAME = "username"

        /** "Don't ask for a code on this device for 30 days", sent back as `X-Nodeloc-Trust`. */
        const val KEY_TRUST = "trust_token"

        /**
         * A browser sign-in's PKCE verifier. The Custom Tab belongs to another
         * app and this process can die behind it, so the verifier has to outlive
         * it; cleared the moment the redirect is handled, either way.
         */
        const val KEY_PKCE_VERIFIER = "pkce_verifier"
        const val KEY_PKCE_STARTED_AT = "pkce_started_at"
    }
}
