package com.nodeloc.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Lucide
import com.nodeloc.app.BuildConfig
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ConfirmDialog
import com.nodeloc.app.core.design.EmptyStateView
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.animatedColor
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.model.NodeReadingMode
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.core.network.ServerFeatures
import com.nodeloc.app.core.util.AppLocale
import com.nodeloc.app.core.util.DiscourseFormat
import com.nodeloc.app.feature.feed.RowsSkeleton
import com.nodeloc.app.feature.moderation.ModTopBar
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route
import kotlinx.coroutines.launch

/** Shared chrome for the settings sub-pages: the Material top app bar over a scrolling body. */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    /** The first fetch has not landed. Skeleton rows stand in for the content. */
    loading: Boolean = false,
    /** A write is in flight. The rows already show the new value — this only
     *  says it has not reached the server yet. */
    saving: Boolean = false,
    /** Trailing bar actions: a page's save sits here, top right, as on every Android editor. */
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        ModTopBar(title, onBack, actions = actions)
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

/** The board's five tabs, in its order. */
enum class SettingsTab(val labelRes: Int) {
    Account(R.string.settings2_tab_account),
    Profile(R.string.settings2_tab_profile),
    Privacy(R.string.settings2_tab_privacy),
    Prefs(R.string.settings2_tab_prefs),
    Notify(R.string.settings2_tab_notify),
}

/**
 * Settings, as the 2026-11 board draws it: one page with a large title and
 * five tabs, everything editable in place, mirroring the website's settings
 * page tab for tab. A guest has no account to configure, so they get only
 * what this device stores — appearance — and the about rows.
 */
