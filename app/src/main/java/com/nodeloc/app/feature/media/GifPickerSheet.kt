package com.nodeloc.app.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.RemoteImage
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.KlipyGif
import kotlinx.coroutines.delay

/**
 * The GIF picker, shared by everything that can attach one.
 *
 * It holds its own query and results rather than a screen's view model: a GIF
 * is already hosted, so picking one is a URL and nothing else — there is no
 * upload, no draft and nothing worth surviving the sheet closing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GifPickerSheet(
    search: suspend (String) -> List<KlipyGif>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<KlipyGif>()) }
    var searching by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Keyed on the query, so a keystroke cancels the search before it and the
    // grid can only ever show the answer to what is currently typed. The pause
    // is what stops a word being eight searches.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        results = runCatching { search(query) }.getOrDefault(emptyList())
        searching = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
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
                                    stringResource(R.string.compose_gif_search),
                                    style = Type.body(15),
                                    color = Nocturne.muted(0.3f),
                                )
                            }
                            inner()
                        },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = Space.page),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results) { gif ->
                        val url = gif.mediaFormats?.get("gif")?.url
                        if (url != null) {
                            RemoteImage(
                                url,
                                modifier = Modifier
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .clickable { onPick(url) },
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                // An empty grid means one of three different things, and a
                // silent one leaves the user guessing which.
                when {
                    searching -> Box(Modifier.fillMaxWidth().height(360.dp), Alignment.Center) {
                        NodelocLoader(height = 26.dp)
                    }
                    results.isEmpty() -> Box(Modifier.fillMaxWidth().height(360.dp), Alignment.Center) {
                        Text(
                            stringResource(
                                if (query.isBlank()) R.string.compose_gif_search else R.string.search_empty,
                            ),
                            style = Type.body(14, FontWeight.Normal),
                            color = Nocturne.muted(0.35f),
                        )
                    }
                }
            }
        }
    }
}
