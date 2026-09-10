package com.nodeloc.app.feature.node

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The create button stays disabled until the slug is both present and free, so
 * a slug rule that quietly produces nothing makes the whole screen a dead end.
 */
class SlugifyNodeNameTest {

    @Test
    fun `words become a hyphenated slug`() {
        assertEquals("cloud-hosting", slugifyNodeName("Cloud Hosting"))
        assertEquals("cloud-hosting", slugifyNodeName("  Cloud   Hosting  "))
        assertEquals("cloud-hosting", slugifyNodeName("cloud_hosting"))
    }

    /** ASCII-only stripping left this empty, and the form could never be submitted. */
    @Test
    fun `a Chinese name still produces a slug`() {
        assertEquals("云服务器", slugifyNodeName("云服务器"))
        assertEquals("云-服务器", slugifyNodeName("云 服务器"))
    }

    @Test
    fun `punctuation is dropped rather than turned into gaps`() {
        assertEquals("vps", slugifyNodeName("V.P.S!"))
        assertEquals("a-b", slugifyNodeName("a --- b"))
        assertEquals("node2", slugifyNodeName("Node#2"))
    }

    @Test
    fun `a name with nothing sluggable yields nothing`() {
        assertEquals("", slugifyNodeName("!!!"))
        assertEquals("", slugifyNodeName("   "))
    }

    /**
     * The bug this file exists for: the follow check compared against the name
     * being replaced, so every prefix of a real name has to keep producing the
     * slug that prefix would have produced.
     */
    @Test
    fun `each prefix of a name slugs to the prefix of its slug`() {
        val name = "Cloud"
        assertEquals("c", slugifyNodeName(name.take(1)))
        assertEquals("cl", slugifyNodeName(name.take(2)))
        assertEquals("cloud", slugifyNodeName(name))
    }
}
