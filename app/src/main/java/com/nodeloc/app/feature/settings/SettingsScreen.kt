package com.nodeloc.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.LayoutList
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.SunMoon
import com.composables.icons.lucide.User
import com.composables.icons.lucide.UserX
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.ServerFeatures
import com.nodeloc.app.core.store.AppPreferences
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModTopBar
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route

/** Shared chrome for every settings page: the Material top app bar over a scrolling body. */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    /** The first fetch has not landed. Skeleton rows stand in for the content. */
    loading: Boolean = false,
    /** A write is in flight. The rows already show the new value — this only
     *  says it has not reached the server yet. */
    saving: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        ModTopBar(title, onBack)
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
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // `adjustResize` in the manifest does nothing here: enableEdgeToEdge
                // takes the decor out of fitsSystemWindows, so the window keeps its
                // full height and the keyboard is drawn over the page rather than
                // shortening it. The scroll range then ends where the content ends,
                // which is somewhere underneath the keyboard — the profile page's
                // bio field could be typed into but never brought into view.
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            // The bar stays put while the body loads: the page identity is
            // known immediately, only its contents are not.
            if (loading) RowsSkeleton(rows = 6, avatar = false) else content()
            Spacer(Modifier.height(40.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
fun SettingsScreen(app: AppViewModel, navigator: Navigator) {
    val services = ServiceLocator.get
    val isSignedIn by app.isSignedIn.collectAsState()
    val preferences = services.preferences
    val colorMode by remember { preferences.colorMode }.collectAsState(initial = "system")
    val readingMode by remember { preferences.readingMode(services.session.isSignedIn) }
        .collectAsState(initial = NodeReadingMode.default(isSignedIn))
    val push by remember { preferences.pushSettings }.collectAsState(initial = AppPreferences.PushSettings())
    val blocked by services.blockedUsers.usernames.collectAsState()
    val update by services.updates.available.collectAsState()

    // The count on "blocked people" is read off the same set the feed filters
    // with; loading it here is a no-op when the session already did.
    LaunchedEffect(isSignedIn) {
        if (isSignedIn) services.blockedUsers.load(services.session.username)
    }

    SettingsScaffold(stringResource(R.string.common_settings), navigator::back) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isSignedIn) {
                SettingsGroup(stringResource(R.string.settings2_section_account)) {
                    ListRow(Lucide.User, stringResource(R.string.settings_profile), stringResource(R.string.settings2_profile_detail)) {
                        navigator.openSettingsRoute(Route.ProfileEdit)
                    }
                    ListRow(Lucide.ShieldCheck, stringResource(R.string.settings2_security)) {
                        navigator.openSettingsRoute(Route.SettingsSecurity)
                    }
                    ListRow(Lucide.Link, stringResource(R.string.settings2_associated)) {
                        navigator.openSettingsRoute(Route.SettingsAssociated)
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings2_section_display)) {
                // Interface is this app's own state, so a guest can set it. The
                // rest are account preferences the server serializes for their
                // owner: as a guest they would show invented defaults and fail
                // to save.
                ListRow(Lucide.SunMoon, stringResource(R.string.settings2_appearance), stringResource(colorModeLabel(colorMode))) {
                    navigator.openSettingsRoute(Route.SettingsInterface)
                }
                ListRow(Lucide.LayoutList, stringResource(R.string.settings2_list_view), stringResource(readingMode.labelRes)) {
                    navigator.openSettingsRoute(Route.SettingsInterface)
                }
            }

            if (isSignedIn) {
                SettingsGroup(stringResource(R.string.settings2_section_notify)) {
                    ListRow(
                        Lucide.BellRing,
                        stringResource(R.string.settings_push),
                        stringResource(if (push.enabled) R.string.settings2_on else R.string.settings2_off),
                    ) { navigator.openSettingsRoute(Route.SettingsPush) }
                    ListRow(Lucide.Bell, stringResource(R.string.settings_notifications)) {
                        navigator.openSettingsRoute(Route.SettingsNotifications)
                    }
                    ListRow(Lucide.Mail, stringResource(R.string.settings_email)) {
                        navigator.openSettingsRoute(Route.SettingsEmail)
                    }
                    ListRow(Lucide.EyeOff, stringResource(R.string.settings_privacy)) {
                        navigator.openSettingsRoute(Route.SettingsPrivacy)
                    }
                    ListRow(
                        Lucide.UserX,
                        stringResource(R.string.settings_blocked),
                        blocked.size.takeIf { it > 0 }?.let { stringResource(R.string.settings2_people_count, it) },
                    ) { navigator.openSettingsRoute(Route.SettingsBlocked) }
                    // Next to privacy, which is what it is: a choice about how
                    // much of your phone your posts say.
                    if (ServerFeatures.POST_SOURCE) {
                        ListRow(Lucide.Smartphone, stringResource(R.string.post_source_title)) {
                            navigator.openSettingsRoute(Route.SettingsPostSource)
                        }
                    }
                    ListRow(Lucide.Globe, stringResource(R.string.settings_web)) {
                        navigator.openBrowser("${DiscourseConfig.BASE_URL}/my/preferences")
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings2_section_about)) {
                // The running version doubles as the answer to "did the update
                // take?", which is the question this row is usually opened for.
                // "Up to date" is never claimed: the store build cannot know,
                // and the github one only knows after a check has run.
                val version = BuildConfig.VERSION_NAME
                ListRow(
                    Lucide.RefreshCw,
                    stringResource(R.string.update_check),
                    if (update != null) stringResource(R.string.settings2_update_available, version) else version,
                    chevron = false,
                ) { services.updates.check(announce = true) }
                ListRow(Lucide.CircleHelp, stringResource(R.string.settings2_help)) {
                    navigator.openBrowser("${DiscourseConfig.BASE_URL}/faq")
                }
                ListRow(Lucide.FileText, stringResource(R.string.settings2_terms)) {
                    navigator.openBrowser("${DiscourseConfig.BASE_URL}/tos")
                }
            }

            if (isSignedIn) {
                ListRow(Lucide.LogOut, stringResource(R.string.settings_sign_out), tint = Nocturne.danger, chevron = false) {
                    app.signOut()
                    navigator.back()
                }
            }
        }
    }
}

@androidx.annotation.StringRes
private fun colorModeLabel(raw: String): Int = when (raw) {
    "light" -> R.string.settings2_appearance_light
    "dark" -> R.string.settings2_appearance_dark
    else -> R.string.settings2_appearance_system
}

/** A list section: the design's accent-green label, then flat 56-high rows. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = Type.body(14, FontWeight.Medium),
            color = Nocturne.accent,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        content()
    }
}

/** Material list row: leading icon, title, the current value on the right. */
@Composable
private fun ListRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    tint: Color? = null,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = tint ?: Nocturne.textSecondary, modifier = Modifier.size(24.dp))
        Text(
            title,
            style = Type.body(16),
            color = tint ?: Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(value, style = Type.body(14), color = Nocturne.textSecondary, maxLines = 1)
        }
        if (chevron) {
            Icon(Lucide.ChevronRight, null, tint = Nocturne.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}
