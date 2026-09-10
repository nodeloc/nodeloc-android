package com.nodeloc.app.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailFormatTest {

    @Test
    fun `accepts ordinary addresses`() {
        assertTrue(EmailFormat.isValid("someone@example.com"))
        assertTrue(EmailFormat.isValid("someone@mail.example.co.uk"))
    }

    /** All of these are real addresses a stricter pattern would turn away. */
    @Test
    fun `accepts the awkward but valid`() {
        assertTrue(EmailFormat.isValid("first.last+tag@example.com"))
        assertTrue(EmailFormat.isValid("o'brien@example.com"))
        assertTrue(EmailFormat.isValid("a@b.co"))
        assertTrue(EmailFormat.isValid("someone@example.photography"))
    }

    @Test
    fun `rejects what the form is actually for`() {
        assertFalse(EmailFormat.isValid(""))
        assertFalse(EmailFormat.isValid("@"))
        assertFalse(EmailFormat.isValid("someone"))
        assertFalse(EmailFormat.isValid("someone@"))
        assertFalse(EmailFormat.isValid("@example.com"))
        // A domain with no dot: the mistake behind "someone@gmail".
        assertFalse(EmailFormat.isValid("someone@example"))
        assertFalse(EmailFormat.isValid("someone@example."))
        assertFalse(EmailFormat.isValid("some one@example.com"))
        assertFalse(EmailFormat.isValid("someone@@example.com"))
    }

    /** Pasted addresses arrive wrapped in whitespace more often than not. */
    @Test
    fun `ignores surrounding whitespace`() {
        assertTrue(EmailFormat.isValid("  someone@example.com\n"))
    }
}