@Composable
fun SettingsScreen(app: AppViewModel, navigator: Navigator) {
    val isSignedIn by app.isSignedIn.collectAsState()
    val viewModel: AccountSettingsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val tab by viewModel.tab.collectAsState()

    LaunchedEffect(isSignedIn) { if (isSignedIn) viewModel.load() }

    Column(Modifier.fillMaxSize().background(Nocturne.bg)) {
        ModTopBar("", navigator::back)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.common_settings),
                style = Type.heading(24, FontWeight.Bold),
                color = Nocturne.text,
                modifier = Modifier.padding(start = Space.page, end = Space.page, top = 12.dp, bottom = 8.dp),
            )
            if (!isSignedIn) {
                SetPage {
                    PrefsRows(top = 12)
                    AboutRows(navigator)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = Space.page, end = Space.page, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsTab.entries.forEach { entry ->
                        TabPill(stringResource(entry.labelRes), entry == tab) { viewModel.requestTab(entry) }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.divider))

                when {
                    // The profile editor fetches for itself; every other tab
                    // reads the settings bag.
                    tab == SettingsTab.Profile -> ProfileTab(state, viewModel)
                    state.loading && state.user == null -> RowsSkeleton(rows = 6, avatar = false)
                    state.failed -> EmptyStateView(
                        icon = Lucide.CircleAlert,
                        title = stringResource(R.string.settings2_load_failed),
                        detail = stringResource(R.string.profile_edit_load_failed_detail),
                        retryLabel = stringResource(R.string.common_retry),
                        onRetry = { viewModel.load(force = true) },
                    )
                    else -> when (tab) {
                        SettingsTab.Account -> AccountTab(state, viewModel, app, navigator)
                        SettingsTab.Privacy -> PrivacyTab(state, viewModel, navigator)
                        SettingsTab.Prefs -> SetPage { PrefsRows(top = 20) }
                        SettingsTab.Notify -> NotifyTab(state, viewModel, navigator)
                        SettingsTab.Profile -> Unit
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

/** The board's tab: a 44-high capsule, ink when chosen. Taller than a filter chip — these are the page's sections. */
@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val ground by animatedColor(if (selected) Nocturne.text else Nocturne.neutral400, "tabGround")
    val ink by animatedColor(if (selected) Nocturne.bg else Nocturne.text, "tabInk")
    val press = rememberPressSource()
    Box(
        Modifier
            .pressScale(press)
            .height(44.dp)
            .clip(CircleShape)
            .background(ground)
            .semantics { this.selected = selected }
            .clickable(interactionSource = press, indication = ripple(), role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(14, FontWeight.Bold), color = ink, maxLines = 1)
    }
}

// ------------------------------------------------------------------ 账号

@Composable
private fun AccountTab(state: AccountSettingsState, viewModel: AccountSettingsViewModel, app: AppViewModel, navigator: Navigator) {
    val account = state.account
    val user = state.user
    val username = ServiceLocator.get.session.username.orEmpty()
    var dialog by remember { mutableStateOf<AccountDialog?>(null) }

    SetPage {
        SetHeading(stringResource(R.string.settings2_basic), top = 20)
        SetRow(
            stringResource(R.string.settings2_email),
            desc = when {
                !account?.pendingEmail.isNullOrBlank() -> stringResource(R.string.settings2_email_pending, account.pendingEmail)
                state.emailVerified -> stringResource(R.string.settings2_email_verified)
                else -> stringResource(R.string.settings2_email_unverified)
            },
        ) {
            SetValueButton(user?.email.orEmpty()) { dialog = AccountDialog.Email }
        }
        SetRow(stringResource(R.string.settings2_phone)) {
            SetValueButton(account?.phone?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings2_phone_none)) {
                dialog = AccountDialog.Phone
            }
        }
        val hasPassword = account?.hasPassword != false
        SetRow(
            stringResource(R.string.settings2_password),
            desc = if (hasPassword) {
                (account?.passwordChangedAt ?: account?.createdAt)?.let {
                    stringResource(R.string.settings2_password_changed, DiscourseFormat.relative(it))
                }
            } else {
                stringResource(R.string.settings2_password_none)
            },
        ) {
            SetButton(stringResource(if (hasPassword) R.string.settings2_password_change else R.string.settings2_password_set)) {
                if (hasPassword) dialog = AccountDialog.Password else viewModel.sendPasswordSetup()
            }
        }
        // Setting up TOTP is a QR, a code and a set of backup codes — the
        // security page already walks through it, so the switch leads there.
        val twoFactorOn = state.twoFactor?.enabled == true
        val twoFactorLabel = stringResource(R.string.settings2_two_factor)
        SetRow(
            twoFactorLabel,
            desc = if (twoFactorOn) {
                stringResource(R.string.settings2_two_factor_on, state.twoFactor?.backupCodesLeft ?: 0)
            } else {
                stringResource(R.string.settings2_two_factor_off)
            },
        ) {
            SetSwitch(twoFactorOn, twoFactorLabel) { navigator.openSettingsRoute(Route.SettingsSecurity) }
        }
        SetRow(
            stringResource(R.string.settings2_sessions),
            desc = stringResource(R.string.settings2_sessions_d),
            onClick = { navigator.openSettingsRoute(Route.SettingsSecurity) },
        ) {
            Icon(Lucide.ChevronRight, null, tint = Nocturne.textSecondary, modifier = Modifier.size(18.dp))
        }

        if (state.identities.isNotEmpty()) {
            SetHeading(stringResource(R.string.settings2_third_party))
            state.identities.forEach { identity ->
                val label = identity.label.ifBlank { identity.provider }
                val who = (identity.name ?: identity.email)?.takeIf { identity.linked && it.isNotBlank() }
                SetRow(
                    label,
                    desc = if (who != null) {
                        stringResource(R.string.settings2_identity_linked, who, label)
                    } else {
                        stringResource(R.string.settings2_identity, label)
                    },
                ) {
                    if (identity.linked) {
                        SetButton(stringResource(R.string.settings2_disconnect)) { dialog = AccountDialog.Unlink(identity) }
                    } else {
                        // Linking is an OAuth round trip through the provider's
                        // site, which only the web page hosts.
                        SetButton(stringResource(R.string.settings2_connect), SetButtonTone.Primary) {
                            navigator.openBrowser("${DiscourseConfig.BASE_URL}/settings#account")
                        }
                    }
                }
            }
        }

        SetHeading(stringResource(R.string.settings2_data))
        ExportRow(state.export, viewModel, navigator)
        SetRow(
            stringResource(R.string.settings2_delete),
            desc = stringResource(R.string.settings2_delete_detail),
            labelColor = Nocturne.danger,
        ) {
            SetButton(stringResource(R.string.settings2_delete_action), SetButtonTone.Danger) { dialog = AccountDialog.Delete }
        }

        AboutRows(navigator)

        Box(Modifier.padding(top = 28.dp)) {
            SetButton(stringResource(R.string.settings_sign_out), SetButtonTone.Danger) {
                app.signOut()
                navigator.back()
            }
        }
    }

    // Sensitive writes confirm identity with the password; an account that
    // signs in only through a provider has none and types its username.
    val secretField = if (account?.hasPassword != false) {
        SetField(stringResource(R.string.settings2_password_current), password = true)
    } else {
        SetField(stringResource(R.string.settings2_confirm_username, username))
    }
    when (val open = dialog) {
        AccountDialog.Email -> SetFormDialog(
            title = stringResource(R.string.settings2_email_change_title),
            detail = stringResource(R.string.settings2_email_change_detail),
            fields = listOf(SetField(stringResource(R.string.settings2_email_new), keyboard = KeyboardType.Email, maxLength = 254), secretField),
            confirmLabel = stringResource(R.string.settings2_email_send),
            onDismiss = { dialog = null },
        ) { (email, secret) -> viewModel.requestEmailChange(email, secret) { dialog = null } }
        AccountDialog.Phone -> SetFormDialog(
            title = stringResource(R.string.settings2_phone),
            detail = stringResource(R.string.settings2_phone_help),
            fields = listOf(SetField(stringResource(R.string.settings2_phone), account?.phone.orEmpty(), keyboard = KeyboardType.Phone, maxLength = 20, required = false)),
            confirmLabel = stringResource(R.string.common_save),
            onDismiss = { dialog = null },
        ) { (phone) -> viewModel.setPhone(phone) { dialog = null } }
        AccountDialog.Password -> SetFormDialog(
            title = stringResource(R.string.settings2_password_title),
            detail = stringResource(R.string.settings2_password_detail),
            fields = listOf(
                SetField(stringResource(R.string.settings2_password_current), password = true),
                SetField(stringResource(R.string.settings2_password_new), password = true),
            ),
            confirmLabel = stringResource(R.string.settings2_password_change),
            onDismiss = { dialog = null },
        ) { (current, new) -> viewModel.changePassword(current, new) { dialog = null } }
        AccountDialog.Delete -> SetFormDialog(
            title = stringResource(R.string.settings2_delete_title),
            detail = stringResource(R.string.settings2_delete_body),
            fields = listOf(secretField),
            confirmLabel = stringResource(R.string.settings2_delete_confirm),
            danger = true,
            onDismiss = { dialog = null },
        ) { (secret) ->
            viewModel.deleteAccount(secret) {
                dialog = null
                app.signOut()
                navigator.back()
            }
        }
        is AccountDialog.Unlink -> {
            val label = open.identity.label.ifBlank { open.identity.provider }
            ConfirmDialog(
                title = stringResource(R.string.security_unlink_confirm_title, label),
                body = stringResource(R.string.security_unlink_confirm_detail),
                confirmLabel = stringResource(R.string.settings2_disconnect),
                onConfirm = {
                    dialog = null
                    viewModel.unlink(open.identity.provider)
                },
                onDismiss = { dialog = null },
            )
        }
        null -> Unit
    }
}

private sealed interface AccountDialog {
    data object Email : AccountDialog
    data object Phone : AccountDialog
    data object Password : AccountDialog
    data object Delete : AccountDialog
    data class Unlink(val identity: ApiLoginIdentity) : AccountDialog
}

/**
 * The archive's download link is an authenticated API route, and the browser
 * has no session of the app's: the web settings page, where the reader is
 * signed in by cookie, is the place that can actually hand the file over.
 */
@Composable
private fun ExportRow(export: ApiDataExport?, viewModel: AccountSettingsViewModel, navigator: Navigator) {
    val label = stringResource(R.string.settings2_export)
    when (export?.status) {
        "pending" -> SetRow(label, desc = stringResource(R.string.settings2_export_pending)) {
            Text(stringResource(R.string.settings2_export_pending_short), style = Type.body(14), color = Nocturne.textSecondary)
        }
        "ready" -> SetRow(label, desc = stringResource(R.string.settings2_export_ready)) {
            SetButton(stringResource(R.string.settings2_export_download), SetButtonTone.Primary) {
                navigator.openBrowser("${DiscourseConfig.BASE_URL}/settings#account")
            }
        }
        else -> SetRow(
            label,
            desc = stringResource(if (export?.status == "failed") R.string.settings2_export_failed else R.string.settings2_export_detail),
        ) {
            SetButton(stringResource(R.string.settings2_export_apply), onClick = viewModel::requestExport)
        }
    }
}

/** Version, help and terms — the rows the old page's 关于 section held; the board has no place for them, so they close the account tab. */
@Composable
private fun AboutRows(navigator: Navigator) {
    val services = ServiceLocator.get
    val update by services.updates.available.collectAsState()
    SetHeading(stringResource(R.string.settings2_section_about))
    // The running version doubles as the answer to "did the update take?",
    // which is the question this row is usually opened for. "Up to date" is
    // never claimed: the store build cannot know, and the github one only
    // knows after a check has run.
    val version = BuildConfig.VERSION_NAME
    SetRow(
        stringResource(R.string.update_check),
        onClick = { services.updates.check(announce = true) },
    ) {
        Text(
            if (update != null) stringResource(R.string.settings2_update_available, version) else version,
            style = Type.body(14),
            color = Nocturne.textSecondary,
        )
    }
    LinkRow(stringResource(R.string.settings2_help)) { navigator.openBrowser("${DiscourseConfig.BASE_URL}/faq") }
    LinkRow(stringResource(R.string.settings2_terms)) { navigator.openBrowser("${DiscourseConfig.BASE_URL}/tos") }
    LinkRow(stringResource(R.string.settings_web)) { navigator.openBrowser("${DiscourseConfig.BASE_URL}/settings") }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    SetRow(label, onClick = onClick) {
        Icon(Lucide.ChevronRight, null, tint = Nocturne.textSecondary, modifier = Modifier.size(18.dp))
    }
}

// --------------------------------------------------------------- 个人资料

@Composable
private fun ProfileTab(state: AccountSettingsState, viewModel: AccountSettingsViewModel) {
    val profile: ProfileEditViewModel = viewModel()
    LaunchedEffect(Unit) { profile.load() }
    val profileState by profile.state.collectAsState()
    if (profileState.isLoading) {
        RowsSkeleton(rows = 6, avatar = false)
        return
    }
    ProfileEditBody(profile, onSaved = {})
    if (state.loading || state.failed) return
    var editingSignature by remember { mutableStateOf(false) }
    SetPage {
        SetHeading(stringResource(R.string.settings2_showcase), top = 12)
        FlagRow(R.string.settings2_show_nodes, R.string.settings2_show_nodes_d, "show_joined_nodes", state.settings.showJoinedNodes, viewModel) { s, v -> s.copy(showJoinedNodes = v) }
        FlagRow(R.string.settings2_show_online, R.string.settings2_show_online_d, "show_online", state.settings.showOnline, viewModel) { s, v -> s.copy(showOnline = v) }
        FlagRow(R.string.settings2_show_level, R.string.settings2_show_level_d, "show_level", state.settings.showLevel, viewModel) { s, v -> s.copy(showLevel = v) }

        SetHeading(stringResource(R.string.settings2_posts))
        SetRow(stringResource(R.string.settings2_signature), desc = stringResource(R.string.settings2_signature_d)) {
            SetValueButton(state.settings.signature.ifBlank { stringResource(R.string.settings2_signature_empty) }) { editingSignature = true }
        }
    }
    if (editingSignature) {
        SetFormDialog(
            title = stringResource(R.string.settings2_signature),
            detail = stringResource(R.string.settings2_signature_help),
            fields = listOf(SetField(stringResource(R.string.settings2_signature), state.settings.signature, multiline = true, maxLength = 300, required = false)),
            confirmLabel = stringResource(R.string.common_save),
            onDismiss = { editingSignature = false },
        ) { (value) -> viewModel.saveSignature(value) { editingSignature = false } }
    }
}

@Composable
private fun FlagRow(
    label: Int,
    desc: Int,
    wireKey: String,
    value: Boolean,
    viewModel: AccountSettingsViewModel,
    lens: (ApiAccountSettings, Boolean) -> ApiAccountSettings,
) {
    val title = stringResource(label)
    SetRow(title, desc = stringResource(desc)) {
        SetSwitch(value, title) { viewModel.setFlag(wireKey, !value, lens) }
    }
}

// ------------------------------------------------------------------- 隐私

@Composable
private fun PrivacyTab(state: AccountSettingsState, viewModel: AccountSettingsViewModel, navigator: Navigator) {
    val services = ServiceLocator.get
    val blocked by services.blockedUsers.usernames.collectAsState()
    // The count is read off the same set the feed filters with; loading it
    // here is a no-op when the session already did.
    LaunchedEffect(Unit) { services.blockedUsers.load(services.session.username) }
    val policies = listOf(
        "everyone" to stringResource(R.string.settings2_policy_everyone),
        "following" to stringResource(R.string.settings2_policy_following),
        "nobody" to stringResource(R.string.settings2_policy_nobody),
    )
    SetPage {
        SetHeading(stringResource(R.string.settings2_interaction), top = 20)
        FlagRow(R.string.settings2_allow_follow, R.string.settings2_allow_follow_d, "allow_follow", state.settings.allowFollow, viewModel) { s, v -> s.copy(allowFollow = v) }
        val messageTitle = stringResource(R.string.settings2_who_message)
        SetRow(messageTitle, desc = stringResource(R.string.settings2_who_message_d)) {
            SetChoiceButton(messageTitle, policies, state.settings.messagePolicy, viewModel::setMessagePolicy)
        }
        val mentionTitle = stringResource(R.string.settings2_who_mention)
        SetRow(mentionTitle, desc = stringResource(R.string.settings2_who_mention_d)) {
            SetChoiceButton(mentionTitle, policies, state.settings.mentionPolicy, viewModel::setMentionPolicy)
        }

        SetHeading(stringResource(R.string.settings2_visibility))
        FlagRow(R.string.settings2_search_index, R.string.settings2_search_index_d, "search_index", state.settings.searchIndex, viewModel) { s, v -> s.copy(searchIndex = v) }
        FlagRow(R.string.settings2_show_upvotes, R.string.settings2_show_upvotes_d, "show_upvotes", state.settings.showUpvotes, viewModel) { s, v -> s.copy(showUpvotes = v) }

        SetHeading(stringResource(R.string.settings2_blocking))
        SetRow(stringResource(R.string.settings_blocked), desc = stringResource(R.string.settings2_blocked_d)) {
            SetValueButton(
                if (blocked.isEmpty()) stringResource(R.string.settings2_none) else stringResource(R.string.settings2_people_count, blocked.size),
            ) { navigator.openSettingsRoute(Route.SettingsBlocked) }
        }
        // Here because it is what it is: a choice about how much of your
        // phone your posts say.
        if (ServerFeatures.POST_SOURCE) {
            LinkRow(stringResource(R.string.post_source_title)) { navigator.openSettingsRoute(Route.SettingsPostSource) }
        }
    }
}

// ------------------------------------------------------------------- 偏好

/**
 * What this device stores. The account's own display preferences (node
 * tint, list paging, NSFW folding, signatures) shape the website's pages and
 * nothing in this app reads them, so they are not offered here.
 */
@Composable
private fun PrefsRows(top: Int) {
    val services = ServiceLocator.get
    val preferences = services.preferences
    val scope = rememberCoroutineScope()
    val colorMode by remember { preferences.colorMode }.collectAsState(initial = "system")
    val signedIn by services.session.isSignedIn.collectAsState()
    val readingMode by remember { preferences.readingMode(services.session.isSignedIn) }
        .collectAsState(initial = NodeReadingMode.default(signedIn))
    val defaultHome by remember { preferences.defaultHome }.collectAsState(initial = "hot")

    SetHeading(stringResource(R.string.settings2_section_display), top = top)
    val themeTitle = stringResource(R.string.settings2_appearance)
    SetRow(themeTitle) {
        SetChoiceButton(
            themeTitle,
            listOf(
                "system" to stringResource(R.string.settings2_appearance_system),
                "light" to stringResource(R.string.settings2_appearance_light),
                "dark" to stringResource(R.string.settings2_appearance_dark),
            ),
            colorMode,
        ) { value -> scope.launch { preferences.setColorMode(value) } }
    }
    val language by AppLocale.tag.collectAsState()
    val languageTitle = stringResource(R.string.settings_language)
    SetRow(languageTitle) {
        SetChoiceButton(
            languageTitle,
            listOf(LANGUAGE_SYSTEM to stringResource(R.string.settings_language_system)) + AppLocale.endonyms.toList(),
            language ?: LANGUAGE_SYSTEM,
        ) { value -> AppLocale.choose(value.takeUnless { it == LANGUAGE_SYSTEM }) }
    }
    val listTitle = stringResource(R.string.settings2_list_view)
    SetRow(listTitle, desc = stringResource(R.string.settings2_list_view_d)) {
        SetChoiceButton(listTitle, NodeReadingMode.entries.map { it to stringResource(it.labelRes) }, readingMode) { mode ->
            scope.launch { preferences.selectReadingMode(mode) }
        }
    }
    val homeTitle = stringResource(R.string.settings_default_home)
    SetRow(homeTitle) {
        SetChoiceButton(
            homeTitle,
            listOf("hot", "rising", "latest", "featured", "following", "best").map { it to stringResource(homeLabel(it)) },
            defaultHome,
        ) { value -> scope.launch { preferences.setDefaultHome(value) } }
    }
}

private const val LANGUAGE_SYSTEM = "system"

@androidx.annotation.StringRes
private fun homeLabel(value: String): Int = when (value) {
    "best" -> R.string.sort_best
    "hot" -> R.string.sort_hot
    "rising" -> R.string.sort_rising
    "featured" -> R.string.sort_featured
    "following" -> R.string.sort_following
    else -> R.string.sort_latest
}
