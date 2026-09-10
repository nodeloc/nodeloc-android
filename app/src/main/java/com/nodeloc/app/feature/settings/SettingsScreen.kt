package com.nodeloc.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SettingsNavRow
import com.nodeloc.app.core.design.SettingsRowDivider
import com.nodeloc.app.core.design.SettingsSection
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route

/** Shared chrome for every settings page: back button plus a large title. */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    /** The first fetch has not landed. Skeleton rows stand in for the content. */
    loading: Boolean = false,
    /** A write is in flight. The rows already show the new value — this only
     *  says it has not reached the server yet. */
    saving: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Nocturne.bg)
            // `adjustResize` in the manifest does nothing here: enableEdgeToEdge
            // takes the decor out of fitsSystemWindows, so the window keeps its
            // full height and the keyboard is drawn over the page rather than
            // shortening it. The scroll range then ends where the content ends,
            // which is somewhere underneath the keyboard — the profile page's
            // bio field could be typed into but never brought into view.
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = onBack)
        }
        // Preference writes are optimistic, so blocking the page would be a lie
        // about what already happened; a hairline is the honest amount of noise.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (saving) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Nocturne.accent,
                    trackColor = Color.Transparent,
                    strokeCap = StrokeCap.Butt,
                    gapSize = 0.dp,
                )
            }
        }
        Text(
            title,
            style = Type.heading(32, FontWeight.Bold),
            color = Nocturne.text,
            modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s4),
        )
        // The title stays put while the body loads: the page identity is known
        // immediately, only its contents are not.
        if (loading) RowsSkeleton(rows = 6, avatar = false) else content()
        Box(Modifier.padding(bottom = 40.dp))
    }
}

@Composable
fun SettingsScreen(app: AppViewModel, navigator: Navigator) {
    val isSignedIn by app.isSignedIn.collectAsState()

    SettingsScaffold(stringResource(R.string.common_settings), navigator::back) {
        if (isSignedIn) {
            SettingsSection(title = stringResource(R.string.settings_account)) {
                SettingsNavRow(stringResource(R.string.settings_profile)) { navigator.openSettingsRoute(Route.ProfileEdit) }
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.settings_associated)) { navigator.openSettingsRoute(Route.SettingsAssociated) }
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.settings_security)) { navigator.openSettingsRoute(Route.SettingsSecurity) }
            }
        }

        SettingsSection(title = stringResource(R.string.settings_preferences)) {
            // Interface is this app's own state, so a guest can set it. The
            // rest are account preferences the server serializes for their
            // owner: as a guest they would show invented defaults and fail
            // to save.
            SettingsNavRow(stringResource(R.string.settings_interface)) { navigator.openSettingsRoute(Route.SettingsInterface) }
            if (isSignedIn) {
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.settings_notifications)) { navigator.openSettingsRoute(Route.SettingsNotifications) }
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.settings_email)) { navigator.openSettingsRoute(Route.SettingsEmail) }
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.settings_privacy)) { navigator.openSettingsRoute(Route.SettingsPrivacy) }
                SettingsNavRow(stringResource(R.string.settings_blocked)) { navigator.openSettingsRoute(Route.SettingsBlocked) }
                SettingsRowDivider()
                // Under preferences and next to privacy, which is what it is:
                // a choice about how much of your phone your posts say.
                SettingsNavRow(stringResource(R.string.post_source_title)) { navigator.openSettingsRoute(Route.SettingsPostSource) }
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.settings_web)) {
                    navigator.openBrowser("${com.nodeloc.app.core.network.DiscourseConfig.BASE_URL}/my/preferences")
                }
            }
        }

        SettingsSection(title = stringResource(R.string.settings_account_section)) {
            if (isSignedIn) {
                SettingsNavRow(stringResource(R.string.settings_push)) { navigator.openSettingsRoute(Route.SettingsPush) }
                SettingsRowDivider()
            }
            // The running version doubles as the answer to "did the update
            // take?", which is the question this row is usually opened for.
            SettingsNavRow(
                stringResource(R.string.update_check),
                detail = BuildConfig.VERSION_NAME,
            ) { ServiceLocator.get.updates.check(announce = true) }
            if (isSignedIn) {
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.settings_sign_out), tint = Nocturne.danger) {
                    app.signOut()
                    navigator.back()
                }
            }
        }
    }
}
