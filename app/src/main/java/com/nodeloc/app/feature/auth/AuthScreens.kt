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
            // The provider runs in a Custom Tab and the result arrives through
            // MainActivity, so this screen only learns of it as the session
            // appearing.
            val context = androidx.compose.ui.platform.LocalContext.current
            var socialStarted by remember { mutableStateOf(false) }
            val isSignedIn by app.isSignedIn.collectAsState()
            LaunchedEffect(isSignedIn, socialStarted) {
                if (isSignedIn && socialStarted) {
                    socialStarted = false
                    signedIn()
                }
            }
            Box(Modifier.fillMaxSize()) {
                LoginScreen(
                    viewModel = viewModel,
                    onClose = navigator::back,
                    onSignedIn = signedIn,
                    onSignup = { controller.navigate(Route.SignupEmail) },
                    onSocial = {
                        socialStarted = true
                        viewModel.startSocialLogin(context, it)
                    },
                )
            }
        }
        composable<Route.SignupEmail> {
            val context = androidx.compose.ui.platform.LocalContext.current
            RegisterScreen(
                viewModel = viewModel,
                // Opened from 注册 this page is the root of the stack, and
                // popping the root does nothing: back has to leave the flow.
                onBack = { if (controller.previousBackStackEntry != null) controller.popBackStack() else navigator.back() },
                onLogin = {
                    if (controller.previousBackStackEntry != null) controller.popBackStack()
                    else controller.navigate(Route.AuthLogin) { popUpTo(Route.SignupEmail) { inclusive = true } }
                },
                onSocial = { viewModel.startSocialLogin(context, it) },
                onResult = { result ->
                    when (result) {
                        is SignupResult.SignedIn -> {
                            app.refreshSession()
                            app.refreshBadge()
                            navigator.finishAuth()
                        }
                        is SignupResult.NeedsActivation ->
                            controller.navigate(Route.SignupActivation(viewModel.register.value.email.trim()))
                    }
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

/**
 * 注册 nodeloc (App-Android-Register), one page: the email gets a 6-digit
 * code first, and registering with it verifies the address and — where no
 * review is needed — signs the new account in. The username is checked as it
 * is typed; the invite code is optional; 申请理由 shows only once the server
 * asks for it. 人机验证 comes from the server's config, through [Captcha].
 */
@Composable
private fun RegisterScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onSocial: (String) -> Unit,
    onResult: (SignupResult) -> Unit,
) {
    val state by viewModel.register.collectAsState()
    var providers by remember { mutableStateOf(emptyList<AuthProvider>()) }
    LaunchedEffect(Unit) {
        runCatching {
            val site = ServiceLocator.get.siteRepository
            site.siteResponse()
            providers = site.authProviders().filter { it.canConnect != false }
        }
    }
    val errors = state.fieldErrors

    AuthScaffold(
        title = stringResource(R.string.register_title),
        onBack = onBack,
        action = {
            state.error?.let {
                Text(it, style = Type.body(13), color = Nocturne.danger, modifier = Modifier.padding(bottom = Space.s3))
            }
            FilledAccentButton(
                stringResource(R.string.common_signup),
                enabled = state.canSubmit,
                loading = state.isSubmitting,
            ) { viewModel.submitRegister(onResult) }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.register_have_account), style = Type.body(15), color = Nocturne.textSecondary)
            Text(
                stringResource(R.string.register_go_login),
                style = Type.body(15, FontWeight.Bold),
                color = Nocturne.accent,
                modifier = Modifier.clickable(onClick = onLogin).padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }

        AuthField(
            label = stringResource(R.string.auth_email),
            value = state.email,
            placeholder = stringResource(R.string.auth_identifier_hint),
            keyboardType = KeyboardType.Email,
            contentType = ContentType.EmailAddress,
            onValueChange = { value -> viewModel.updateRegister { it.copy(email = value) } },
        )
        errors["email"]?.let { FieldNote(false, it) }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                AuthField(
                    label = stringResource(R.string.register_code),
                    value = state.code,
                    placeholder = stringResource(R.string.register_code_hint),
                    keyboardType = KeyboardType.Number,
                    onValueChange = { value -> viewModel.updateRegister { it.copy(code = value.filter(Char::isDigit).take(6)) } },
                )
            }
            val sendLabel = when {
                state.resendIn > 0 -> stringResource(R.string.register_resend_in, state.resendIn)
                state.codeSent -> stringResource(R.string.register_resend)
                else -> stringResource(R.string.register_send_code)
            }
            SecondaryButton(
                sendLabel,
                modifier = Modifier.height(44.dp),
                enabled = state.emailValid && !state.sendingCode && state.resendIn == 0,
                onClick = viewModel::sendRegisterCode,
            )
        }
        when {
            errors["email_code"] != null -> FieldNote(false, errors.getValue("email_code"))
            state.codeSent -> Text(
                stringResource(R.string.register_code_sent, state.email.trim()),
                style = Type.body(12),
                color = Nocturne.textSecondary,
            )
        }

        AuthField(
            label = stringResource(R.string.auth_username),
            value = state.username,
            placeholder = stringResource(R.string.auth_username_hint),
            contentType = ContentType.NewUsername,
            onValueChange = { value -> viewModel.updateRegister { it.copy(username = value) } },
        )
        val check = state.usernameCheck
        when {
            errors["username"] != null -> FieldNote(false, errors.getValue("username"))
            check.checking -> Text(stringResource(R.string.common_checking), style = Type.body(12), color = Nocturne.textSecondary)
            check.available == true -> FieldNote(true, stringResource(R.string.auth_username_available))
            check.available == false -> {
                FieldNote(false, stringResource(R.string.auth_username_taken))
                check.suggestion?.let { suggestion ->
                    Text(
                        stringResource(R.string.auth_username_suggestion, suggestion),
                        style = Type.body(12),
                        color = Nocturne.accent,
                        modifier = Modifier.clickable { viewModel.updateRegister { it.copy(username = suggestion) } },
                    )
                }
            }
        }

        AuthField(
            label = stringResource(R.string.auth_password),
            value = state.password,
            isPassword = true,
            contentType = ContentType.NewPassword,
            onValueChange = { value -> viewModel.updateRegister { it.copy(password = value) } },
        )
        when {
            errors["password"] != null -> FieldNote(false, errors.getValue("password"))
            state.password.isNotEmpty() -> FieldNote(
                state.passwordLongEnough,
                stringResource(
                    if (state.passwordLongEnough) R.string.auth_password_ok else R.string.register_password_short,
                    RegisterState.PASSWORD_MINIMUM,
                ),
            )
        }

        AuthField(
            label = stringResource(R.string.register_invite),
            value = state.inviteCode,
            imeAction = if (state.needsReason) ImeAction.Next else ImeAction.Done,
            onValueChange = { value -> viewModel.updateRegister { it.copy(inviteCode = value) } },
        )
        errors["invite_code"]?.let { FieldNote(false, it) }
            ?: Text(stringResource(R.string.register_invite_hint), style = Type.body(12), color = Nocturne.textSecondary)

        if (state.needsReason) {
            AuthField(
                label = stringResource(R.string.register_reason),
                value = state.reason,
                placeholder = stringResource(R.string.register_reason_hint),
                imeAction = ImeAction.Done,
                onValueChange = { value -> viewModel.updateRegister { it.copy(reason = value) } },
            )
            errors["reason"]?.let { FieldNote(false, it) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { viewModel.updateRegister { it.copy(agreed = !it.agreed) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Checkbox(
                checked = state.agreed,
                onCheckedChange = null,
                colors = androidx.compose.material3.CheckboxDefaults.colors(
                    checkedColor = Nocturne.accent,
                    uncheckedColor = Nocturne.textTertiary,
                    checkmarkColor = Nocturne.bg,
                ),
            )
            Text(stringResource(R.string.register_agree), style = Type.body(13), color = Nocturne.textSecondary)
        }

        if (providers.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).height(1.dp).background(Nocturne.divider))
                Text(stringResource(R.string.register_or_providers), style = Type.body(12), color = Nocturne.muted(0.4f))
                Box(Modifier.weight(1f).height(1.dp).background(Nocturne.divider))
            }
            providers.forEach { provider -> ProviderButton(provider) { onSocial(provider.name) } }
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
    // The 2.0 server's names and hints are bare ("google", "apple", "x");
    // the Discourse-era ones ("google_oauth2", "fab-google") still match.
    val icon = provider.iconOverride?.removePrefix("fab-")
    when {
        provider.name in setOf("google", "google_oauth2") || icon == "google" ->
            Image(painterResource(R.drawable.ic_provider_google), null, Modifier.size(MARK_SIZE))
        provider.name == "telegram" || icon == "telegram" ->
            Image(painterResource(R.drawable.ic_provider_telegram), null, Modifier.size(MARK_SIZE))
        // GitHub's, X's and Apple's marks are one colour by design, and the
        // colour they take is the one the page is written in — black on white,
        // white on black. Tinted rather than shipped twice.
        provider.name == "github" || icon == "github" ->
            Icon(painterResource(R.drawable.ic_provider_github), null, Modifier.size(MARK_SIZE), tint = Nocturne.text)
        provider.name == "twitter" || icon == "x" || icon == "x-twitter" ->
            Icon(painterResource(R.drawable.ic_provider_x), null, Modifier.size(MARK_SIZE), tint = Nocturne.text)
        provider.name == "apple" || icon == "apple" ->
            Icon(painterResource(R.drawable.ic_provider_apple), null, Modifier.size(MARK_SIZE), tint = Nocturne.text)
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
