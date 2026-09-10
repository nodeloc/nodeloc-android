package com.nodeloc.app.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.delay

/**
 * `@` for a person, `#` for a node or a tag — the two completions the site's
 * own composer offers, in the two places this app lets you write.
 *
 * Deliberately a strip of chips above the field rather than a dropdown over it.
 * The reply sheet is already sharing the screen with a keyboard, and a list
 * that covers what you just typed to show you what you might type next is the
 * wrong trade in that much space.
 */

enum class AutocompleteKind(val trigger: Char) { User('@'), Node('#') }

data class AutocompleteQuery(val kind: AutocompleteKind, val term: String, val start: Int)

/**
 * What gets inserted, and what the chip says.
 *
 * [avatarUrl] is the whole point of the chip for a mention: usernames on this
 * site differ by a character often enough that a row of names alone is a
 * guess, and the face is what the writer actually recognises.
 */
data class AutocompleteItem(
    val insert: String,
    val label: String,
    val detail: String? = null,
    val avatarUrl: String? = null,
)

/**
 * The token being typed at the caret, if it is one.
 *
 * A trigger only counts at the start of a word — `a@b` is an email address, not
 * a mention — and the term ends at the first space, so the strip closes by
 * itself once the thought moves on. Nothing fires until the caret is actually
 * inside the token: moving away from `@ali` should stop offering to complete
 * it.
 */
fun detectAutocomplete(value: TextFieldValue): AutocompleteQuery? {
    val caret = value.selection.takeIf { it.collapsed }?.start ?: return null
    val text = value.text
    if (caret > text.length) return null

    var index = caret - 1
    while (index >= 0) {
        val char = text[index]
        if (char.isWhitespace()) return null
        val kind = AutocompleteKind.entries.firstOrNull { it.trigger == char }
        if (kind != null) {
            val before = text.getOrNull(index - 1)
            if (before != null && !before.isWhitespace()) return null
            val term = text.substring(index + 1, caret)
            // A term is the part after the trigger; the trigger alone is a
            // prompt to start listing, which is what the web does too.
            return AutocompleteQuery(kind, term, index)
        }
        index--
    }
    return null
}

/** Replaces the token with the pick, and leaves the caret after the space. */
fun applyAutocomplete(value: TextFieldValue, query: AutocompleteQuery, item: AutocompleteItem): TextFieldValue {
    val end = (query.start + 1 + query.term.length).coerceAtMost(value.text.length)
    val inserted = "${query.kind.trigger}${item.insert} "
    val text = value.text.replaceRange(query.start, end, inserted)
    val caret = query.start + inserted.length
    return TextFieldValue(text, TextRange(caret))
}

/**
 * The strip. Draws nothing at all when there is no token and nothing to offer,
 * so it costs the layout nothing in the ordinary case.
 */
@Composable
fun AutocompleteStrip(
    value: TextFieldValue,
    onPick: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = detectAutocomplete(value)
    var items by remember { mutableStateOf<List<AutocompleteItem>>(emptyList()) }

    LaunchedEffect(query?.kind, query?.term) {
        if (query == null) {
            items = emptyList()
            return@LaunchedEffect
        }
        // A keystroke is not a search. Every letter would otherwise be a round
        // trip, and the answer to the word half-typed is never the one wanted.
        delay(220)
        items = runCatchingCancellable { suggestions(query) }.getOrDefault(emptyList())
    }

    if (query == null || items.isEmpty()) return

    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(items, key = { it.insert }) { item ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(Radius.md))
                    .background(Nocturne.surface)
                    .clickable { onPick(applyAutocomplete(value, query, item)) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.avatarUrl?.let {
                    RemoteAvatar(it, item.label.take(1).uppercase(), size = 18.dp)
                }
                Text(
                    "${query.kind.trigger}${item.label}",
                    style = Type.body(13, FontWeight.Medium),
                    color = Nocturne.accent,
                )
                item.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = Type.body(11), color = Nocturne.muted(0.45f))
                }
            }
        }
    }
}

/**
 * Nodes come from the site response the app already holds, so `#` answers
 * before a request could have been made; only tags are fetched. Users always
 * are — the directory is far too big to carry.
 */
private suspend fun suggestions(query: AutocompleteQuery): List<AutocompleteItem> {
    val services = ServiceLocator.get
    val term = query.term.trim()
    return when (query.kind) {
        AutocompleteKind.User ->
            services.client.searchUsers(term.ifEmpty { "" }, limit = 6).users.orEmpty().map {
                AutocompleteItem(
                    insert = it.username,
                    label = it.username,
                    detail = it.name,
                    avatarUrl = DiscourseConfig.avatarUrl(it.avatarTemplate, 80),
                )
            }

        AutocompleteKind.Node -> {
            val nodes = services.siteRepository.categories().values
                .filter { term.isEmpty() || it.matches(term) }
                .take(5)
                .map {
                    AutocompleteItem(
                        insert = it.slug,
                        label = it.slug,
                        detail = it.name,
                    )
                }
                .filter { it.insert.isNotEmpty() }
            val tags = runCatchingCancellable { services.client.searchTags(term, limit = 5) }
                .getOrNull()
                ?.results
                .orEmpty()
                .map { AutocompleteItem(insert = it.name, label = it.name, detail = it.countLabel()) }
            (nodes + tags).distinctBy { it.insert }.take(8)
        }
    }
}

private fun com.nodeloc.app.core.model.DiscourseCategory.matches(term: String): Boolean =
    slug.contains(term, ignoreCase = true) || name.contains(term, ignoreCase = true)

private fun com.nodeloc.app.core.model.DiscourseTag.countLabel(): String? =
    count?.takeIf { it > 0 }?.toString()
