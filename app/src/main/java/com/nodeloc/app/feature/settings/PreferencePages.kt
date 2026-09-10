package com.nodeloc.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.SettingsPickerRow
import com.nodeloc.app.core.design.SettingsRowDivider
import com.nodeloc.app.core.design.SettingsSection
import com.nodeloc.app.core.design.SettingsToggleRow
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.model.UserPreferences
import com.nodeloc.app.core.store.EmailLevel
import com.nodeloc.app.core.store.InterfaceColorMode
import com.nodeloc.app.core.store.LikeNotificationFrequency
import com.nodeloc.app.core.store.NewTopicDuration
import com.nodeloc.app.core.store.PreviousRepliesLevel
import com.nodeloc.app.core.store.TextSize
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backing store for every preference page. */
class PreferencesViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val repository = services.userPreferences
    private val appPreferences = services.preferences

    val preferences: StateFlow<UserPreferences> = repository.preferences

    /** True while a preference write is in flight. */
    val isSaving: StateFlow<Boolean> = repository.isSaving

    /**
     * True until the first fetch lands. Owned here, not by the repository, so it
     * is set the moment [load] is called — the repository would only flip it
     * once its coroutine ran, and the pages would render one frame of default
     * switch positions first.
     */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val readingMode: StateFlow<NodeReadingMode> = appPreferences.readingMode(services.session.isSignedIn)
        // The same fallback as the flow's, so a guest is not shown one frame
        // of the signed-in default before the stored value arrives.
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            NodeReadingMode.default(services.session.isSignedIn.value),
        )

    val colorMode: StateFlow<String> = appPreferences.colorMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")

    val defaultHome: StateFlow<String> = appPreferences.defaultHome
        .stateIn(viewModelScope, SharingStarted.Eagerly, "latest")

    fun load() {
        _isLoading.value = true
        viewModelScope.launch {
            repository.load()
            _isLoading.value = false
        }
    }

    fun setBoolean(wireKey: String, value: Boolean, mutate: (UserPreferences, Boolean) -> UserPreferences) {
        viewModelScope.launch { repository.saveBoolean(wireKey, value, mutate) }
    }

    fun setInt(wireKey: String, value: Int, mutate: (UserPreferences, Int) -> UserPreferences) {
        viewModelScope.launch { repository.saveInt(wireKey, value, mutate) }
    }

    fun setReadingMode(mode: NodeReadingMode) {
        viewModelScope.launch { appPreferences.selectReadingMode(mode) }
    }

    fun setColorMode(value: String) {
        viewModelScope.launch { appPreferences.setColorMode(value) }
    }

    fun setDefaultHome(value: String) {
        viewModelScope.launch { appPreferences.setDefaultHome(value) }
    }
}

@Composable
fun InterfaceSettingsScreen(navigator: Navigator) {
    val viewModel: PreferencesViewModel = viewModel()
    val preferences by viewModel.preferences.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val readingMode by viewModel.readingMode.collectAsState()
    val colorMode by viewModel.colorMode.collectAsState()
    val defaultHome by viewModel.defaultHome.collectAsState()


    LaunchedEffect(Unit) { viewModel.load() }

    SettingsScaffold(stringResource(R.string.settings_interface), navigator::back, loading = isLoading, saving = isSaving) {
        SettingsSection(title = stringResource(R.string.settings_appearance)) {
            SettingsPickerRow(
                title = stringResource(R.string.settings_color_mode),
                options = listOf("system", "light", "dark"),
                selected = colorMode,
                label = { stringResource(InterfaceColorMode.from(colorModeValue(it)).labelRes) },
                onSelect = { value ->
                    viewModel.setColorMode(value)
                    // Also mirrored to the account so the web client agrees.
                    viewModel.setInt("interface_color_mode", colorModeValue(value)) { prefs, _ -> prefs }
                },
            )
            SettingsRowDivider()
            SettingsPickerRow(
                title = stringResource(R.string.settings_text_size),
                options = TextSize.ordered,
                selected = TextSize.from(preferences.textSize?.toIntOrNull()),
                label = { stringResource(it.labelRes) },
                // Synced to the server only — the app's own type scale is
                // fixed so dense reader layouts stay predictable — but the row
                // still has to show the choice. Returning `prefs` unchanged
                // meant the value never moved until the process restarted.
                onSelect = { size ->
                    viewModel.setInt("text_size", size.value) { prefs, value ->
                        prefs.copy(textSize = value.toString())
                    }
                },
            )
            SettingsRowDivider()
            SettingsPickerRow(
                title = stringResource(R.string.feed_reading_mode),
                options = NodeReadingMode.entries,
                selected = readingMode,
                label = { stringResource(it.labelRes) },
                onSelect = viewModel::setReadingMode,
            )
            SettingsRowDivider()
            SettingsPickerRow(
                title = stringResource(R.string.settings_default_home),
                options = listOf("best", "latest", "hot", "top"),
                selected = defaultHome,
                label = { home -> stringResource(homeLabel(home)) },
                onSelect = viewModel::setDefaultHome,
            )
        }

        SettingsSection(title = stringResource(R.string.settings_other)) {
            SettingsToggleRow(
                title = stringResource(R.string.settings_external_links),
                checked = preferences.externalLinksInNewTab == true,
            ) { value ->
                viewModel.setBoolean("external_links_in_new_tab", value) { prefs, v ->
                    prefs.copy(externalLinksInNewTab = v)
                }
            }
            SettingsRowDivider()
            SettingsToggleRow(
                title = stringResource(R.string.settings_quoting),
                checked = preferences.enableQuoting != false,
            ) { value ->
                viewModel.setBoolean("enable_quoting", value) { prefs, v -> prefs.copy(enableQuoting = v) }
            }
        }
    }
}

