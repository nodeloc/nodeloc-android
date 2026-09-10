package com.nodeloc.app.feature.settings

import com.nodeloc.app.core.util.runCatchingCancellable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.FieldSurface
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.SettingsNavRow
import com.nodeloc.app.core.design.SettingsRowDivider
import com.nodeloc.app.core.design.SettingsSection
import com.nodeloc.app.core.design.SettingsValueRow
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.model.AssociatedAccount
import com.nodeloc.app.core.model.UserAuthToken
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SecurityState(
    val secondFactorEnabled: Boolean = false,
    val sessions: List<UserAuthToken> = emptyList(),
    val associatedAccounts: List<AssociatedAccount> = emptyList(),
    val totpSecret: String? = null,
    val backupCodes: List<String> = emptyList(),
    /** Starts true: the screen composes before the account detail is fetched. */
    val isLoading: Boolean = true,
    val trustedSession: Boolean = false,
)

class SecurityViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val client = services.client
    private val session = services.session

    private val _state = MutableStateFlow(SecurityState())
    val state: StateFlow<SecurityState> = _state.asStateFlow()

    fun load() {
        val username = session.username ?: run {
            _state.value = _state.value.copy(isLoading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val detail = runCatchingCancellable { client.accountDetail(username) }.getOrNull()?.user
            val trusted = runCatchingCancellable { client.trustedSession() }.getOrNull()?.isTrusted == true
            _state.value = _state.value.copy(
                secondFactorEnabled = detail?.secondFactorEnabled == true,
                sessions = detail?.userAuthTokens.orEmpty(),
                associatedAccounts = detail?.associatedAccounts.orEmpty(),
                trustedSession = trusted,
                isLoading = false,
            )
        }
    }

    /** Sensitive routes require a recently password-confirmed session. */
    fun confirmSession(password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val trusted = runCatchingCancellable { client.confirmSession(password) }.getOrNull()?.isTrusted == true
            _state.value = _state.value.copy(trustedSession = trusted)
            if (!trusted) ToastCenter.show(R.string.security_wrong_password)
            onDone(trusted)
        }
    }

    fun requestPasswordReset() {
        val login = session.username ?: return
        viewModelScope.launch {
            runCatchingCancellable { client.requestPasswordReset(login) }
                .onSuccess { ToastCenter.show(R.string.security_reset_sent) }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun startTotp() {
        viewModelScope.launch {
            runCatchingCancellable { client.createTotp() }
                .onSuccess { _state.value = _state.value.copy(totpSecret = it.key) }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun enableTotp(code: String) {
        viewModelScope.launch {
            runCatchingCancellable { client.enableTotp(code, "NodeLoc Android") }
                .onSuccess {
                    ToastCenter.show(R.string.security_totp_enabled)
                    _state.value = _state.value.copy(totpSecret = null, secondFactorEnabled = true)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun disableSecondFactor() {
        viewModelScope.launch {
            runCatchingCancellable { client.disableSecondFactor() }
                .onSuccess {
                    ToastCenter.show(R.string.security_totp_disabled)
                    _state.value = _state.value.copy(secondFactorEnabled = false)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun generateBackupCodes() {
        viewModelScope.launch {
            runCatchingCancellable { client.generateBackupCodes() }
                .onSuccess { _state.value = _state.value.copy(backupCodes = it.backupCodes.orEmpty()) }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun revokeSession(tokenId: Int?) {
        val username = session.username ?: return
        viewModelScope.launch {
            runCatchingCancellable { client.revokeAuthToken(username, tokenId) }
                .onSuccess {
                    ToastCenter.show(R.string.security_session_revoked)
                    load()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun revokeAssociated(provider: String) {
        val username = session.username ?: return
        viewModelScope.launch {
            runCatchingCancellable { client.revokeAssociatedAccount(username, provider) }
                .onSuccess { load() }
                .onFailure { ToastCenter.showError(it) }
        }
    }
}

@Composable
fun SecurityScreen(navigator: Navigator) {
    val viewModel: SecurityViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    var confirmPassword by remember { mutableStateOf<(() -> Unit)?>(null) }
    var totpCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.load() }

    SettingsScaffold(stringResource(R.string.settings_security), navigator::back, loading = state.isLoading) {
        SettingsSection(title = stringResource(R.string.security_password)) {
            SettingsNavRow(stringResource(R.string.security_send_reset)) { viewModel.requestPasswordReset() }
        }

        SettingsSection(title = stringResource(R.string.security_two_factor), footer = stringResource(R.string.security_two_factor_footer)) {
            SettingsValueRow(stringResource(R.string.security_status), stringResource(if (state.secondFactorEnabled) R.string.security_enabled else R.string.security_disabled))
            SettingsRowDivider()
            if (state.secondFactorEnabled) {
                SettingsNavRow(stringResource(R.string.security_generate_backup)) {
                    withTrustedSession(state.trustedSession, { confirmPassword = it }) {
                        viewModel.generateBackupCodes()
                    }
                }
                SettingsRowDivider()
                SettingsNavRow(stringResource(R.string.security_disable_totp), tint = Nocturne.danger) {
                    withTrustedSession(state.trustedSession, { confirmPassword = it }) {
                        viewModel.disableSecondFactor()
                    }
                }
            } else {
                SettingsNavRow(stringResource(R.string.security_enable_totp)) {
                    withTrustedSession(state.trustedSession, { confirmPassword = it }) {
                        viewModel.startTotp()
                    }
                }
            }
        }

        state.totpSecret?.let { secret ->
            SettingsSection(title = stringResource(R.string.security_scan_key), footer = stringResource(R.string.security_totp_footer)) {
                SettingsValueRow(stringResource(R.string.security_key), secret)
                Column(Modifier.padding(horizontal = Space.page, vertical = 10.dp)) {
                    FieldSurface {
                        BasicTextField(
                            value = totpCode,
                            onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
                            textStyle = Type.body(15).copy(color = Nocturne.text),
                            cursorBrush = SolidColor(Nocturne.accent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (totpCode.isEmpty()) {
                                    Text(stringResource(R.string.security_totp_code_hint), style = Type.body(15), color = Nocturne.muted(0.3f))
                                }
                                inner()
                            },
                        )
                    }
                }
                SettingsNavRow(stringResource(R.string.security_finish_binding)) {
                    viewModel.enableTotp(totpCode)
                    totpCode = ""
                }
            }
        }

        if (state.backupCodes.isNotEmpty()) {
            SettingsSection(title = stringResource(R.string.security_backup_codes), footer = stringResource(R.string.security_backup_footer)) {
                Column(
                    Modifier.padding(horizontal = Space.page, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.backupCodes.forEach {
                        Text(it, style = Type.body(14), color = Nocturne.text)
                    }
                }
            }
        }

        SettingsSection(title = stringResource(R.string.security_sessions)) {
            state.sessions.forEachIndexed { index, token ->
                if (index > 0) SettingsRowDivider()
                SettingsNavRow(
                    title = listOfNotNull(token.deviceName, token.osName, token.clientName)
                        .joinToString(" · ")
                        .ifEmpty { stringResource(R.string.security_session_fallback, token.id) },
                    detail = stringResource(if (token.isActive == true) R.string.security_current_device else R.string.security_sign_out_session),
                ) {
                    if (token.isActive != true) {
                        withTrustedSession(state.trustedSession, { confirmPassword = it }) {
                            viewModel.revokeSession(token.id)
                        }
                    }
                }
            }
        }
    }

    confirmPassword?.let { pending ->
        PasswordConfirmDialog(
            onDismiss = { confirmPassword = null },
            onConfirm = { password ->
                viewModel.confirmSession(password) { trusted ->
                    confirmPassword = null
                    if (trusted) pending()
                }
            },
        )
    }
}

/** Runs [action] now if the session is already trusted, else asks for the password. */
private fun withTrustedSession(
    trusted: Boolean,
    requestConfirmation: (() -> Unit) -> Unit,
    action: () -> Unit,
) {
    if (trusted) action() else requestConfirmation(action)
}

@Composable
private fun PasswordConfirmDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = { Text(stringResource(R.string.security_confirm_identity), style = Type.heading(17), color = Nocturne.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(stringResource(R.string.security_confirm_detail), style = Type.body(13), color = Nocturne.muted(0.6f))
                FieldSurface {
                    BasicTextField(
                        value = password,
                        onValueChange = { password = it },
                        textStyle = Type.body(15).copy(color = Nocturne.text),
                        cursorBrush = SolidColor(Nocturne.accent),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }) {
                Text(stringResource(R.string.common_confirm), color = Nocturne.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Nocturne.muted(0.6f)) }
        },
    )
}

@Composable
fun AssociatedAccountsScreen(navigator: Navigator) {
    val viewModel: SecurityViewModel = viewModel()
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    // Losing the only way into an account is not something to do on one tap.
    var confirmUnlink by rememberSaveable { mutableStateOf<String?>(null) }
    confirmUnlink?.let { provider ->
        AlertDialog(
            onDismissRequest = { confirmUnlink = null },
            containerColor = Nocturne.bg,
            title = {
                Text(
                    stringResource(R.string.security_unlink_confirm_title, provider.replaceFirstChar { it.uppercase() }),
                    style = Type.heading(17),
                    color = Nocturne.text,
                )
            },
            text = {
                Text(
                    stringResource(R.string.security_unlink_confirm_detail),
                    style = Type.body(13),
                    color = Nocturne.muted(0.6f),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revokeAssociated(provider)
                    confirmUnlink = null
                }) {
                    Text(stringResource(R.string.security_unlink), color = Nocturne.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnlink = null }) {
                    Text(stringResource(R.string.common_cancel), color = Nocturne.muted(0.6f))
                }
            },
        )
    }

    SettingsScaffold(stringResource(R.string.settings_associated), navigator::back, loading = state.isLoading) {
        SettingsSection(footer = stringResource(R.string.security_associated_footer)) {
            if (state.associatedAccounts.isEmpty()) {
                SettingsValueRow(stringResource(R.string.security_no_associated), "")
            }
            state.associatedAccounts.forEachIndexed { index, account ->
                if (index > 0) SettingsRowDivider()
                SettingsNavRow(
                    title = account.name.replaceFirstChar { it.uppercase() },
                    // The account's own description when it has one, but never
                    // instead of saying what the row does: a row reading
                    // "user@example.com" gave no sign that touching it would
                    // disconnect the account.
                    detail = account.description?.let { "$it · ${stringResource(R.string.security_unlink)}" }
                        ?: stringResource(R.string.security_unlink),
                ) { confirmUnlink = account.name }
            }
        }
    }
}
