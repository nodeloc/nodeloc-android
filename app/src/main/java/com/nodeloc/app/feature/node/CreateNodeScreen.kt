package com.nodeloc.app.feature.node

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateNodeState(
    val name: String = "",
    val slug: String = "",
    val description: String = "",
    val color: String = NODE_PALETTE.first(),
    val parents: List<NodeSummary> = emptyList(),
    /** `node-parents` could not be read, or names no section: nothing is offered rather than everything. */
    val parentsError: Int? = null,
    val parent: NodeSummary? = null,
    val slugAvailable: Boolean? = null,
    val checkingSlug: Boolean = false,
    val isSubmitting: Boolean = false,
)

/**
 * The slug a node name suggests.
 *
 * Discourse accepts a unicode slug when `slug_generation_method` allows it, and
 * stripping to ASCII produced nothing at all for a Chinese node name — so the
 * slug stayed empty and the create button never enabled. Top-level because the
 * rule is pure, and because getting it wrong is invisible until a node is
 * created under a one-letter slug.
 */
internal fun slugifyNodeName(value: String): String =
    value.trim().lowercase()
        .replace(Regex("[\\s_]+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}-]"), "")
        .replace(Regex("-+"), "-")
        .trim('-')

class CreateNodeViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val site = services.siteRepository

    private val _state = MutableStateFlow(CreateNodeState())
    val state: StateFlow<CreateNodeState> = _state.asStateFlow()

    private var slugJob: Job? = null

    fun load() {
        // The parent list does not change, and re-running this on a rotation
        // put the selection back to the first entry.
        if (_state.value.parents.isNotEmpty()) return
        viewModelScope.launch {
            site.siteResponse()
            val sections = site.categories().values
                .filter { it.parentCategoryId == null }
                .map(NodeSummaryFactory::summary)
            // Only the sections the server will take a new node under for this
            // account (`open_for_nodes`). Listing every section put up ones the
            // server then refused, so an unreadable answer offers none.
            val open = runCatchingCancellable { creatableParentSlugs(sections.map { it.slug }) }.getOrNull()
            if (open == null) {
                _state.value = _state.value.copy(parentsError = R.string.node_create_parents_failed)
                return@launch
            }
            val parents = sections.filter { it.slug.lowercase() in open }.sortedBy { it.name }
            _state.value = _state.value.copy(
                parents = parents,
                parent = _state.value.parent?.takeIf { it in parents } ?: parents.firstOrNull(),
                parentsError = if (parents.isEmpty()) R.string.node_create_parents_none else null,
            )
        }
    }

    /**
     * `GET /api/v1/node-parents` answers at most 10 rows; when the first page
     * is full, each section it did not name is asked for by its slug.
     */
    private suspend fun creatableParentSlugs(candidates: List<String>): Set<String> {
        val first = client.api.get<ApiNodeParents>("api/v1/node-parents")
        val open = first.nodes.mapTo(mutableSetOf()) { it.slug.lowercase() }
        if (first.nodes.size < 10) return open
        for (slug in candidates.filter { it.lowercase() !in open }) {
            val page = runCatchingCancellable { client.api.get<ApiNodeParents>("api/v1/node-parents", listOf("q" to slug)) }.getOrNull()
            if (page?.nodes?.any { it.slug.equals(slug, ignoreCase = true) } == true) open += slug.lowercase()
        }
        return open
    }

    private fun slugify(value: String) = slugifyNodeName(value)

    fun updateName(value: String) {
        // Tested against the name being replaced, not the one replacing it.
        // Comparing after the write meant "ab" no longer matched the slug "a"
        // generated from "a", so the slug stopped following after the first
        // keystroke and every node was created as a one-letter slug.
        val followed = _state.value.slug.isEmpty() || _state.value.slug == slugify(_state.value.name)
        _state.value = _state.value.copy(name = value)
        if (followed) updateSlug(slugify(value))
    }

    fun updateSlug(value: String) {
        _state.value = _state.value.copy(slug = value, checkingSlug = value.isNotEmpty(), slugAvailable = null)
        slugJob?.cancel()
        if (value.isEmpty()) return
        slugJob = viewModelScope.launch {
            delay(400)
            val result = runCatchingCancellable { client.checkNodeSlug(value) }.getOrNull()
            _state.value = _state.value.copy(slugAvailable = result?.available, checkingSlug = false)
        }
    }

    fun updateDescription(value: String) {
        _state.value = _state.value.copy(description = value)
    }

    fun updateColor(value: String) {
        _state.value = _state.value.copy(color = value)
    }

    fun selectParent(parent: NodeSummary) {
        _state.value = _state.value.copy(parent = parent)
    }

    fun submit(onCreated: (Int, String) -> Unit) {
        val current = _state.value
        val parent = current.parent ?: return
        viewModelScope.launch {
            _state.value = current.copy(isSubmitting = true)
            runCatchingCancellable {
                client.createNode(
                    name = current.name.trim(),
                    slug = current.slug.trim(),
                    description = current.description.trim(),
                    colorHex = current.color,
                    parentCategoryId = parent.id,
                )
            }
                .onSuccess { response ->
                    ToastCenter.show(R.string.node_created)
                    site.refresh()
                    response.category?.let { onCreated(it.id, it.slug) }
                }
                .onFailure { ToastCenter.showError(it) }
            _state.value = _state.value.copy(isSubmitting = false)
        }
    }


}

