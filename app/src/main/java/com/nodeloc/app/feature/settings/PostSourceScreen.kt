package com.nodeloc.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FloatingHeaderBar
import com.nodeloc.app.core.design.HeaderGroup
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SettingsNavRow
import com.nodeloc.app.core.design.SettingsPickerRow
import com.nodeloc.app.core.design.SettingsSection
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.floatingHeaderInset
import com.nodeloc.app.core.design.isScrolledPastTop
import com.nodeloc.app.core.design.rememberTapSafeOverscroll
import com.nodeloc.app.feature.nav.Navigator

/**
 * 发帖来源 — how much of this handset a post may say.
 *
 * A ladder rather than a set of switches: "show the model but not the brand"
 * is not a thing anyone means. Every rung shows what it would actually print
 * on *this* phone, because "手机品牌" and "来自 iQOO" are not the same promise
 * to someone deciding whether to agree to it.
 */
@Composable
fun PostSourceScreen(navigator: Navigator) {
    val viewModel: PostSourceViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.load() }

    Box(Modifier.fillMaxSize().background(Nocturne.bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = floatingHeaderInset, bottom = 32.dp),
            overscrollEffect = rememberTapSafeOverscroll(),
        ) {
            item {
                Text(
                    stringResource(R.string.post_source_explain),
                    style = Type.body(13),
                    color = Nocturne.muted(0.55f),
                    modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s3),
                )
            }

            item {
                SettingsSection(footer = stringResource(R.string.post_source_footer)) {
                    SettingsPickerRow(
                        title = stringResource(R.string.post_source_title),
                        options = PostSourceLevel.entries,
                        selected = PostSourceLevel.of(state.level),
                        label = { stringResource(it.labelRes) },
                        // What this rung would actually print, on this handset.
                        detail = { it.sample() },
                        onSelect = { viewModel.select(it.value) },
                    )
                }
            }

            item {
                SettingsSection(
                    title = stringResource(R.string.post_source_history_title),
                    // Lowering the rung governs what comes next; only this
                    // reaches what is already written, and it does not hide it.
                    footer = stringResource(R.string.post_source_history_detail),
                ) {
                    SettingsNavRow(
                        title = stringResource(R.string.post_source_clear),
                        tint = Nocturne.danger,
                        onClick = viewModel::clearHistory,
                    )
                }
            }
        }

        FloatingHeaderBar(
            scrolled = listState.isScrolledPastTop(threshold = 0),
            leading = {
                HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
            },
            center = {
                HeaderGroup {
                    Text(
                        stringResource(R.string.post_source_title),
                        style = Type.heading(17, FontWeight.SemiBold),
                        color = Nocturne.headerText,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            },
        )
    }
}
