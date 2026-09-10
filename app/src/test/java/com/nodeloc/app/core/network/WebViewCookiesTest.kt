package com.nodeloc.app.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebView hands its cookies back as a bare request header, so this merge is
 * the only thing standing between "the site rotated `_t` in the WebView" and
 * "every native request now answers not_logged_in".
 */
class WebViewCookiesTest {

    private val url = "https://www.nodeloc.com/".toHttpUrl()

    private fun session(value: String) = Cookie.Builder()
        .name("_t")
        .value(value)
        .domain("www.nodeloc.com")
        .path("/")
        .expiresAt(System.currentTimeMillis() + 86_400_000)
        .secure()
        .httpOnly()
        .build()

    @Test
    fun `a rotated value replaces the old one and keeps every attribute`() {
        val merged = WebViewCookies.merge(listOf(session("old")), "_t=rotated; _forum_session=x", url)

        assertEquals(1, merged.size)
        val cookie = merged.single()
        assertEquals("_t", cookie.name)
        assertEquals("rotated", cookie.value)
        // The header carries none of these; they can only come from the jar.
        assertEquals("www.nodeloc.com", cookie.domain)
        assertEquals("/", cookie.path)
        assertTrue(cookie.secure)
        assertTrue(cookie.httpOnly)
    }

    @Test
    fun `an unchanged value reports nothing to write`() {
        assertTrue(WebViewCookies.merge(listOf(session("same")), "_t=same", url).isEmpty())
    }

    /**
     * The security rule: the WebView can refresh a session the jar already
     * holds, never mint one it does not.
     */
    @Test
    fun `a name the jar does not hold is ignored`() {
        val merged = WebViewCookies.merge(listOf(session("old")), "_forum_session=new; other=x", url)
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `malformed segments are skipped rather than poisoning the batch`() {
        val merged = WebViewCookies.merge(listOf(session("old")), "novalue; =orphan; _t=rotated; ", url)
        assertEquals(listOf("rotated"), merged.map { it.value })
    }

    @Test
    fun `an empty or absent header is a no-op`() {
        assertTrue(WebViewCookies.merge(listOf(session("old")), null, url).isEmpty())
        assertTrue(WebViewCookies.merge(listOf(session("old")), "   ", url).isEmpty())
    }

    @Test
    fun `a cookie scoped to another host is left alone`() {
        val elsewhere = Cookie.Builder()
            .name("_t").value("old").domain("example.com").path("/").build()

        assertTrue(WebViewCookies.merge(listOf(elsewhere), "_t=rotated", url).isEmpty())
    }
}
