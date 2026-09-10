package com.nodeloc.app.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest is a forum post, which means a person edits it by hand and
 * every one of these shapes is something they will eventually type.
 */
class UpdateManifestTest {

    private val fenced = """
        下一版发布说明在这里。

        ```json
        {
          "versionCode": 2,
          "versionName": "1.0.1",
          "url": "https://example.com/nodeloc-2.apk",
          "sha256": "ABCDEF",
          "notes": "修了一堆东西"
        }
        ```

        有问题回帖。
    """.trimIndent()

    @Test
    fun `reads the manifest out of a post written around it`() {
        val manifest = parseUpdateManifest(fenced)!!
        assertEquals(2, manifest.versionCode)
        assertEquals("1.0.1", manifest.versionName)
        assertEquals("https://example.com/nodeloc-2.apk", manifest.url)
    }

    @Test
    fun `accepts a post that is nothing but the manifest`() {
        val bare = """{"versionCode":3,"versionName":"1.0.2","url":"https://e.com/a.apk"}"""
        assertEquals(3, parseUpdateManifest(bare)?.versionCode)
    }

    /** An unfenced post, a half-written one, an empty one: all just "no news". */
    @Test
    fun `refuses anything it cannot read as a release`() {
        assertNull(parseUpdateManifest(null))
        assertNull(parseUpdateManifest(""))
        assertNull(parseUpdateManifest("还没发布"))
        assertNull(parseUpdateManifest("```json\n{ not json\n```"))
        // A manifest with no download is not a release anyone can install.
        assertNull(parseUpdateManifest("""{"versionCode":2,"versionName":"1.0.1"}"""))
        // Nor is one the APK could be swapped in transit on.
        assertNull(parseUpdateManifest("""{"versionCode":2,"url":"http://e.com/a.apk"}"""))
        // Nor is one with no version to compare against.
        assertNull(parseUpdateManifest("""{"url":"https://e.com/a.apk"}"""))
    }

    /** Unknown keys are how the post survives a future field being added. */
    @Test
    fun `ignores keys it does not know`() {
        val extra = """{"versionCode":2,"versionName":"1.0.1","url":"https://e.com/a.apk","channel":"beta"}"""
        assertNotNull(parseUpdateManifest(extra))
    }

    /**
     * The shape the real post came back in: the fence indented, every line
     * padded with trailing spaces, and a key parted from its value by a line
     * break. All of it is whitespace outside a string, and none of it matters.
     */
    @Test
    fun `survives how the editor actually leaves a post`() {
        val messy = "```json                        \n" +
            "  {                                 \n" +
            "    \"versionCode\": 4,               \n" +
            "    \"versionName\": \"1.0.3\",         \n" +
            "    \"url\":                          \n" +
            "  \"https://example.com/a.apk\",     \n" +
            "    \"notes\": \"首个发布版本\"          \n" +
            "  }                                 \n" +
            "  ```"
        val manifest = parseUpdateManifest(messy)!!
        assertEquals(4, manifest.versionCode)
        assertEquals("https://example.com/a.apk", manifest.url)
    }

    /**
     * A URL wrapped *inside* its quotes, which is what a person pasting a long
     * link into the editor produces. It is a raw newline in a JSON string —
     * unparseable, and joining it would be guessing — so it reads as no news
     * rather than as a release pointing at a truncated address.
     */
    @Test
    fun `refuses a manifest whose url was broken across lines`() {
        val wrapped = "```json\n{\n  \"versionCode\": 4,\n  \"versionName\": \"1.0.3\",\n" +
            "  \"url\": \"https://example.com/app\n-release.apk\"\n}\n```"
        assertNull(parseUpdateManifest(wrapped))
    }

    @Test
    fun `offers only a build newer than the one running`() {
        val manifest = parseUpdateManifest(fenced)
        assertNull(updateFor(manifest, currentVersionCode = 2))
        // A dev build ahead of the post is not asked to downgrade.
        assertNull(updateFor(manifest, currentVersionCode = 9))
        assertNotNull(updateFor(manifest, currentVersionCode = 1))
        assertNull(updateFor(null, currentVersionCode = 1))
    }

    @Test
    fun `only a declared floor makes an update mandatory`() {
        val optional = UpdateManifest(versionCode = 5, url = "https://e.com/a.apk")
        assertFalse(updateFor(optional, 1)!!.mandatory)

        val forced = optional.copy(minVersionCode = 4)
        assertTrue(updateFor(forced, 1)!!.mandatory)
        // At or above the floor it goes back to being a suggestion.
        assertFalse(updateFor(forced, 4)!!.mandatory)
    }
}
