package com.nodeloc.app.core.network

import com.nodeloc.app.core.model.HoneypotResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reversal is the whole trick and it cannot be checked against the live
 * site: nodeloc is invite-only, and that alone makes every signup look
 * suspicious, so a correct honeypot and a broken one produce the same reply.
 * These pin it against what the server actually compares —
 * `params[:challenge] != challenge_value.try(:reverse)`.
 */
class HoneypotFieldsTest {

    @Test
    fun `echoes the value and the reversed challenge`() {
        val fields = honeypotFields(HoneypotResponse(value = "abc123", challenge = "0f1e2d"))
        assertEquals(
            listOf("password_confirmation" to "abc123", "challenge" to "d2e1f0"),
            fields,
        )
    }

    /** The bait field carries the honeypot, never the password. */
    @Test
    fun `password confirmation is the honeypot value`() {
        val fields = honeypotFields(HoneypotResponse(value = "bait", challenge = "xy"))!!
        assertEquals("bait", fields.first { it.first == "password_confirmation" }.second)
    }

    /**
     * Null rather than an empty list: sending the signup without these fields
     * would be answered with a success that creates nothing, so the caller has
     * to be able to tell "no honeypot" from "here are the fields".
     */
    @Test
    fun `reports unusable answers rather than sending none`() {
        assertNull(honeypotFields(null))
        assertNull(honeypotFields(HoneypotResponse()))
        assertNull(honeypotFields(HoneypotResponse(value = "abc", challenge = null)))
        assertNull(honeypotFields(HoneypotResponse(value = "", challenge = "abc")))
    }
}
