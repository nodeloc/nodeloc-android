package com.nodeloc.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ballot is a state, not a toggle.
 *
 * Every request says which direction to end up in, so a double tap and a
 * replayed request both land on the same answer — which is the only reason the
 * optimistic control can be trusted on a phone with a bad connection.
 */
class VoteDirectionTest {

    @Test
    fun `tapping the arrow you are already on takes the vote back`() {
        assertEquals(VoteDirection.None, VoteDirection.Up.after(VoteDirection.Up))
        assertEquals(VoteDirection.None, VoteDirection.Down.after(VoteDirection.Down))
    }

    @Test
    fun `tapping the other arrow crosses straight over`() {
        assertEquals(VoteDirection.Down, VoteDirection.Up.after(VoteDirection.Down))
        assertEquals(VoteDirection.Up, VoteDirection.Down.after(VoteDirection.Up))
    }

    @Test
    fun `an unvoted post takes the arrow that was tapped`() {
        assertEquals(VoteDirection.Up, VoteDirection.None.after(VoteDirection.Up))
        assertEquals(VoteDirection.Down, VoteDirection.None.after(VoteDirection.Down))
    }

    /** An absent or unknown direction is "not voted", never a guess at one. */
    @Test
    fun `an unrecognised direction reads as none`() {
        assertEquals(VoteDirection.None, VoteDirection.from(null))
        assertEquals(VoteDirection.None, VoteDirection.from("sideways"))
        assertEquals(VoteDirection.Up, VoteDirection.from("up"))
        assertEquals(VoteDirection.Down, VoteDirection.from("down"))
    }

    /**
     * Crossing from up to down is a swing of two: the like goes and a downvote
     * arrives, and the score is the difference between the two tallies.
     */
    @Test
    fun `crossing over moves the score by two`() {
        assertEquals(-2, VoteDirection.Up.stepTo(VoteDirection.Down))
        assertEquals(2, VoteDirection.Down.stepTo(VoteDirection.Up))
        assertEquals(-1, VoteDirection.Up.stepTo(VoteDirection.None))
        assertEquals(1, VoteDirection.None.stepTo(VoteDirection.Up))
        assertEquals(0, VoteDirection.Up.stepTo(VoteDirection.Up))
    }

    /**
     * The threshold is a site setting the app is *given*, never one it assumes.
     * With none — voting off, or a server that predates the field — nothing
     * folds, because folding at a guessed line is worse than not folding.
     */
    @Test
    fun `no threshold folds nothing`() {
        assertFalse(isVoteCollapsed(score = -900, threshold = null))
        assertFalse(isVoteCollapsed(score = null, threshold = -5))
    }

    /** `<=`, matching the plugin: a post *at* the threshold is folded. */
    @Test
    fun `the threshold itself is folded`() {
        assertTrue(isVoteCollapsed(score = -5, threshold = -5))
        assertTrue(isVoteCollapsed(score = -6, threshold = -5))
        assertFalse(isVoteCollapsed(score = -4, threshold = -5))
        assertFalse(isVoteCollapsed(score = 12, threshold = -5))
    }
}
