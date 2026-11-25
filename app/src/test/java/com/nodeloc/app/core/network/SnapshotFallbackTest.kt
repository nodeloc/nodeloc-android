package com.nodeloc.app.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** Which failures may put a saved copy on screen (issue #1): the network's, never the request's. */
class SnapshotFallbackTest {

    @Test
    fun `a dead connection or a failing server falls back`() {
        assertTrue(DiscourseError.Transport(UnknownHostException()).servesSnapshot())
        assertTrue(DiscourseError.BadResponse(502).servesSnapshot())
        assertTrue(java.io.IOException("reset").servesSnapshot())
    }

    @Test
    fun `a topic that is gone or forbidden does not`() {
        assertFalse(DiscourseError.BadResponse(404).servesSnapshot())
        assertFalse(DiscourseError.BadResponse(403).servesSnapshot())
        assertFalse(IllegalStateException().servesSnapshot())
    }
}