private fun colorModeValue(raw: String): Int = when (raw) {
    "light" -> InterfaceColorMode.Light.value
    "dark" -> InterfaceColorMode.Dark.value
    else -> InterfaceColorMode.Auto.value
}

@androidx.annotation.StringRes
private fun homeLabel(value: String): Int = when (value) {
    "best" -> R.string.sort_best
    "top" -> R.string.sort_top
    "hot" -> R.string.sort_hot
    else -> R.string.sort_latest
}

@Composable
fun NotificationPreferencesScreen(navigator: Navigator) {
    val viewModel: PreferencesViewModel = viewModel()
    val preferences by viewModel.preferences.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()


    LaunchedEffect(Unit) { viewModel.load() }

    SettingsScaffold(stringResource(R.string.settings_notifications), navigator::back, loading = isLoading, saving = isSaving) {
        SettingsSection(title = stringResource(R.string.settings_in_app_notifications)) {
            SettingsPickerRow(
                title = stringResource(R.string.settings_like_frequency),
                options = LikeNotificationFrequency.entries,
                selected = LikeNotificationFrequency.from(preferences.likeNotificationFrequency),
                label = { stringResource(it.labelRes) },
                onSelect = { option ->
                    viewModel.setInt("like_notification_frequency", option.value) { prefs, value ->
                        prefs.copy(likeNotificationFrequency = value)
                    }
                },
            )
            SettingsRowDivider()
            SettingsPickerRow(
                title = stringResource(R.string.settings_new_topic_duration),
                options = NewTopicDuration.entries,
                selected = NewTopicDuration.from(preferences.newTopicDurationMinutes),
                label = { stringResource(it.labelRes) },
                onSelect = { option ->
                    viewModel.setInt("new_topic_duration_minutes", option.minutes) { prefs, value ->
                        prefs.copy(newTopicDurationMinutes = value)
                    }
                },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title = stringResource(R.string.settings_track_on_reply),
                checked = preferences.notificationLevelWhenReplying != null &&
                    preferences.notificationLevelWhenReplying!! >= 2,
            ) { value ->
                viewModel.setInt("notification_level_when_replying", if (value) 2 else 1) { prefs, level ->
                    prefs.copy(notificationLevelWhenReplying = level)
                }
            }
        }
    }
}

@Composable
fun EmailSettingsScreen(navigator: Navigator) {
    val viewModel: PreferencesViewModel = viewModel()
    val preferences by viewModel.preferences.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()


    LaunchedEffect(Unit) { viewModel.load() }

    SettingsScaffold(stringResource(R.string.settings_email), navigator::back, loading = isLoading, saving = isSaving) {
        SettingsSection(title = stringResource(R.string.settings_email_notifications)) {
            SettingsPickerRow(
                title = stringResource(R.string.settings_email_replies),
                options = EmailLevel.entries,
                selected = EmailLevel.from(preferences.emailLevel),
                label = { stringResource(it.labelRes) },
                onSelect = { option ->
                    viewModel.setInt("email_level", option.value) { prefs, value -> prefs.copy(emailLevel = value) }
                },
            )
            SettingsRowDivider()
            SettingsPickerRow(
                title = stringResource(R.string.settings_email_messages),
                options = EmailLevel.entries,
                selected = EmailLevel.from(preferences.emailMessagesLevel),
                label = { stringResource(it.labelRes) },
                onSelect = { option ->
                    viewModel.setInt("email_messages_level", option.value) { prefs, value ->
                        prefs.copy(emailMessagesLevel = value)
                    }
                },
            )
            SettingsRowDivider()
            SettingsPickerRow(
                title = stringResource(R.string.settings_email_previous),
                options = PreviousRepliesLevel.entries,
                selected = PreviousRepliesLevel.from(preferences.emailPreviousReplies),
                label = { stringResource(it.labelRes) },
                onSelect = { option ->
                    viewModel.setInt("email_previous_replies", option.value) { prefs, value ->
                        prefs.copy(emailPreviousReplies = value)
                    }
                },
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title = stringResource(R.string.settings_email_digest),
                checked = preferences.emailDigests == true,
            ) { value ->
                viewModel.setBoolean("email_digests", value) { prefs, v -> prefs.copy(emailDigests = v) }
            }
        }
    }
}

@Composable
fun PrivacySettingsScreen(navigator: Navigator) {
    val viewModel: PreferencesViewModel = viewModel()
    val preferences by viewModel.preferences.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()


    LaunchedEffect(Unit) { viewModel.load() }

    SettingsScaffold(stringResource(R.string.settings_privacy), navigator::back, loading = isLoading, saving = isSaving) {
        SettingsSection {
            SettingsToggleRow(
                title = stringResource(R.string.settings_hide_profile),
                checked = preferences.hideProfile == true,
            ) { value ->
                viewModel.setBoolean("hide_profile", value) { prefs, v -> prefs.copy(hideProfile = v) }
            }
            SettingsRowDivider()
            SettingsToggleRow(
                title = stringResource(R.string.settings_hide_presence),
                checked = preferences.hidePresence == true,
            ) { value ->
                viewModel.setBoolean("hide_presence", value) { prefs, v -> prefs.copy(hidePresence = v) }
            }
        }
    }
}
