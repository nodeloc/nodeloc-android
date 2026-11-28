package com.nodeloc.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderInput
import com.composables.icons.lucide.FolderPlus
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiBookmarkFolder
import com.nodeloc.app.core.network.ApiSaved
import com.nodeloc.app.core.network.BookmarkApi
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModTopBar
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.settings.SetField
import com.nodeloc.app.feature.settings.SetFormDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which slice of the saves is showing: everything, 未分类 (`none`), or one folder's id. */
data class BookmarksState(
    val folders: List<ApiBookmarkFolder> = emptyList(),
    val total: Int? = null,
    val unfiled: Int? = null,
    val folder: String? = null,
    val items: List<ApiSaved> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val nextOffset: Int? = null,
    val error: Throwable? = null,
)

class BookmarksViewModel : ViewModel() {
    private val api = BookmarkApi(ServiceLocator.get.client.api)
    private val _state = MutableStateFlow(BookmarksState())
    val state: StateFlow<BookmarksState> = _state.asStateFlow()

    fun load() {
        loadFolders()
        reload()
    }

    private fun loadFolders() {
        viewModelScope.launch {
            runCatchingCancellable { api.folders() }.onSuccess { found ->
                _state.value = _state.value.copy(folders = found.folders, total = found.total, unfiled = found.unfiled)
            }
        }
    }

    fun select(folder: String?) {
        if (_state.value.folder == folder && !_state.value.loading) return
        _state.value = _state.value.copy(folder = folder)
        reload()
    }

    private fun reload() {
        val folder = _state.value.folder
        _state.value = _state.value.copy(loading = true, error = null, items = emptyList(), nextOffset = null)
        viewModelScope.launch {
            runCatchingCancellable { api.saved(folder = folder) }
                .onSuccess { page ->
                    // A newer tap on another folder owns the list now.
                    if (_state.value.folder != folder) return@onSuccess
                    _state.value = _state.value.copy(loading = false, items = page.bookmarks, nextOffset = page.nextOffset)
                }
                .onFailure { if (_state.value.folder == folder) _state.value = _state.value.copy(loading = false, error = it) }
        }
    }

    fun loadMore() {
        val current = _state.value
        val offset = current.nextOffset ?: return
        if (current.loading || current.loadingMore) return
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            runCatchingCancellable { api.saved(folder = current.folder, offset = offset) }
                .onSuccess { page ->
                    if (_state.value.folder != current.folder) return@onSuccess
                    _state.value = _state.value.copy(
                        loadingMore = false,
                        items = (_state.value.items + page.bookmarks).distinctBy { it.id },
                        nextOffset = page.nextOffset,
                    )
                }
                .onFailure { _state.value = _state.value.copy(loadingMore = false) }
        }
    }

    fun createFolder(name: String, then: (Int) -> Unit = {}) {
        viewModelScope.launch {
            runCatchingCancellable { api.createFolder(name.trim()) }
                .onSuccess { id ->
                    ToastCenter.show(R.string.bookmarks_folder_created)
                    loadFolders()
                    then(id)
                }
                .onFailure(ToastCenter::showError)
        }
    }

    fun renameFolder(id: Int, name: String) {
        viewModelScope.launch {
            runCatchingCancellable { api.renameFolder(id, name.trim()) }
                .onSuccess { loadFolders() }
                .onFailure(ToastCenter::showError)
        }
    }

    /** Its saves go back to 未分类, so the list is read again rather than patched. */
    fun deleteFolder(id: Int) {
        viewModelScope.launch {
            runCatchingCancellable { api.deleteFolder(id) }
                .onSuccess {
                    ToastCenter.show(R.string.bookmarks_folder_deleted)
                    if (_state.value.folder == id.toString()) _state.value = _state.value.copy(folder = null)
                    load()
                }
                .onFailure(ToastCenter::showError)
        }
    }

    /**
     * The card changes on the tap — and leaves a filtered folder it no longer
     * belongs in; a refusal puts the list back as it was.
     */
    fun move(item: ApiSaved, folderId: Int?) {
        val before = _state.value.items
        val folder = _state.value.folder
        val stays = folder == null || folder == (folderId?.toString() ?: "none")
        _state.value = _state.value.copy(
            items = if (stays) before.map { if (it.id == item.id) it.copy(folderId = folderId) else it } else before.filterNot { it.id == item.id },
        )
        viewModelScope.launch {
            runCatchingCancellable { api.move(listOf(item.id), folderId) }
                .onSuccess {
                    ToastCenter.show(R.string.bookmarks_moved)
                    loadFolders()
                }
                .onFailure {
                    _state.value = _state.value.copy(items = before)
                    ToastCenter.showError(it)
                }
        }
    }

    fun remove(item: ApiSaved) {
        val before = _state.value.items
        _state.value = _state.value.copy(items = before.filterNot { it.id == item.id })
        viewModelScope.launch {
            runCatchingCancellable { api.remove(listOf(item.id)) }
                .onSuccess {
                    ToastCenter.show(R.string.bookmarks_removed)
                    loadFolders()
                }
                .onFailure {
                    _state.value = _state.value.copy(items = before)
                    ToastCenter.showError(it)
                }
        }
    }
}

