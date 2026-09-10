package com.nodeloc.app.feature.auth

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.HeaderIconButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLogo
import com.nodeloc.app.core.design.Radius
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.AuthProvider
import com.nodeloc.app.core.network.SignupResult
import com.nodeloc.app.feature.nav.AppViewModel
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route

/**
 * The whole auth flow lives in its own nested graph, so signing in never
 * unwinds the stack the user came from — they land back exactly where the gate
 * appeared.
 */
@Composable
fun AuthNavHost(app: AppViewModel, navigator: Navigator, startAtSignup: Boolean = false) {
    val controller = rememberNavController()
    val viewModel: AuthViewModel = viewModel()

    NavHost(
        navController = controller,
        startDestination = if (startAtSignup) Route.SignupEmail else Route.AuthLogin,
    ) {
        composable<Route.AuthLogin> {
            val signedIn = {
                app.refreshSession()
                app.refreshBadge()
                navigator.finishAuth()
            }
            // The provider's page is an overlay on this screen rather than a
            // destination of its own: it is a sheet, and a sheet with nothing
            // behind it is just a screen with a gap at the top.
            var social by remember { mutableStateOf<String?>(null) }
            Box(Modifier.fillMaxSize()) {
                LoginScreen(
                    viewModel = viewModel,
                    onClose = navigator::back,
                    onSignedIn = signedIn,
                    onSignup = { controller.navigate(Route.SignupEmail) },
                    onSocial = { social = it },
                )
                social?.let { provider ->
                    SocialLoginSheet(provider) { ok ->
                        social = null
                        if (ok) signedIn()
                    }
                }
            }
        }
        composable<Route.SignupEmail> {
            SignupEmailScreen(viewModel, controller)
        }
        composable<Route.SignupUsername> {
            SignupUsernameScreen(viewModel, controller)
        }
        composable<Route.SignupPassword> {
            SignupPasswordScreen(viewModel, controller)
        }
        composable<Route.SignupGender> {
            SignupGenderScreen(viewModel, controller)
        }
        composable<Route.SignupInterests> {
            SignupInterestsScreen(
                viewModel = viewModel,
                controller = controller,
                onSignedIn = {
                    app.refreshSession()
                    app.refreshBadge()
                    navigator.finishAuth()
                },
            )
        }
        composable<Route.SignupActivation> { entry ->
            ActivationScreen(entry.toRoute<Route.SignupActivation>().email) {
                // Leaves the whole flow rather than popping to the login form:
                // entered at the signup step that form is not on this stack at
                // all, so the pop found nothing and the screen was a dead end
                // reachable only by system back. There is nothing to do here
                // either way until the email link is followed.
                navigator.finishAuth()
            }
        }
    }
}

