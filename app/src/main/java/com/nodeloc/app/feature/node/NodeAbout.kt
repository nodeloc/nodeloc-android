package com.nodeloc.app.feature.node

import com.nodeloc.app.core.network.NodelocApi
import kotlinx.serialization.Serializable

/** One rule as `GET /api/v1/nodes/{slug}` serves it; old nodes stored bare titles, which the server folds into this. */
@Serializable
data class NodeRule(val title: String = "", val description: String? = null)

@Serializable
data class NodeAboutNode(val description: String? = null)

/**
 * The parts of the node detail the 关于 tab shows. Fetched separately from the
 * directory entry the header is built from: only this endpoint carries the
 * rules, and it serves the name and description in the reader's language.
 */
@Serializable
data class NodeAbout(
    val node: NodeAboutNode? = null,
    val rules: List<NodeRule> = emptyList(),
) {
    val description: String? get() = node?.description?.takeIf { it.isNotBlank() }
    val isEmpty: Boolean get() = description == null && rules.none { it.title.isNotBlank() }
}

suspend fun NodelocApi.nodeAbout(slug: String): NodeAbout = get("api/v1/nodes/$slug")
