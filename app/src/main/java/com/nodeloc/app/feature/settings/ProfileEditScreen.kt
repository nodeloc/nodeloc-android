package com.nodeloc.app.feature.settings

import com.nodeloc.app.core.util.runCatchingCancellable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.nodeloc.app.core.design.Radius
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Camera
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ripple
import androidx.compose.ui.text.style.TextOverflow
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.composables.icons.lucide.Trash2
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.RemoteAvatar
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.SettingsRowDivider
import com.nodeloc.app.core.design.SettingsSection
import com.nodeloc.app.core.design.SettingsPickerRow
import com.nodeloc.app.core.design.SettingsValueRow
import com.nodeloc.app.core.design.SettingsTextRow
import com.composables.icons.lucide.CircleAlert
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileEditState(
    val name: String = "",
    val bio: String = "",
    val website: String = "",
    val location: String = "",
    val title: String = "",
    /**
     * Titles this account may actually wear. Free text does not work:
     * `UserUpdater` only applies `title` when `Guardian#can_grant_title?`
     * passes, and silently drops it otherwise — so a typed title looks saved
     * and never is.
     */
    val availableTitles: List<String> = emptyList(),
    val avatarUrl: String? = null,
    val backgroundUrl: String? = null,
    /** `profile_background_allowed_groups` decides this; without it the server
     *  silently drops the field, so the row is hidden rather than lying. */
    val canEditBackground: Boolean = false,
    /** Starts true: the screen opens before the profile has been fetched. */
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    /** Distinguishes "nothing fetched" from "this profile really is empty". */
    val loadFailed: Boolean = false,
) {
    /**
     * A form that never loaded holds five empty strings, and saving PUTs all
     * five — so one failed fetch followed by a tap would erase the profile.
     */
    val canSave: Boolean get() = !isLoading && !loadFailed && !isSaving
}

class ProfileEditViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val session = services.session

    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    // Guards against a recomposition refetching over what the user has typed.
    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        val username = session.username ?: run {
            _state.value = _state.value.copy(isLoading = false, loadFailed = true)
            return
        }
        _state.value = _state.value.copy(isLoading = true, loadFailed = false)
        viewModelScope.launch {
            val response = runCatchingCancellable { client.user(username) }.getOrNull()
            if (response == null) {
                _state.value = _state.value.copy(isLoading = false, loadFailed = true)
                return@launch
            }
            val profile = response.user
            // Same two sources the web client reads, in the same order.
            val titles = (
                response.badges.orEmpty().filter { it.allowTitle == true }.map { it.name } +
                    profile.groups.orEmpty().mapNotNull { it.title }
                )
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            _state.value = ProfileEditState(
                name = profile.name.orEmpty(),
                bio = profile.bioRaw.orEmpty(),
                website = profile.website.orEmpty(),
                location = profile.location.orEmpty(),
                title = profile.title.orEmpty(),
                availableTitles = titles,
                avatarUrl = DiscourseConfig.avatarUrl(profile.avatarTemplate, 240),
                // Same fallback the profile tab uses. Discourse keeps two images —
                // the page header and the user card — and an account that only
                // set the card would otherwise look here as if it had none.
                backgroundUrl = DiscourseConfig.absoluteUrl(
                    profile.profileBackgroundUploadUrl ?: profile.cardBackgroundUploadUrl,
                ),
                canEditBackground = profile.canUploadProfileHeader == true,
                isLoading = false,
            )
            loaded = true
        }
    }

    /**
     * Upload, then point the profile at the resulting URL — Discourse resolves
     * the upload by URL here, not by id the way [uploadAvatar] does.
     */
    fun uploadBackground(bytes: ByteArray, fileName: String, mimeType: String) {
        val username = session.username ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true)
            runCatchingCancellable {
                val upload = client.uploadUserImage(bytes, fileName, mimeType, "profile_background")
                client.updateProfile(username, listOf("profile_background_upload_url" to upload.url.orEmpty()))
                upload
            }
                .onSuccess {
                    _state.value = _state.value.copy(backgroundUrl = DiscourseConfig.absoluteUrl(it.url))
                    ToastCenter.show(R.string.profile_background_updated)
                    session.refresh(force = true)
                }
                .onFailure { ToastCenter.showError(it) }
            _state.value = _state.value.copy(isSaving = false)
        }
    }

    /** An empty URL is how Discourse is told to drop the background. */
    fun removeBackground() {
        val username = session.username ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true)
            runCatchingCancellable { client.updateProfile(username, listOf("profile_background_upload_url" to "")) }
                .onSuccess {
                    _state.value = _state.value.copy(backgroundUrl = null)
                    ToastCenter.show(R.string.profile_background_updated)
                    session.refresh(force = true)
                }
                .onFailure { ToastCenter.showError(it) }
            _state.value = _state.value.copy(isSaving = false)
        }
    }

    fun update(transform: (ProfileEditState) -> ProfileEditState) {
        _state.value = transform(_state.value)
    }

    fun save(onDone: () -> Unit) {
        val username = session.username ?: return
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            _state.value = current.copy(isSaving = true)
            runCatchingCancellable {
                client.updateProfile(
                    username,
                    listOf(
                        "name" to current.name,
                        "bio_raw" to current.bio,
                        "website" to current.website,
                        "location" to current.location,
                        "title" to current.title,
                    ),
                )
            }
                .onSuccess {
                    ToastCenter.show(R.string.profile_edit_saved)
                    session.refresh(force = true)
                    onDone()
                }
                .onFailure { ToastCenter.showError(it) }
            _state.value = _state.value.copy(isSaving = false)
        }
    }

    /** Upload, then pick — Discourse needs both calls to change an avatar. */
    fun uploadAvatar(bytes: ByteArray, fileName: String, mimeType: String) {
        val username = session.username ?: return
        viewModelScope.launch {
            runCatchingCancellable {
                val upload = client.uploadUserImage(bytes, fileName, mimeType, "avatar")
                client.pickAvatar(username, upload.id)
                upload
            }
                .onSuccess {
                    ToastCenter.show(R.string.profile_avatar_updated)
                    session.refresh(force = true)
                    load()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }
}

@Composable
fun ProfileEditScreen(navigator: Navigator) {
    val viewModel: ProfileEditViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    SettingsScaffold(stringResource(R.string.settings_profile), navigator::back, loading = state.isLoading) {
        ProfileEditBody(viewModel, onSaved = navigator::back)
    }
}

/**
 * The editor itself, without chrome: its own page above, and the settings
 * page's 个人资料 tab, which hosts it under the tab bar and stays put after a
 * save rather than closing.
 */
@Composable
internal fun ProfileEditBody(viewModel: ProfileEditViewModel, onSaved: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatchingCancellable {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) viewModel.uploadAvatar(bytes, "avatar.${mime.substringAfter('/')}", mime)
        }
    }

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatchingCancellable {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (bytes != null) viewModel.uploadBackground(bytes, "background.${mime.substringAfter('/')}", mime)
        }
    }

    if (state.loadFailed) {
        // An empty form here is indistinguishable from an empty profile,
        // and saving it would overwrite the real one with blanks.
        EmptyStateView(
            icon = Lucide.CircleAlert,
            title = stringResource(R.string.profile_edit_load_failed),
            detail = stringResource(R.string.profile_edit_load_failed_detail),
            retryLabel = stringResource(R.string.common_retry),
            onRetry = { viewModel.load(force = true) },
        )
        return
    }

    val username = ServiceLocator.get.session.username.orEmpty()
    ProfileHeader(
        avatarUrl = state.avatarUrl,
        letter = state.name.ifEmpty { username }.take(1).uppercase().ifEmpty { "?" },
        name = state.name.ifEmpty { username },
        username = username,
        backgroundUrl = state.backgroundUrl,
        canEditBackground = state.canEditBackground,
        onPickAvatar = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onPickBackground = {
            backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onRemoveBackground = viewModel::removeBackground,
    )

    SettingsSection(title = stringResource(R.string.profile_edit_section)) {
        SettingsTextRow(stringResource(R.string.profile_edit_name), state.name, stringResource(R.string.profile_edit_name_hint)) { value ->
            viewModel.update { it.copy(name = value) }
        }
        SettingsRowDivider()
        // A title is not free text — it has to be one the account has earned.
        val noTitle = stringResource(R.string.profile_edit_title_none)
        if (state.availableTitles.isEmpty()) {
            SettingsValueRow(
                stringResource(R.string.profile_edit_title),
                stringResource(R.string.profile_edit_title_locked),
            )
        } else {
            SettingsPickerRow(
                title = stringResource(R.string.profile_edit_title),
                options = listOf("") + state.availableTitles,
                selected = state.title,
                label = { it.ifEmpty { noTitle } },
                onSelect = { value -> viewModel.update { it.copy(title = value) } },
            )
        }
        SettingsRowDivider()
        SettingsTextRow(stringResource(R.string.profile_edit_bio), state.bio, stringResource(R.string.profile_edit_bio_hint), singleLine = false) { value ->
            viewModel.update { it.copy(bio = value) }
        }
        SettingsRowDivider()
        SettingsTextRow(stringResource(R.string.profile_edit_website), state.website, "https://") { value ->
            viewModel.update { it.copy(website = value) }
        }
        SettingsRowDivider()
        SettingsTextRow(stringResource(R.string.profile_edit_location), state.location, "") { value ->
            viewModel.update { it.copy(location = value) }
        }
    }

    Column(Modifier.padding(horizontal = Space.page, vertical = Space.s4)) {
        FilledAccentButton(
            stringResource(R.string.common_save),
            loading = state.isSaving,
            enabled = state.canSave,
        ) {
            viewModel.save(onSaved)
        }
    }
}

