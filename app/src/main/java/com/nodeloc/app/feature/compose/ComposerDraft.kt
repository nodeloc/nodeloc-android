package com.nodeloc.app.feature.compose

import com.nodeloc.app.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The unposted topic, on disk.
 *
 * Deliberately not `raw` markdown: a picture in a draft is `upload://…`, which
 * the server resolves but nothing can fetch, so a draft round-tripped through
 * markdown would come back with every image a grey box. Blocks keep the
 * fetchable address beside the one that goes in the post.
 *
 * Pending uploads are dropped on the way in. They have no server address yet,
 * and the local preview they are showing is a cache file that will not be there
 * next time.
 */
@Serializable
data class ComposerDraft(
    val title: String = "",
    @SerialName("category_id") val categoryId: Int? = null,
    val blocks: List<DraftBlock> = emptyList(),
) {
    val isEmpty: Boolean
        get() = title.isBlank() && blocks.all { it.type == TEXT && it.text.isBlank() }

    fun toBlocks(): List<ComposerBlock> = blocks.mapNotNull { it.toBlock() }
        .ifEmpty { listOf(ComposerBlock.Text("")) }

    companion object {
        const val TEXT = "text"
        const val MEDIA = "media"
        const val MARKUP = "markup"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun of(title: String, categoryId: Int?, blocks: List<ComposerBlock>): ComposerDraft =
            ComposerDraft(
                title = title,
                categoryId = categoryId,
                blocks = blocks.mapNotNull(DraftBlock::of),
            )

        fun encode(draft: ComposerDraft): String = json.encodeToString(draft)

        /** A draft written by an older build is discarded, never crashed on. */
        fun decode(raw: String?): ComposerDraft? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString<ComposerDraft>(raw) }.getOrNull()
        }
    }
}

@Serializable
data class DraftBlock(
    val type: String,
    val text: String = "",
    val url: String = "",
    @SerialName("display_url") val displayUrl: String = "",
    val name: String = "",
    val kind: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
) {
    fun toBlock(): ComposerBlock? = when (type) {
        ComposerDraft.TEXT -> ComposerBlock.Text(text)
        ComposerDraft.MEDIA -> ComposerBlock.Media(
            url = url,
            displayUrl = displayUrl,
            name = name,
            kind = runCatching { ComposerBlock.MediaKind.valueOf(kind) }
                .getOrDefault(ComposerBlock.MediaKind.Image),
            posterPath = posterPath,
        ).takeIf { url.isNotBlank() }
        // The only markup the composer builds is a poll, so the label needs no
        // storing — and a stored resource id would not survive a rebuild.
        ComposerDraft.MARKUP -> ComposerBlock.Markup(text, R.string.poll_title)
            .takeIf { text.isNotBlank() }
        else -> null
    }

    companion object {
        fun of(block: ComposerBlock): DraftBlock? = when (block) {
            is ComposerBlock.Text -> DraftBlock(ComposerDraft.TEXT, text = block.value)
            is ComposerBlock.Media -> if (block.isPending) null else DraftBlock(
                type = ComposerDraft.MEDIA,
                url = block.url,
                displayUrl = block.displayUrl,
                name = block.name,
                kind = block.kind.name,
                posterPath = block.posterPath,
            )
            is ComposerBlock.Markup -> DraftBlock(ComposerDraft.MARKUP, text = block.markdown)
            // Only a repost has one, and a repost never owns the draft slot —
            // it arrives with its subject and its card already decided, so
            // there is nothing here anybody was half-way through writing.
            is ComposerBlock.Onebox -> null
        }
    }
}