/**
 * 我的收藏 (App-Android-Saved): folder chips over the newest saves, each card
 * with its folder and a button to move it. A folder chip held down offers
 * rename and delete; 新建 is the last chip. Only the account sees any of it.
 */
@Composable
fun BookmarksScreen(navigator: Navigator) {
    val viewModel: BookmarksViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ApiBookmarkFolder?>(null) }
    var deleting by remember { mutableStateOf<ApiBookmarkFolder?>(null) }
    var folderMenu by remember { mutableStateOf<ApiBookmarkFolder?>(null) }
    var moving by remember { mutableStateOf<ApiSaved?>(null) }
    var removing by remember { mutableStateOf<ApiSaved?>(null) }

    LaunchedEffect(Unit) { viewModel.load() }
    listState.OnReachedEnd { viewModel.loadMore() }

    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        ModTopBar(stringResource(R.string.sidebar_bookmarks), navigator::back) {
            HeaderIconButton(Lucide.FolderPlus, stringResource(R.string.bookmarks_folder_new)) { creating = true }
        }
        FilterPillRow {
            FilterPill(stringResource(R.string.bookmarks_all), state.folder == null, count = state.total) { viewModel.select(null) }
            state.folders.forEach { folder ->
                Box {
                    FilterPill(
                        folder.name,
                        state.folder == folder.id.toString(),
                        count = folder.count,
                        onLongClick = { folderMenu = folder },
                    ) { viewModel.select(folder.id.toString()) }
                    DropdownMenu(expanded = folderMenu == folder, onDismissRequest = { folderMenu = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmarks_folder_rename)) },
                            onClick = { folderMenu = null; renaming = folder },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmarks_folder_delete), color = Nocturne.danger) },
                            onClick = { folderMenu = null; deleting = folder },
                        )
                    }
                }
            }
            FilterPill(stringResource(R.string.bookmarks_unfiled), state.folder == "none", count = state.unfiled) { viewModel.select("none") }
        }
        HairLine()

        when {
            state.loading -> RowsSkeleton(rows = 6, avatar = false)
            state.error != null && state.items.isEmpty() -> EmptyStateView(
                icon = Lucide.CircleAlert,
                title = stringResource(R.string.error_action_failed),
                retryLabel = stringResource(R.string.common_retry),
                onRetry = viewModel::load,
            )
            state.items.isEmpty() -> EmptyStateView(
                icon = if (state.folder == null) Lucide.Bookmark else Lucide.Folder,
                title = stringResource(R.string.bookmarks_empty),
                detail = stringResource(if (state.folder == null) R.string.bookmarks_private else R.string.bookmarks_empty_folder),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    BookmarkCard(
                        item = item,
                        folderName = state.folders.firstOrNull { it.id == item.folderId }?.name,
                        onOpen = { item.topicId?.let { navigator.openTopic(it, item.postNumber?.takeIf { n -> n > 1 }) } },
                        onMove = { moving = item },
                        onRemove = { removing = item },
                    )
                    HairLine(Modifier.padding(start = Space.page))
                }
                item(key = "footer") {
                    Text(
                        stringResource(R.string.bookmarks_private),
                        style = Type.body(12),
                        color = Nocturne.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(Space.page),
                    )
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
        }
    }

    if (creating) {
        FolderNameDialog(stringResource(R.string.bookmarks_folder_new), "", onDismiss = { creating = false }) {
            creating = false
            viewModel.createFolder(it)
        }
    }
    renaming?.let { folder ->
        FolderNameDialog(stringResource(R.string.bookmarks_folder_rename), folder.name, onDismiss = { renaming = null }) {
            renaming = null
            viewModel.renameFolder(folder.id, it)
        }
    }
    deleting?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.bookmarks_folder_delete_title, folder.name),
            body = stringResource(R.string.bookmarks_folder_delete_body),
            confirmLabel = stringResource(R.string.bookmarks_folder_delete),
            onConfirm = { deleting = null; viewModel.deleteFolder(folder.id) },
            onDismiss = { deleting = null },
        )
    }
    removing?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.bookmarks_remove_title),
            body = item.topicTitle.orEmpty(),
            confirmLabel = stringResource(R.string.bookmarks_remove),
            onConfirm = { removing = null; viewModel.remove(item) },
            onDismiss = { removing = null },
        )
    }
    moving?.let { item ->
        BookmarkFolderSheet(
            folders = state.folders,
            current = item.folderId,
            onDismiss = { moving = null },
            onCreate = { name -> viewModel.createFolder(name) { id -> viewModel.move(item, id) } },
            onChoose = { folderId -> moving = null; viewModel.move(item, folderId) },
        )
    }
}