@Composable
private fun AuthScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    /**
     * The step's one button. Pinned above the keyboard rather than left at the
     * end of the fields: on a short screen it was the first thing the keyboard
     * covered, which on a login form is the only control that matters.
     */
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The keyboard inset belongs to the container, with the scroll inside it:
    // that is what shrinks the scrolling viewport, and only a viewport that
    // shrinks can bring a focused field out from under the keyboard. Applied
    // to the scroller itself it merely shifts the contents, and the field
    // stays covered. `adjustResize` in the manifest cannot stand in for it —
    // `enableEdgeToEdge` turns off the decor fitting it works through.
    Column(
        Modifier
            .fillMaxSize()
            .background(Nocturne.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Space.page, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                HeaderIconButton(Lucide.ArrowLeft, stringResource(R.string.common_back), onClick = onBack)
            }
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            // The wordmark, not `NodelocLoader` — that one is the app's
            // spinner, and above a form it read as a screen that never
            // finished loading.
            NodelocLogo(height = 30.dp)
            Text(title, style = Type.heading(26, FontWeight.Bold), color = Nocturne.text)
            content()
            // Enough that the last field can clear the pinned button when the
            // keyboard brings it into view.
            Spacer(Modifier.height(Space.s4))
        }
        if (action != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Nocturne.bg)
                    .padding(horizontal = 24.dp)
                    .padding(top = Space.s3, bottom = Space.s6),
            ) {
                action()
            }
        } else {
            Spacer(Modifier.height(Space.s6))
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AuthField(
    label: String,
    value: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    contentType: ContentType? = null,
    focusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    // Focusing a field only guarantees the caret is revealed, which leaves the
    // field's own edges tucked under the keyboard or the pinned button; the
    // whole label-plus-field rect has to be asked for, with a margin.
    val bringIntoView = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var focused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val slack = with(density) { 16.dp.toPx() }
    // Keyed on the keyboard's height as well as the focus, because focus
    // arrives before the keyboard has finished sliding up: a single request
    // aims at a viewport that is still shrinking under it and lands short.
    // Every frame of the animation re-aims, and the last one settles right.
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(focused, imeBottom, size) {
        if (focused && size != IntSize.Zero) {
            bringIntoView.bringIntoView(
                Rect(0f, 0f, size.width.toFloat(), size.height + slack),
            )
        }
    }
    Column(
        Modifier
            .onSizeChanged { size = it }
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focused = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = Type.body(12), color = Nocturne.muted(0.55f))
        FieldSurface {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Type.body(15).copy(color = Nocturne.text),
                cursorBrush = SolidColor(Nocturne.accent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onNext = { onImeAction?.invoke() },
                    onDone = { onImeAction?.invoke() },
                    onGo = { onImeAction?.invoke() },
                ),
                // Autofill hints are what make the password manager and the SMS
                // code prompt appear at all.
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(
                        if (contentType != null) {
                            Modifier.semantics { this.contentType = contentType }
                        } else {
                            Modifier
                        },
                    ),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = Type.body(15), color = Nocturne.muted(0.3f))
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun LoginScreen(
    viewModel: AuthViewModel,
    onClose: () -> Unit,
    onSignedIn: () -> Unit,
    onSignup: () -> Unit,
    onSocial: (String) -> Unit,
) {
    val state by viewModel.login.collectAsState()
    val context = LocalContext.current
    // site.json is cached for the session, so this is a map read after the
    // first screen that needed it — and an empty list until then, which just
    // leaves the password form standing on its own.
    var providers by remember { mutableStateOf(emptyList<AuthProvider>()) }
    // The code field is appended below the password, i.e. under the keyboard
    // that is still up from typing it. Focusing it is what scrolls it back
    // into view; there is nothing else on screen the user could want next.
    val passwordFocus = remember { FocusRequester() }
    val secondFactorFocus = remember { FocusRequester() }
    // Tapping the button with the password still empty means "next field", the
    // same as the keyboard's own action key — submitting an empty password
    // would only bounce off the server.
    val submit: () -> Unit = {
        if (state.identifier.isNotBlank() && state.password.isBlank()) {
            passwordFocus.requestFocus()
        } else {
            viewModel.submitLogin(onSignedIn)
        }
    }
    LaunchedEffect(state.secondFactorRequired) {
        if (state.secondFactorRequired) {
            // The field is composed this frame but not yet placed, and an
            // unattached FocusRequester throws rather than no-ops.
            withFrameNanos {}
            runCatching { secondFactorFocus.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        runCatching {
            val site = ServiceLocator.get.siteRepository
            site.siteResponse()
            providers = site.authProviders().filter { it.canConnect != false }
        }
    }

    AuthScaffold(
        title = stringResource(R.string.common_login),
        onBack = onClose,
        trailing = {
            Text(
                stringResource(R.string.common_signup),
                style = Type.body(14, FontWeight.SemiBold),
                color = Nocturne.accent,
                modifier = Modifier.clickable(onClick = onSignup).padding(8.dp),
            )
        },
        action = {
            // Pinned with the button rather than left in the form: it is the
            // answer to pressing that button, and in the form it appeared in
            // the strip of scrolled-away content behind the keyboard.
            state.error?.let {
                Text(
                    it,
                    style = Type.body(13),
                    color = Nocturne.danger,
                    modifier = Modifier.padding(bottom = Space.s3),
                )
            }
            FilledAccentButton(
                stringResource(R.string.common_continue),
                loading = state.isSubmitting,
                onClick = submit,
            )
        },
    ) {
        Text(
            stringResource(R.string.auth_terms),
            style = Type.body(12),
            color = Nocturne.muted(0.45f),
        )

        // Straight from `site.json`: the server owns every one of these
        // registrations, so adding a provider there adds a button here.
        providers.forEach { provider ->
            ProviderButton(provider) { onSocial(provider.name) }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(Nocturne.divider))
            Text(stringResource(R.string.common_or), style = Type.body(12), color = Nocturne.muted(0.4f))
            Box(Modifier.weight(1f).height(1.dp).background(Nocturne.divider))
        }

        AuthField(
            label = stringResource(R.string.auth_identifier),
            value = state.identifier,
            placeholder = stringResource(R.string.auth_identifier_hint),
            keyboardType = KeyboardType.Email,
            contentType = ContentType.Username,
            onImeAction = { passwordFocus.requestFocus() },
            onValueChange = viewModel::updateIdentifier,
        )
        AuthField(
            label = stringResource(R.string.auth_password),
            value = state.password,
            isPassword = true,
            contentType = ContentType.Password,
            focusRequester = passwordFocus,
            imeAction = ImeAction.Go,
            onImeAction = submit,
            onValueChange = viewModel::updatePassword,
        )

        if (state.secondFactorRequired) {
            AuthField(
                label = stringResource(if (state.useBackupCode) R.string.auth_backup_code else R.string.auth_code),
                value = state.secondFactorToken,
                placeholder = stringResource(if (state.useBackupCode) R.string.auth_backup_code else R.string.auth_code_hint),
                keyboardType = if (state.useBackupCode) KeyboardType.Text else KeyboardType.Number,
                contentType = if (state.useBackupCode) null else ContentType.SmsOtpCode,
                focusRequester = secondFactorFocus,
                imeAction = ImeAction.Go,
                onImeAction = { viewModel.submitLogin(onSignedIn) },
                onValueChange = viewModel::updateSecondFactor,
            )
            Text(
                stringResource(if (state.useBackupCode) R.string.auth_use_totp else R.string.auth_use_backup),
                style = Type.body(12),
                color = Nocturne.accent,
                modifier = Modifier.clickable { viewModel.toggleBackupCode() },
            )
        }

        Text(
            stringResource(R.string.auth_forgot),
            style = Type.body(12),
            color = Nocturne.muted(0.5f),
            modifier = Modifier.clickable { viewModel.forgotPassword(context) },
        )
    }
}

@Composable
private fun SignupEmailScreen(viewModel: AuthViewModel, controller: NavHostController) {
    val state by viewModel.signup.collectAsState()
    val check = state.emailCheck
    val ready = state.email.isNotBlank() && check.settled
    val next = { controller.navigate(Route.SignupUsername) }
    AuthScaffold(
        stringResource(R.string.auth_email_step),
        onBack = { controller.popBackStack() },
        action = {
            FilledAccentButton(
                stringResource(R.string.common_continue),
                enabled = ready,
                onClick = { next() },
            )
        },
    ) {
        AuthField(
            label = stringResource(R.string.auth_email),
            value = state.email,
            placeholder = stringResource(R.string.auth_identifier_hint),
            keyboardType = KeyboardType.Email,
            contentType = ContentType.EmailAddress,
            onImeAction = { if (ready) next() },
            onValueChange = viewModel::updateEmail,
        )
        val rejected = check.rejected
        when {
            state.email.isBlank() -> Unit
            check.checking -> Text(
                stringResource(R.string.common_checking),
                style = Type.body(12),
                color = Nocturne.muted(0.45f),
            )
            check.malformed -> FieldNote(false, stringResource(R.string.auth_email_invalid))
            rejected != null -> FieldNote(false, rejected)
            // Not "available" — the server will not say. Only that this is an
            // address the next step can be sent.
            else -> FieldNote(true, stringResource(R.string.auth_email_ok))
        }
    }
}

/**
 * One "continue with…" row, with the provider's own mark on the left.
 *
 * Taller than an ordinary button because it is the first thing on the screen
 * and the mark has to be legible at a glance — a brand is recognised before it
 * is read, which is the whole reason for showing one.
 */
@Composable
private fun ProviderButton(provider: AuthProvider, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .border(1.dp, Nocturne.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderMark(provider)
        Text(
            stringResource(R.string.auth_continue_with, provider.label),
            style = Type.heading(15),
            color = Nocturne.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        // Balances the mark so the labels stay centred as a column.
        Spacer(Modifier.size(MARK_SIZE))
    }
}

private val MARK_SIZE = 22.dp

/**
 * Matched on the strategy name first and the server's Font Awesome hint
 * second, so a provider re-registered under a different name still gets its
 * mark. An unknown one gets blank space rather than a wrong logo.
 */
@Composable
private fun ProviderMark(provider: AuthProvider) {
    val icon = provider.iconOverride
    when {
        provider.name == "google_oauth2" || icon == "fab-google" ->
            Image(painterResource(R.drawable.ic_provider_google), null, Modifier.size(MARK_SIZE))
        provider.name == "telegram" || icon == "fab-telegram" ->
            Image(painterResource(R.drawable.ic_provider_telegram), null, Modifier.size(MARK_SIZE))
        // GitHub's and X's marks are one colour by design, and the colour they
        // take is the one the page is written in — black on white, white on
        // black. Tinted rather than shipped twice.
        provider.name == "github" || icon == "fab-github" ->
            Icon(painterResource(R.drawable.ic_provider_github), null, Modifier.size(MARK_SIZE), tint = Nocturne.text)
        provider.name == "twitter" || icon == "fab-x-twitter" ->
            Icon(painterResource(R.drawable.ic_provider_x), null, Modifier.size(MARK_SIZE), tint = Nocturne.text)
        else -> Spacer(Modifier.size(MARK_SIZE))
    }
}

/** The tick-or-cross line under a field, as the username step draws it. */
@Composable
private fun FieldNote(good: Boolean, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (good) Lucide.Check else Lucide.X,
            null,
            tint = if (good) Nocturne.success else Nocturne.danger,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text,
            style = Type.body(12),
            color = if (good) Nocturne.success else Nocturne.danger,
        )
    }
}

@Composable
private fun SignupUsernameScreen(viewModel: AuthViewModel, controller: NavHostController) {
    val state by viewModel.signup.collectAsState()
    val check = state.usernameCheck

    AuthScaffold(stringResource(R.string.auth_username_step), onBack = { controller.popBackStack() },
        action = {
            FilledAccentButton(stringResource(R.string.common_continue), enabled = check.available == true) {
                controller.navigate(Route.SignupPassword)
            }
        },
    ) {
        AuthField(
            label = stringResource(R.string.auth_username),
            value = state.username,
            placeholder = stringResource(R.string.auth_username_hint),
            contentType = ContentType.NewUsername,
            onValueChange = viewModel::updateUsername,
        )
        when {
            check.checking -> Text(stringResource(R.string.common_checking), style = Type.body(12), color = Nocturne.muted(0.45f))
            check.available == true -> FieldNote(true, stringResource(R.string.auth_username_available))
            check.available == false -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldNote(false, stringResource(R.string.auth_username_taken))
                check.suggestion?.let { suggestion ->
                    Text(
                        stringResource(R.string.auth_username_suggestion, suggestion),
                        style = Type.body(12),
                        color = Nocturne.accent,
                        modifier = Modifier.clickable { viewModel.updateUsername(suggestion) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SignupPasswordScreen(viewModel: AuthViewModel, controller: NavHostController) {
    val state by viewModel.signup.collectAsState()
    val longEnough = state.password.length >= 10

    AuthScaffold(stringResource(R.string.auth_password_step), onBack = { controller.popBackStack() },
        action = {
            FilledAccentButton(stringResource(R.string.common_continue), enabled = longEnough) {
                controller.navigate(Route.SignupGender)
            }
        },
    ) {
        AuthField(
            label = stringResource(R.string.auth_password),
            value = state.password,
            placeholder = stringResource(R.string.auth_password_hint),
            isPassword = true,
            contentType = ContentType.NewPassword,
            onValueChange = viewModel::updateSignupPassword,
        )
        Text(
            stringResource(if (longEnough) R.string.auth_password_ok else R.string.auth_password_short),
            style = Type.body(12),
            color = if (longEnough) Nocturne.success else Nocturne.muted(0.45f),
        )
    }
}

@Composable
private fun SignupGenderScreen(viewModel: AuthViewModel, controller: NavHostController) {
    val state by viewModel.signup.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadSignupFields() }
    // The site's own options are what the server will match against; the
    // labels below are only translations of them, and an option nobody
    // translated still shows, spelled the way the site spells it.
    val options = state.genderField?.options.orEmpty()

    AuthScaffold(stringResource(R.string.auth_gender_step), onBack = { controller.popBackStack() },
        action = {
            FilledAccentButton(stringResource(R.string.common_continue), enabled = state.gender != null) {
                controller.navigate(Route.SignupInterests)
            }
        },
    ) {
        Text(stringResource(R.string.auth_gender_footer), style = Type.body(12), color = Nocturne.muted(0.45f))
        options.forEach { option ->
            val selected = state.gender == option
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(if (selected) Nocturne.selected else Nocturne.surface)
                    .clickable { viewModel.updateGender(option) }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    genderLabel(option),
                    style = Type.body(15, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (selected) Nocturne.accent else Nocturne.text,
                )
            }
        }
    }
}

@Composable
private fun SignupInterestsScreen(
    viewModel: AuthViewModel,
    controller: NavHostController,
    onSignedIn: () -> Unit,
) {
    val state by viewModel.signup.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadInterestCandidates() }

    AuthScaffold(stringResource(R.string.auth_interests_step), onBack = { controller.popBackStack() },
        action = {
            state.error?.let {
                Text(
                    it,
                    style = Type.body(13),
                    color = Nocturne.danger,
                    modifier = Modifier.padding(bottom = Space.s3),
                )
            }
            FilledAccentButton(stringResource(R.string.auth_finish_signup), loading = state.isSubmitting) {
                viewModel.submitSignup { result ->
                    when (result) {
                        SignupResult.SignedIn -> onSignedIn()
                        is SignupResult.NeedsActivation -> controller.navigate(Route.SignupActivation(state.email))
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.auth_interests_footer), style = Type.body(12), color = Nocturne.muted(0.45f))

        // The picked ones stay above the batch and outlive it: "换一批" deals
        // around them rather than through them.
        if (state.picked.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.picked.forEach { node ->
                    InterestChip(node.name, picked = true) { viewModel.toggleInterest(node) }
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.candidates.filterNot { it.id in state.interests }.forEach { node ->
                InterestChip(node.name, picked = false) { viewModel.toggleInterest(node) }
            }
        }

        SecondaryButton(stringResource(R.string.auth_shuffle), block = true) {
            viewModel.shuffleInterestCandidates()
        }
    }
}

/** The site writes its options in English; these are the ones we can translate. */
@Composable
private fun genderLabel(option: String): String = when (option.lowercase()) {
    "male" -> stringResource(R.string.auth_gender_male)
    "female" -> stringResource(R.string.auth_gender_female)
    "non-binary" -> stringResource(R.string.auth_gender_other)
    "rather not say" -> stringResource(R.string.auth_gender_private)
    else -> option
}

/**
 * A node to pick, sized to be tapped rather than read: this is the one screen
 * where the nodes are the content, so they get the room a tag chip elsewhere
 * would not deserve.
 */
@Composable
private fun InterestChip(name: String, picked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.lg)
    Row(
        Modifier
            .clip(shape)
            .background(if (picked) Nocturne.selected else Nocturne.surface)
            .border(1.dp, if (picked) Nocturne.accent else Nocturne.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            name,
            style = Type.body(15, if (picked) FontWeight.SemiBold else FontWeight.Normal),
            color = if (picked) Nocturne.accent else Nocturne.text,
        )
        if (picked) {
            Icon(Lucide.X, null, tint = Nocturne.accent, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ActivationScreen(email: String, onBackToLogin: () -> Unit) {
    // No back button here: the account exists, and going "back" into the signup
    // steps would only invite a duplicate attempt.
    AuthScaffold(stringResource(R.string.auth_check_email),
        action = {
            FilledAccentButton(stringResource(R.string.auth_back_to_login), onClick = onBackToLogin)
        },
    ) {
        Text(
            stringResource(R.string.auth_activation_detail, email),
            style = Type.body(14),
            color = Nocturne.muted(0.62f),
            textAlign = TextAlign.Start,
        )
        Text(
            stringResource(R.string.auth_activation_hint),
            style = Type.body(12),
            color = Nocturne.muted(0.45f),
        )
    }
}
