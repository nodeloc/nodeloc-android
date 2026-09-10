package com.nodeloc.app.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.DiscourseEmoji
import com.nodeloc.app.core.model.EmojiGroup
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.launch

/**
 * Drops `:shortcode:` in at the caret, with a space either side of it where
 * there is not one already — `:tada::tada:` cooks, but `word:tada:` does not.
 */
fun insertEmoji(value: TextFieldValue, shortcode: String): TextFieldValue {
    val at = value.selection.start.coerceIn(0, value.text.length)
    val end = value.selection.end.coerceIn(at, value.text.length)
    val before = value.text.take(at)
    val lead = if (before.isEmpty() || before.last().isWhitespace() || before.endsWith(":")) "" else " "
    val inserted = "$lead:$shortcode: "
    return TextFieldValue(
        value.text.replaceRange(at, end, inserted),
        TextRange(at + inserted.length),
    )
}

/**
 * The site's emoji, not the handset's.
 *
 * Discourse stores an emoji as `:shortcode:` and cooks it into an image, so
 * what this sheet inserts is text — which is also why the site's own uploads
 * work at all: a phone keyboard has no `:xhj001:`, and those are the ones
 * people here actually use.
 *
 * The sheet stays open after a pick. Emoji arrive in runs, and closing after
 * each one would make a three-emoji reply three trips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var groups by remember { mutableStateOf<List<EmojiGroup>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var groupIndex by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val loaded = runCatchingCancellable { ServiceLocator.get.siteRepository.emojiGroups() }
            .getOrDefault(emptyList())
        groups = loaded
        failed = loaded.isEmpty()
    }

    // A search crosses every group; without one the sheet is the selected tab.
    val term = query.trim().lowercase()
    val shown: List<DiscourseEmoji> = remember(groups, term, groupIndex) {
        if (term.isEmpty()) {
            groups.getOrNull(groupIndex)?.emojis.orEmpty()
        } else {
            groups.flatMap { it.emojis }.filter { it.name.contains(term) }.take(200)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).imePadding()) {
            Box(Modifier.padding(horizontal = Space.page, vertical = Space.s3)) {
                FieldSurface {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = Type.body(15).copy(color = Nocturne.text),
                        cursorBrush = SolidColor(Nocturne.accent),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.compose_emoji_search),
                                    style = Type.body(15),
                                    color = Nocturne.muted(0.3f),
                                )
                            }
                            inner()
                        },
                    )
                }
            }

            if (term.isEmpty() && groups.size > 1) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = Space.page),
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    itemsIndexed(groups, key = { _, it -> it.name }) { index, group ->
                        val selected = index == groupIndex
                        Text(
                            group.label,
                            style = Type.body(12, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (selected) Nocturne.accent else Nocturne.muted(0.5f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (selected) Nocturne.surface else Nocturne.bg)
                                .clickable {
                                    groupIndex = index
                                    scope.launch { gridState.scrollToItem(0) }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            when {
                groups.isEmpty() && !failed ->
                    Box(Modifier.fillMaxWidth().height(160.dp), Alignment.Center) {
                        NodelocLoader(height = 20.dp, tint = Nocturne.accent)
                    }

                failed -> Text(
                    stringResource(R.string.compose_emoji_failed),
                    style = Type.body(14),
                    color = Nocturne.muted(0.45f),
                    modifier = Modifier.padding(horizontal = Space.page, vertical = 24.dp),
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(52.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(Space.page),
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                    verticalArrangement = Arrangement.spacedBy(Space.s2),
                ) {
                    items(shown, key = { it.name }) { emoji ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(Radius.md))
                                .clickable { onPick(emoji.name) }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            RemoteImage(
                                DiscourseConfig.absoluteUrl(emoji.url),
                                modifier = Modifier.size(30.dp),
                                contentScale = ContentScale.Fit,
                                contentDescription = emoji.name,
                                placeholder = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
