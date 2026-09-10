package com.nodeloc.app.core.util

import com.nodeloc.app.R
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.Post
import com.nodeloc.app.core.model.VoteDirection
import com.nodeloc.app.core.network.DiscourseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * discourse-vote from a list row, shared by every screen that holds a
 * `List<Post>`.
 *
 * A row stands for the topic, but a ballot is always cast on a post — the
 * topic's first one, which the server sends along as `op_post_id`. A row
 * without it is not votable and nothing here happens.
 *
 * The optimistic write and its rollback both go through the same list rewrite,
 * because a feed can have paged, refreshed or been re-sorted between the tap
 * and the answer: the row is found by id each time rather than by position.
 */
object TopicVoting {

    /**
     * Applies the tap, fires the request, and puts the row back if it fails.
     *
     * [current] is read at each step rather than captured, so a list that
     * changed underneath — a pull-to-refresh landing mid-request — is not
     * overwritten wholesale by a stale copy.
     */
    fun cast(
        scope: CoroutineScope,
        client: DiscourseClient,
        topicId: Int,
        tapped: VoteDirection,
        /** Which face to vote with; without one the direction's default is cast. */
        face: String? = null,
        current: () -> List<Post>,
        publish: (List<Post>) -> Unit,
    ) {
        if (!client.auth.isAuthenticated) return
        val before = current().firstOrNull { it.id == topicId } ?: return
        val postId = before.opPostId ?: return
        val score = before.voteScore ?: return
        // Picking a face always casts that direction; only the bare arrow
        // toggles a vote back off.
        val next = if (face != null) tapped else before.voteDirection.after(tapped)
        if (next == VoteDirection.Down && !before.canVoteDown) {
            ToastCenter.show(R.string.vote_down_not_allowed)
            return
        }

        publish(current().replacing(topicId) { it.copy(voteDirection = next, voteScore = score + before.voteDirection.stepTo(next)) })

        scope.launch {
            runCatchingCancellable { client.castVote(postId, next, face) }
                .onFailure {
                    publish(
                        current().replacing(topicId) {
                            it.copy(voteDirection = before.voteDirection, voteScore = score)
                        },
                    )
                    ToastCenter.showError(it)
                }
        }
    }

    private fun List<Post>.replacing(id: Int, change: (Post) -> Post): List<Post> =
        map { if (it.id == id) change(it) else it }
}
