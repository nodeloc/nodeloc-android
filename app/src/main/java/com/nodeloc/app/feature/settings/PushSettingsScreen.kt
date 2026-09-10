package com.nodeloc.app.feature.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.SettingsNavRow
import com.nodeloc.app.core.design.SettingsRowDivider
import com.nodeloc.app.core.design.SettingsSection
import com.nodeloc.app.core.design.SettingsToggleRow
import com.nodeloc.app.core.store.AppPreferences
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PushSettingsViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val preferences = services.preferences
    private val push = services.pushRepository

    val settings: StateFlow<AppPreferences.PushSettings> = preferences.pushSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.PushSettings())

    fun hasSystemPermission() = push.hasSystemPermission()

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { push.setEnabled(enabled) }
    }

    fun update(transform: (AppPreferences.PushSettings) -> AppPreferences.PushSettings) {
        viewModelScope.launch {
            preferences.setPushSettings(transform(settings.value))
            // The server holds these too, so silencing a category stops the
            // delivery rather than only the banner it would have drawn.
            push.onCategoriesChanged()
        }
    }
}

/**
 * Push settings.
 *
 * There is no push server: Discourse only relays to its own app, so delivery is
 * a background poll. The master switch asks for the system permission first,
 * because without it nothing this page offers can actually appear.
 */
@Composable
fun PushSettingsScreen(navigator: Navigator) {
    val viewModel: PushSettingsViewModel = viewModel()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(viewModel.hasSystemPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        viewModel.setEnabled(granted)
    }

    LaunchedEffect(Unit) { permissionGranted = viewModel.hasSystemPermission() }

    SettingsScaffold(stringResource(R.string.settings_push), navigator::back) {
        SettingsSection(
            footer = if (!permissionGranted) {
                stringResource(R.string.push_permission_footer)
            } else {
                stringResource(R.string.push_filter_footer)
            },
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.push_enable),
                detail = stringResource(R.string.push_enable_detail),
                checked = settings.enabled && permissionGranted,
            ) { value ->
                if (value && !permissionGranted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        permissionGranted = true
                        viewModel.setEnabled(true)
                    }
                } else {
                    viewModel.setEnabled(value)
                }
            }
            if (!permissionGranted) {
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.push_open_system_settings)) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            },
                        )
                    }
                }
            }
        }

        SettingsSection(title = stringResource(R.string.push_types)) {
            SettingsToggleRow(
                title = stringResource(R.string.push_replies),
                detail = stringResource(R.string.push_replies_detail),
                checked = settings.replies,
                enabled = settings.enabled,
            ) { value -> viewModel.update { it.copy(replies = value) } }
            SettingsRowDivider()
            SettingsToggleRow(
                title = stringResource(R.string.push_likes),
                detail = stringResource(R.string.push_likes_detail),
                checked = settings.likes,
                enabled = settings.enabled,
            ) { value -> viewModel.update { it.copy(likes = value) } }
            SettingsRowDivider()
            SettingsToggleRow(
                title = stringResource(R.string.push_messages),
                detail = stringResource(R.string.push_messages_detail),
                checked = settings.messages,
                enabled = settings.enabled,
            ) { value -> viewModel.update { it.copy(messages = value) } }
            SettingsRowDivider()
            SettingsToggleRow(
                title = stringResource(R.string.push_other),
                detail = stringResource(R.string.push_other_detail),
                checked = settings.other,
                enabled = settings.enabled,
            ) { value -> viewModel.update { it.copy(other = value) } }
        }
    }
}