@Composable
fun CreateNodeScreen(navigator: Navigator) {
    val viewModel: CreateNodeViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Nocturne.bg)
            .verticalScroll(rememberScrollState())
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = navigator::back)
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.page),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(stringResource(R.string.node_create_title), style = Type.heading(26, FontWeight.Bold), color = Nocturne.text)

            LabeledField(stringResource(R.string.node_name), state.name, stringResource(R.string.node_name_hint), viewModel::updateName)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledField(stringResource(R.string.node_slug), state.slug, stringResource(R.string.node_slug_hint), viewModel::updateSlug)
                when {
                    state.checkingSlug -> Text(stringResource(R.string.common_checking), style = Type.body(12), color = Nocturne.muted(0.45f))
                    state.slugAvailable == true -> Text(stringResource(R.string.node_slug_available), style = Type.body(12), color = Nocturne.success)
                    state.slugAvailable == false -> Text(stringResource(R.string.node_slug_taken), style = Type.body(12), color = Nocturne.danger)
                }
            }

            LabeledField(stringResource(R.string.node_description), state.description, stringResource(R.string.node_description_hint), viewModel::updateDescription, singleLine = false)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.node_parent), style = Type.body(12), color = Nocturne.muted(0.55f))
                // Scrolls rather than take(6): a site with seven top-level
                // categories simply could not have the seventh chosen.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.parentsError?.let {
                        Text(stringResource(it), style = Type.body(13), color = Nocturne.textSecondary)
                    }
                    state.parents.forEach { parent ->
                        val selected = parent.id == state.parent?.id
                        Text(
                            parent.name,
                            style = Type.body(13, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (selected) Nocturne.accent else Nocturne.muted(0.55f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) Nocturne.selected else Nocturne.surface)
                                .clickable { viewModel.selectParent(parent) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.node_color), style = Type.body(12), color = Nocturne.muted(0.55f))
                var paletteOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NODE_PALETTE.forEach { hex ->
                        ColorSwatch(hex, selected = state.color.equals(hex, ignoreCase = true)) { viewModel.updateColor(hex) }
                    }
                    // Any other colour, from the fuller palette. Opaque only:
                    // the server keeps six hex digits.
                    val custom = NODE_PALETTE.none { it.equals(state.color, ignoreCase = true) }
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (custom) hexColor(state.color) else Nocturne.neutral300)
                            .clickable(onClickLabel = stringResource(R.string.node_color_custom)) { paletteOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (custom) Lucide.Check else Lucide.Palette,
                            stringResource(R.string.node_color_custom),
                            tint = if (custom) Color.White else Nocturne.textSecondary,
                            modifier = Modifier.size(if (custom) 14.dp else 16.dp),
                        )
                    }
                }
                if (paletteOpen) {
                    PaletteDialog(
                        selected = state.color,
                        onDismiss = { paletteOpen = false },
                        onPick = { hex ->
                            paletteOpen = false
                            viewModel.updateColor(hex)
                        },
                    )
                }
            }

            Text(
                stringResource(R.string.node_create_footer),
                style = Type.body(12),
                color = Nocturne.muted(0.45f),
            )

            FilledAccentButton(
                stringResource(R.string.node_create_title),
                enabled = state.name.isNotBlank() && state.slugAvailable == true && state.parent != null,
                loading = state.isSubmitting,
            ) {
                viewModel.submit { id, slug ->
                    navigator.back()
                    navigator.openNode(id, slug)
                }
            }
        }
        Box(Modifier.padding(bottom = 48.dp))
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = Type.body(12), color = Nocturne.muted(0.55f))
        FieldSurface {
            // The field owns its text and tells the ViewModel afterwards.
            // Routing every keystroke out and back through a StateFlow puts a
            // frame in the way, and pinyin composition — which is most of what
            // gets typed into a node name here — drops characters when the
            // value it is composing against arrives late. The slug field is
            // worse still: it is rewritten as the name is typed.
            var field by remember { mutableStateOf(TextFieldValue(value)) }
            if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    onValueChange(it.text)
                },
                singleLine = singleLine,
                textStyle = Type.body(15).copy(color = Nocturne.text),
                cursorBrush = SolidColor(Nocturne.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (field.text.isEmpty()) {
                        Text(placeholder, style = Type.body(15), color = Nocturne.muted(0.3f))
                    }
                    inner()
                },
            )
        }
    }
}

