package com.nodeloc.app.feature.moderation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SearchX
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FilterPill
import com.nodeloc.app.core.design.FilterPillRow
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.OnReachedEnd
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.StatusPill
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiAdminUserRow
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.DiscourseError
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The list's status filter; [key] is the server's `status` value and its `counts` key. */
enum class UserStatusFilter(val key: String?, @StringRes val labelRes: Int) {
    All(null, R.string.mod_users_filter_all),
    Normal("normal", R.string.mod_users_status_normal),
    Silenced("silenced", R.string.mod_users_status_silenced),
    Suspended("suspended", R.string.mod_users_status_suspended),
    Unverified("unverified", R.string.mod_users_status_unverified),
    Deleted("deleted", R.string.mod_users_status_deleted),
}

data class ModUsersState(
    val query: String = "",
    val filter: UserStatusFilter = UserStatusFilter.All,
    val users: List<ApiAdminUserRow> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Throwable? = null,
) {
    val hasMore: Boolean get() = users.size < total
}

class ModUsersViewModel : ViewModel() {
    private val api = ServiceLocator.get.moderationApi
    private val _state = MutableStateFlow(ModUsersState())
    val state: StateFlow<ModUsersState> = _state.asStateFlow()
    private var page = 1
    private var job: Job? = null

    init {
        load(debounce = false)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        load(debounce = true)
    }

    fun setFilter(filter: UserStatusFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        load(debounce = false)
    }

    fun load(debounce: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(350)
            page = 1
            _state.update { it.copy(loading = true, error = null) }
            val current = _state.value
            runCatchingCancellable { api.users(current.query, current.filter.key, page) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(users = list.users, total = list.total, counts = list.counts, loading = false)
                    }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error, users = emptyList(), total = 0) } }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.loading || current.loadingMore) return
        job = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runCatchingCancellable { api.users(current.query, current.filter.key, page + 1) }
                .onSuccess { list ->
                    page += 1
                    _state.update {
                        it.copy(
                            users = (it.users + list.users).distinctBy { user -> user.id },
                            // A short page means the total moved under us; stop rather than ask forever.
                            total = if (list.users.isEmpty()) it.users.size else list.total,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(loadingMore = false) } }
        }
    }
}

@Composable
fun ModUsersScreen(navigator: Navigator) {
    val viewModel: ModUsersViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val privileges by ServiceLocator.get.moderation.privileges.collectAsState()
    val listState = rememberLazyListState()
    listState.OnReachedEnd { viewModel.loadMore() }

    ModScaffold(title = stringResource(R.string.mod_users_title), onBack = navigator::back) {
        if (!privileges.managesUsers) {
            EmptyStateView(Lucide.Users, stringResource(R.string.mod_users_forbidden))
            return@ModScaffold
        }
        SearchField(state.query, viewModel::setQuery)
        FilterPillRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
            UserStatusFilter.entries.forEach { filter ->
                val count = state.counts[filter.key ?: "all"]
                FilterPill(stringResource(filter.labelRes), selected = state.filter == filter, count = count) {
                    viewModel.setFilter(filter)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val error = state.error
            when {
                state.users.isEmpty() && state.loading -> NodelocLoader(Modifier.align(Alignment.Center), height = 40.dp)
                state.users.isEmpty() && error != null -> EmptyStateView(
                    Lucide.CircleAlert,
                    stringResource(R.string.mod_users_load_failed),
                    detail = stringResource((error as? DiscourseError)?.messageRes ?: R.string.error_action_failed),
                    retryLabel = stringResource(R.string.common_retry),
                    onRetry = { viewModel.load() },
                )
                state.users.isEmpty() -> EmptyStateView(Lucide.SearchX, stringResource(R.string.mod_users_empty))
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.users, key = { it.id }) { user ->
                        UserRow(user) { navigator.openModUser(user.id, user.username, null) }
                    }
                    if (state.loadingMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                NodelocLoader(height = 28.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    FieldSurface(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Lucide.Search, null, tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.mod_users_search_hint), style = Type.body(16), color = Nocturne.textTertiary)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = Type.body(16).copy(color = Nocturne.text),
                    cursorBrush = SolidColor(Nocturne.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {}),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Lucide.X,
                    stringResource(R.string.common_clear),
                    tint = Nocturne.textSecondary,
                    modifier = Modifier.size(20.dp).clickable { onChange("") },
                )
            }
        }
    }
}

@Composable
private fun UserRow(user: ApiAdminUserRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 72.dp)
            .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RemoteAvatar(DiscourseConfig.absoluteUrl(user.avatarUrl), user.username, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(user.username, user.name?.takeIf { it.isNotBlank() && it != user.username }).joinToString(" · "),
                style = Type.body(16),
                color = Nocturne.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    user.trustLevel?.let { stringResource(R.string.mod_users_level, it) },
                    stringResource(R.string.mod_users_posts, user.posts),
                    modAgo(user.createdAt).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.mod_users_joined, it) },
                ).joinToString(" · "),
                style = Type.body(14),
                color = Nocturne.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (user.role) {
            "admin" -> StatusPill(stringResource(R.string.mod_entry_role_admin), PillTone.Info)
            "moderator" -> StatusPill(stringResource(R.string.mod_entry_role_moderator), PillTone.Info)
        }
        UserStatusPill(user.status)
    }
}

@Composable
internal fun UserStatusPill(status: String) {
    when (status) {
        "silenced" -> StatusPill(stringResource(R.string.mod_users_status_silenced), PillTone.Warning)
        "suspended" -> StatusPill(stringResource(R.string.mod_users_status_suspended), PillTone.Danger)
        "unverified" -> StatusPill(stringResource(R.string.mod_users_status_unverified), PillTone.Neutral)
        "deleted" -> StatusPill(stringResource(R.string.mod_users_status_deleted), PillTone.Neutral)
        else -> StatusPill(stringResource(R.string.mod_users_status_normal), PillTone.Accent)
    }
}
