package com.nodeloc.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeFieldTest {

    @Test
    fun `sends the code when the site has one`() {
        assertEquals(listOf("invite_code" to "let-me-in"), inviteCodeField("let-me-in"))
    }

    /**
     * Absent rather than empty. Discourse does `params.require(:invite_code)`
     * when a code is configured, and `require` rejects a blank value the same
     * way it rejects a missing one — so an empty field would fail the signup
     * outright on exactly the sites that do not need it.
     */
    @Test
    fun `omits the field entirely when there is no code`() {
        assertTrue(inviteCodeField("").isEmpty())
        assertTrue(inviteCodeField("   ").isEmpty())
    }
}
