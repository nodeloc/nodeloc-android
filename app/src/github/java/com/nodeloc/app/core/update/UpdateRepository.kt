package com.nodeloc.app.core.update

import android.content.Context
import androidx.core.content.FileProvider
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Update checking, against a manifest kept in a topic on the site.
 *
 * There is no plugin behind this and no endpoint of its own: a release is a
 * post edit. The cost is that nothing here can be trusted more than that post,
 * which is why the download is verified against the hash the post declares and
 * why Android's own signature check remains the thing that actually protects
 * the install — an APK signed with a different key cannot replace this app,
 * whatever the manifest says.
 */
class UpdateRepository(
    private val context: Context,
    private val client: DiscourseClient,
) {

    /**
     * Null when there is nothing newer, or when neither source can be read.
     *
     * The post is consulted only when the plugin could not be *reached*. It
     * used to be consulted whenever the plugin returned nothing, which is also
     * what "you are up to date" looks like from here — so every check still
     * fetched the post, and a manifest left behind in it could still speak
     * over the thing that replaced it.
     */
    suspend fun check(): AvailableUpdate? =
        runCatchingCancellable { fromEndpoint() }.getOrElse { fromTopic() }

    /**
     * The plugin, which is where releases live now.
     *
     * Answers 200 with a null release for "you are up to date", so an empty
     * body and a failed call are different things: this returns null for the
     * first and throws for the second.
     */
    private suspend fun fromEndpoint(): AvailableUpdate? {
        val response = client.latestRelease(
            versionCode = BuildConfig.VERSION_CODE,
            // Keeps a signed-out install in one rollout bucket instead of a
            // different answer on every check.
            clientIdForRollout = DiscourseConfig.clientId(context),
        )

        val release = response.release ?: return null
        if (release.versionCode <= BuildConfig.VERSION_CODE) return null
        return AvailableUpdate(
            UpdateManifest(
                versionCode = release.versionCode,
                versionName = release.versionName,
                url = release.url,
                sha256 = release.sha256,
                notes = release.notes,
            ),
            mandatory = release.mandatory,
        )
    }

    /**
     * The post, which is where they lived before.
     *
     * Kept because the plugin is not on the site yet and because a build older
     * than it exists in people's hands: dropping this would strand anyone whose
     * next update is the one that teaches them the new address.
     */
    private suspend fun fromTopic(): AvailableUpdate? {
        if (DiscourseConfig.UPDATE_TOPIC_ID <= 0) return null
        val raw = runCatchingCancellable {
            client.getRaw("raw/${DiscourseConfig.UPDATE_TOPIC_ID}/1")
        }.getOrNull()
        return updateFor(parseUpdateManifest(raw), BuildConfig.VERSION_CODE)
    }

    /**
     * The APK on disk, ready to hand to the installer.
     *
     * Downloaded straight through OkHttp rather than through `DiscourseClient`:
     * the manifest may point anywhere, and the site's cookies have no business
     * travelling to a host the post happened to name.
     */
    suspend fun download(update: AvailableUpdate): File? = withContext(Dispatchers.IO) {
        val manifest = update.manifest
        val target = File(cacheDir(), "nodeloc-${manifest.versionCode}.apk")
        // A finished download of this exact version is reused; a half-finished
        // one is not, which the hash below is what distinguishes.
        if (target.exists() && matchesHash(target, manifest.sha256)) return@withContext target

        val request = Request.Builder().url(manifest.url).get().build()
        val ok = runCatchingCancellable {
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                target.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                true
            }
        }.getOrDefault(false)

        if (!ok || !matchesHash(target, manifest.sha256)) {
            target.delete()
            return@withContext null
        }
        target
    }

    fun installIntentUri(file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun cacheDir(): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /** True when no hash was declared: the post is the only authority there is. */
    private fun matchesHash(file: File, expected: String?): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val want = expected?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == want
    }
}
