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
    val color: String = "0088CC",
    val parents: List<NodeSummary> = emptyList(),
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
            val parents = site.categories().values
                .filter { it.parentCategoryId == null }
                .map(NodeSummaryFactory::summary)
            _state.value = _state.value.copy(
                parents = parents,
                parent = _state.value.parent ?: parents.firstOrNull(),
            )
        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("0088CC", "009966", "FF9933", "C80001", "8B5CF6", "333333").forEach { hex ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(("FF$hex").toLong(16)))
                                .clickable { viewModel.updateColor(hex) },
                        )
                    }
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