/** The node colours the site offers first, as iOS lists them. */
internal val NODE_PALETTE = listOf("009966", "0088CC", "6F42C1", "E45735", "FFA500", "C90D0D", "577590", "2D9CDB")

/** A fuller palette behind 自定义颜色: hues across the wheel, each in three depths, plus greys. */
private val NODE_PALETTE_FULL = listOf(
    "B71C1C", "E53935", "EF9A9A",
    "880E4F", "D81B60", "F48FB1",
    "4A148C", "8E24AA", "CE93D8",
    "1A237E", "3949AB", "9FA8DA",
    "01579B", "039BE5", "81D4FA",
    "004D40", "00897B", "80CBC4",
    "1B5E20", "43A047", "A5D6A7",
    "827717", "C0CA33", "E6EE9C",
    "E65100", "FB8C00", "FFCC80",
    "3E2723", "6D4C41", "BCAAA4",
    "212121", "757575", "BDBDBD",
)

private fun hexColor(hex: String): Color =
    hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: Color.Gray

@Composable
private fun ColorSwatch(hex: String, selected: Boolean, size: androidx.compose.ui.unit.Dp = 32.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(hexColor(hex))
            .clickable(onClickLabel = "#$hex", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Lucide.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun PaletteDialog(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = { Text(stringResource(R.string.node_color_custom), style = Type.heading(20), color = Nocturne.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NODE_PALETTE_FULL.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { hex ->
                            ColorSwatch(hex, selected = hex.equals(selected, ignoreCase = true), size = 34.dp) { onPick(hex) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Nocturne.textSecondary)
            }
        },
    )
}

@kotlinx.serialization.Serializable
private data class ApiNodeParents(val nodes: List<ApiNodeParent> = emptyList())

@kotlinx.serialization.Serializable
private data class ApiNodeParent(val slug: String = "")
