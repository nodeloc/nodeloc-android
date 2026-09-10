package com.nodeloc.app.core.store

import com.nodeloc.app.core.network.DiscourseClient
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Who this account has blocked, held where every list can see it.
 *
 * The lists have to do this themselves, because the server does not. Ignoring
 * somebody in Discourse hides their *replies* inside a topic — `TopicView`
 * filters on `user_id IN (…) AND posts.post_number != 1`, which deliberately
 * keeps the opening post — and `TopicQuery` has no ignore filter at all, so a
 * blocked person's topics stay in `/latest`, `/best` and every node list
 * exactly as they were.
 *
 * So the names live here and the lists drop the rows. Held in memory only: it
 * is a handful of usernames, the server is the record, and a stale copy that
 * outlived a sign-out would hide people from somebody else.
 */
class BlockedUsers(private val client: DiscourseClient) {

    private val _usernames = MutableStateFlow<Set<String>>(emptySet())
    val usernames: StateFlow<Set<String>> = _usernames.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private var loadedFor: String? = null

    /**
     * Read the list off your own profile, once per signed-in account.
     *
     * `ignored_usernames` is on the user serializer and filled from your own
     * rows, so there is nothing else to ask and nobody else to ask it of.
     */
    fun load(username: String?) {
        if (username == null) {
            loadedFor = null
            _usernames.value = emptySet()
            return
        }
        if (loadedFor == username) return
        loadedFor = username
        scope.launch {
            runCatchingCancellable { client.user(username) }
                .getOrNull()
                ?.user
                ?.ignoredUsernames
                ?.let { _usernames.value = it.toSet() }
        }
    }

    /** Said before the server has answered, so the rows go on the tap. */
    fun add(username: String) {
        _usernames.value = _usernames.value + username
    }

    fun remove(username: String) {
        _usernames.value = _usernames.value - username
    }

    fun contains(username: String?): Boolean = username != null && username in _usernames.value
}
