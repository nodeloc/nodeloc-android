package com.nodeloc.app.core.util

/**
 * Whether an address is worth sending to the server.
 *
 * Deliberately loose. The only authority on an address is the mail that
 * arrives at it, and every rule tighter than this one has a real address it
 * wrongly rejects — plus signs, apostrophes, single-letter domains, new TLDs.
 * This catches the mistakes people actually make in a form: no `@`, nothing
 * before or after it, a domain with no dot, a stray space from a paste.
 */
object EmailFormat {

    private val PATTERN = Regex("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$")

    fun isValid(value: String): Boolean = PATTERN.matches(value.trim())
}