@Composable
private fun BookmarkCard(item: ApiSaved, folderName: String?, onOpen: () -> Unit, onMove: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = Space.page, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(item.nodeSlug?.let { "n/$it" }, item.author).joinToString(" · "),
                style = Type.body(12),
                color = Nocturne.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.topicTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bookmarks_gone),
                style = Type.body(16, FontWeight.SemiBold),
                color = Nocturne.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.excerpt?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            item.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Type.body(13), color = Nocturne.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(
                    stringResource(R.string.bookmarks_saved_at, DiscourseFormat.relative(item.createdAt)),
                    folderName ?: stringResource(R.string.bookmarks_unfiled),
                ).joinToString(" · "),
                style = Type.body(12),
                color = Nocturne.textTertiary,
            )
        }
        HeaderIconButton(Lucide.FolderInput, stringResource(R.string.bookmarks_move), onClick = onMove)
        HeaderIconButton(Lucide.Trash2, stringResource(R.string.bookmarks_remove), onClick = onRemove)
    }
}

@Composable
private fun FolderNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    SetFormDialog(
        title = title,
        fields = listOf(SetField(stringResource(R.string.bookmarks_folder_name), initial, maxLength = 16)),
        confirmLabel = stringResource(R.string.common_save),
        onDismiss = onDismiss,
    ) { values -> onConfirm(values.first()) }
}

/**
 * Which 收藏夹 a save is in: 未分类 and the folders with their counts, a check
 * on the current one, 新建收藏夹 inline, and — from a post — 取消收藏. Shared by
 * the reader's save and the cards of 我的收藏.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkFolderSheet(
    folders: List<ApiBookmarkFolder>,
    current: Int?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onChoose: (Int?) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var creating by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Nocturne.bg) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.bookmarks_choose_folder),
                style = Type.heading(18, FontWeight.Bold),
                color = Nocturne.text,
                modifier = Modifier.padding(horizontal = Space.page, vertical = 8.dp),
            )
            FolderChoiceRow(Lucide.Inbox, stringResource(R.string.bookmarks_unfiled), null, current == null) { onChoose(null) }
            folders.forEach { folder ->
                FolderChoiceRow(Lucide.Folder, folder.name, folder.count, current == folder.id) { onChoose(folder.id) }
            }
            FolderChoiceRow(Lucide.FolderPlus, stringResource(R.string.bookmarks_folder_new), null, false, tint = Nocturne.accent) { creating = true }
            if (onRemove != null) {
                HairLine(Modifier.padding(vertical = 4.dp))
                FolderChoiceRow(Lucide.Trash2, stringResource(R.string.bookmarks_remove), null, false, tint = Nocturne.danger) { onRemove() }
            }
        }
    }
    if (creating) {
        FolderNameDialog(stringResource(R.string.bookmarks_folder_new), "", onDismiss = { creating = false }) {
            creating = false
            onCreate(it)
        }
    }
}

@Composable
private fun FolderChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    tint: androidx.compose.ui.graphics.Color = Nocturne.text,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = Space.page),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (tint == Nocturne.text) Nocturne.textSecondary else tint, modifier = Modifier.size(20.dp))
        Text(label, style = Type.body(16), color = tint, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        count?.let { Text(it.toString(), style = Type.body(14), color = Nocturne.textTertiary) }
        if (selected) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Nocturne.selected), contentAlignment = Alignment.Center) {
                Icon(Lucide.Check, null, tint = Nocturne.accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}
