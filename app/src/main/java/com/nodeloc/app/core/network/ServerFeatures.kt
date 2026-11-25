package com.nodeloc.app.core.network

/**
 * What the 2.0 server can do that the screens were built to offer.
 *
 * The screens predate it, and some of what they offer has no counterpart in
 * the native API. Rather than redesign them, the entry points read these and
 * stay out of sight; the day the server grows one of these, flipping it here
 * brings the existing UI back.
 */
object ServerFeatures {
    /** Emoji reactions on chat messages. */
    const val CHAT_REACTIONS = false

    /** Editing a sent chat message. */
    const val CHAT_EDIT = false

    /** Reporting a chat message. */
    const val CHAT_REPORT = false

    /** Threads inside a chat channel. */
    const val CHAT_THREADS = false

    /** Who reacted to a post, face by face. */
    const val POST_REACTION_USERS = false

    /** Staff pinning replies to the top of a thread. */
    const val REPLY_PINNING = false

    /** The device "tail" shown beside a post. */
    const val POST_SOURCE = false

    /** "Recently visited" nodes. */
    const val RECENT_NODES = false

    /** Topic-style private messages; conversations live in chat now. */
    const val PRIVATE_MESSAGE_TOPICS = false

    /** Archiving and unlisting topics; closing and pinning remain. */
    const val TOPIC_ARCHIVE = false
}
