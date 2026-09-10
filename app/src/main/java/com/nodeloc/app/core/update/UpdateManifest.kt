package com.nodeloc.app.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a release announces about itself.
 *
 * Carried in the first post of a topic on the site rather than in an endpoint
 * of its own: `GET /raw/{topic}/1` already returns the post's markdown as
 * `text/plain` to anonymous readers, so a release needs no plugin, no upload
 * permission and no deploy — only an edit.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    /**
     * Where the APK is. Deliberately just a string: hosting it as a forum
     * upload or on a static host is a decision that belongs to whoever writes
     * the post, and neither one changes anything here.
     */
    val url: String = "",
    /** Lower-case hex. Optional, and checked before the installer is offered. */
    val sha256: String? = null,
    val notes: String? = null,
    /**
     * Below this, the update is not a suggestion. Left at zero the prompt is
     * always dismissible, which is the right default.
     */
    val minVersionCode: Int = 0,
)

/** A release worth telling the user about, against the build they are running. */
data class AvailableUpdate(
    val manifest: UpdateManifest,
    val mandatory: Boolean,
)

/** Unknown keys pass, so a post can gain a field older builds never knew. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * The manifest out of a post's markdown.
 *
 * A fenced block first, because a post that renders as a readable code block
 * is one a human can keep up to date without a tool; the bare document is
 * accepted too so the post can be nothing but the manifest.
 */
fun parseUpdateManifest(raw: String?): UpdateManifest? {
    val body = raw?.takeIf { it.isNotBlank() } ?: return null
    val fenced = FENCE.find(body)?.groupValues?.get(1)
    val candidate = (fenced ?: body).trim()
    if (!candidate.startsWith("{")) return null
    return runCatching { json.decodeFromString<UpdateManifest>(candidate) }.getOrNull()
        ?.takeIf { it.versionCode > 0 && isDownloadable(it.url) }
}

/**
 * A release has to point somewhere the app can actually go.
 *
 * The whitespace check is not pedantry: a long link pasted into the editor
 * comes back wrapped *inside* its own quotes, and neither JSON parser objects
 * to the newline that leaves in the middle of the address — so without this,
 * a typo in a post becomes a download the user watches fail. Joining the
 * halves would be guessing; refusing is not.
 *
 * https only, because the APK is the one thing here worth intercepting.
 */
private fun isDownloadable(url: String): Boolean =
    url.startsWith("https://") && url.none { it.isWhitespace() }

/**
 * Both braces escaped, including the closing one. Android's regex engine is
 * ICU, not `java.util.regex`, and ICU rejects a bare `}` as a syntax error —
 * so this pattern compiled in every unit test and threw on the first device
 * that ran it, taking the app down at launch.
 */
private val FENCE = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)

/** Null when the running build is already current — or newer, on a dev device. */
fun updateFor(manifest: UpdateManifest?, currentVersionCode: Int): AvailableUpdate? {
    val release = manifest ?: return null
    if (release.versionCode <= currentVersionCode) return null
    return AvailableUpdate(release, mandatory = currentVersionCode < release.minVersionCode)
}