/** The board's numbers: a 120-high cover card, and an 80 avatar hanging 36 below its edge. */
private val CoverHeight = 120.dp
private val AvatarOverlap = 36.dp
private val AvatarSize = 80.dp

/**
 * Cover, avatar, and the ways to change them — the board's 个人资料 header.
 *
 * The avatar is the control, not a preview beside one: tapping the picture is
 * what everyone tries first, and the camera badge says so without spending a
 * row on a button. The cover's buttons sit on the picture itself, bottom
 * right, one dark and one light so at least one reads on any image.
 */
@Composable
private fun ProfileHeader(
    avatarUrl: String?,
    letter: String,
    name: String,
    username: String,
    backgroundUrl: String?,
    canEditBackground: Boolean,
    onPickAvatar: () -> Unit,
    onPickBackground: () -> Unit,
    onRemoveBackground: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 16.dp)) {
        val coverShape = RoundedCornerShape(16.dp)
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(CoverHeight)
                    .clip(coverShape)
                    .background(Nocturne.neutral300),
            ) {
                if (backgroundUrl != null) {
                    AsyncImage(
                        model = backgroundUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Lucide.Image, null, tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.settings2_cover_none), style = Type.body(13), color = Nocturne.textSecondary)
                    }
                }
                if (canEditBackground) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (backgroundUrl != null) {
                            CoverButton(Lucide.Trash2, stringResource(R.string.settings2_cover_remove), dark = true, onClick = onRemoveBackground)
                        }
                        CoverButton(Lucide.Image, stringResource(R.string.settings2_cover_change), dark = false, onClick = onPickBackground)
                    }
                }
            }

            // Laid over the cover's bottom edge rather than offset up, so the space
            // the column reserves is exactly what is drawn.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = CoverHeight - AvatarOverlap),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(AvatarSize)) {
                    // The white ring is the page showing through, so the avatar
                    // reads as lifted off the cover rather than pasted on it.
                    Box(
                        Modifier
                            .size(AvatarSize)
                            .clip(CircleShape)
                            .background(Nocturne.bg)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onPickAvatar),
                    ) {
                        RemoteAvatar(avatarUrl, letter, size = AvatarSize - 8.dp)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Nocturne.bg)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Nocturne.text)
                            .clickable(onClick = onPickAvatar),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Camera, stringResource(R.string.profile_edit_avatar), tint = Nocturne.bg, modifier = Modifier.size(14.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(top = 42.dp)) {
                    Text(name, style = Type.heading(18, FontWeight.Bold), color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (username.isNotEmpty()) {
                        Text("u/$username", style = Type.body(13), color = Nocturne.textSecondary, maxLines = 1)
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings2_media_hint),
            style = Type.body(12),
            color = Nocturne.textSecondary,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** A button laid over the cover image. */
@Composable
private fun CoverButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val press = rememberPressSource()
    Row(
        Modifier
            .pressScale(press)
            .height(36.dp)
            .clip(CircleShape)
            .background(if (dark) Nocturne.text.copy(alpha = 0.72f) else Nocturne.bg.copy(alpha = 0.94f))
            .clickable(interactionSource = press, indication = ripple(), onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (dark) Nocturne.bg else Nocturne.text
        Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
        Text(label, style = Type.body(13, FontWeight.Bold), color = ink)
    }
}
