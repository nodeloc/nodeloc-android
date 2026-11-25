package com.nodeloc.app.feature.node

import com.nodeloc.app.core.network.NodelocApi
import kotlinx.serialization.Serializable

/** One rule as `GET /api/v1/nodes/{slug}` serves it; old nodes stored bare titles, which the server folds into this. */
@Serializable
data class NodeRule(val title: String = "", val description: String? = null)

/** The node object of `GET /api/v1/nodes/{slug}`, the fields the 关于 tab reads. */
@Serializable
data class NodeAboutNode(
    val name: String? = null,
    val description: String? = null,
    /** `public` or `members`. */
    val visibility: String? = null,
    val memberCount: Int? = null,
    val topicCount: Int? = null,
    val postCount: Int? = null,
    val createdAt: String? = null,
)

/**
 * The parts of the node detail the 规则 and 关于 tabs show. Fetched separately
 * from the directory entry the header is built from: only this endpoint
 * carries the rules, and it serves the name and description in the reader's
 * language.
 */
@Serializable
data class NodeAbout(
    val node: NodeAboutNode? = null,
    val rules: List<NodeRule> = emptyList(),
) {
    val description: String? get() = node?.description?.takeIf { it.isNotBlank() }
    val listedRules: List<NodeRule> get() = rules.filter { it.title.isNotBlank() }
}

suspend fun NodelocApi.nodeAbout(slug: String): NodeAbout = get("api/v1/nodes/$slug")

/**
 * Lifts the reader's block on a node. The bell's "muted" *is* this block on
 * the 2.0 backend (setting it calls the same `SetNodeBlocked`), so lifting it
 * leaves the member's previous level in place rather than resetting it.
 */
suspend fun NodelocApi.unblockNode(slug: String) {
    sendRaw("DELETE", "api/v1/nodes/$slug/block")
}
