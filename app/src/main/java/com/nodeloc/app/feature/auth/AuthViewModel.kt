package com.nodeloc.app.feature.auth

import com.nodeloc.app.core.util.EmailFormat
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.core.util.rethrowIfCancellation
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.model.NodeSummary
import com.nodeloc.app.core.model.UserFieldDefinition
import com.nodeloc.app.core.network.AuthError
import com.nodeloc.app.core.network.SignupResult
import com.nodeloc.app.feature.node.NodeSummaryFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginState(
    val identifier: String = "",
    val password: String = "",
    val secondFactorRequired: Boolean = false,
    val useBackupCode: Boolean = false,
    val secondFactorToken: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

data class UsernameCheck(
    val available: Boolean? = null,
    val suggestion: String? = null,
    val checking: Boolean = false,
)

/**
 * What is known about the address so far.
 *
 * No "available" here, unlike the username: this site runs with
 * `hide_email_address_taken`, so `check_email.json` answers OK to everything
 * and there is no availability to report. Claiming one would be inventing it.
 */
data class EmailCheck(
    val checking: Boolean = false,
    val malformed: Boolean = false,
    val rejected: String? = null,
) {
    val settled: Boolean get() = !checking && !malformed && rejected == null
}

data class SignupState(
    val email: String = "",
    val username: String = "",
    val password: String = "",
    /** The server's own option string, not the label shown for it. */
    val gender: String? = null,
    val genderField: UserFieldDefinition? = null,
    /**
     * The nodes themselves, not their ids: "换一批" deals a batch that excludes
     * whatever is already picked, so a picked node is routinely absent from
     * [candidates] and there would be nothing left to draw it from.
     */
    val picked: List<NodeSummary> = emptyList(),
    val candidates: List<NodeSummary> = emptyList(),
    val emailCheck: EmailCheck = EmailCheck(),
    val usernameCheck: UsernameCheck = UsernameCheck(),
    val isSubmitting: Boolean = false,
    val error: String? = null,
) {
    val interests: Set<Int> get() = picked.mapTo(mutableSetOf()) { it.id }
}

/**
 * The one sign-up page (App-Android-Register): email and its 6-digit code,
 * username, password, an optional invite code, and 申请理由 only once the
 * server asks for it — there is no public flag for registration review.
 */
data class RegisterState(
    val email: String = "",
    val code: String = "",
    val username: String = "",
    val password: String = "",
    val inviteCode: String = "",
    val reason: String = "",
    val agreed: Boolean = false,
    val needsReason: Boolean = false,
    val codeSent: Boolean = false,
    val sendingCode: Boolean = false,
    /** Seconds until 重新发送; 0 when it is live. */
    val resendIn: Int = 0,
    val usernameCheck: UsernameCheck = UsernameCheck(),
    val isSubmitting: Boolean = false,
    /** The server's sentence per field, shown under that field. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    val emailValid: Boolean get() = EmailFormat.isValid(email.trim())
    val passwordLongEnough: Boolean get() = password.length >= PASSWORD_MINIMUM
    val canSubmit: Boolean
        get() = emailValid && code.trim().length == 6 && username.trim().length >= 3 &&
            passwordLongEnough && agreed && !isSubmitting && (!needsReason || reason.isNotBlank())

    companion object {
        /** The server's rule, not a client guess: at least ten characters. */
        const val PASSWORD_MINIMUM = 10
    }
}

/**
 * Both sign-in paths and the step-by-step signup.
 *
 * The 2FA branch is the subtle one: the server answering
 * `invalid_second_factor` on a *first* attempt is a prompt, not a failure, so
 * the form expands in place rather than bouncing the user out with an error.
 */
class AuthViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val authService = services.authService
    private val client = services.client
    private val session = services.session
    private val preferences = services.preferences
    // The application context: held for string resources, never an Activity.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private val context = services.appContext

    private val _login = MutableStateFlow(LoginState())
    val login: StateFlow<LoginState> = _login.asStateFlow()

    private val _signup = MutableStateFlow(SignupState())
    val signup: StateFlow<SignupState> = _signup.asStateFlow()

    private val _register = MutableStateFlow(RegisterState())
    val register: StateFlow<RegisterState> = _register.asStateFlow()
    private var registerUsernameJob: Job? = null
    private var resendJob: Job? = null

    private var emailJob: Job? = null
    private var usernameJob: Job? = null

    // -------------------------------------------------------------- login

    fun updateIdentifier(value: String) {
        _login.value = _login.value.copy(identifier = value, error = null)
    }

    fun updatePassword(value: String) {
        _login.value = _login.value.copy(password = value, error = null)
    }

    fun updateSecondFactor(value: String) {
        _login.value = _login.value.copy(secondFactorToken = value, error = null)
    }

    fun toggleBackupCode() {
        _login.value = _login.value.copy(useBackupCode = !_login.value.useBackupCode, secondFactorToken = "")
    }

    fun submitLogin(onSuccess: () -> Unit) {
        val current = _login.value
        if (current.isSubmitting) return
        viewModelScope.launch {
            _login.value = current.copy(isSubmitting = true, error = null)
            try {
                authService.login(
                    identifier = current.identifier,
                    password = current.password,
                    secondFactorToken = current.secondFactorToken.takeIf { it.isNotBlank() },
                    // 1 = TOTP, 2 = backup code.
                    secondFactorMethod = if (current.useBackupCode) 2 else 1,
                )
                session.onSignedIn()
                _login.value = LoginState()
                onSuccess()
            } catch (error: AuthError) {
                _login.value = if (error is AuthError.SecondFactorRequired) {
                    _login.value.copy(isSubmitting = false, secondFactorRequired = true, error = null)
                } else {
                    _login.value.copy(isSubmitting = false, error = error.text(context))
                }
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _login.value = _login.value.copy(isSubmitting = false, error = context.getString(R.string.auth_error_login_failed))
            }
        }
    }

    /**
     * A social sign-in in a Custom Tab (providers refuse embedded WebViews). The
     * browser comes back to `nodeloc://auth?code=…`, which MainActivity trades
     * for a token; the login screen watches for the session to appear.
     */
    fun startSocialLogin(context: Context, provider: String) {
        runCatchingCancellable {
            val url = authService.buildAuthorizationUrl(
                services.siteRepository.authProviders().firstOrNull { it.name == provider }?.providerUrl
                    ?: "${com.nodeloc.app.core.network.DiscourseConfig.BASE_URL}/auth/$provider",
            )
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
        }.onFailure { ToastCenter.showError(it) }
    }

    fun forgotPassword(context: Context) {
        val login = _login.value.identifier.trim()
        viewModelScope.launch {
            if (login.isNotEmpty()) {
                runCatchingCancellable { client.requestPasswordReset(login) }
                    .onSuccess { ToastCenter.show(R.string.security_reset_sent) }
                    // Closing the challenge is a choice, not a failure.
                    .onFailure { if (it !is com.nodeloc.app.core.network.CaptchaCancelled) ToastCenter.showError(it) }
            } else {
                CustomTabsIntent.Builder().build().launchUrl(
                    context,
                    "${com.nodeloc.app.core.network.DiscourseConfig.BASE_URL}/password-reset".toUri(),
                )
            }
        }
    }

    // ------------------------------------------------------------- signup

    fun updateEmail(value: String) {
        emailJob?.cancel()
        val trimmed = value.trim()
        val check = when {
            trimmed.isEmpty() -> EmailCheck()
            !EmailFormat.isValid(trimmed) -> EmailCheck(malformed = true)
            else -> EmailCheck(checking = true)
        }
        _signup.value = _signup.value.copy(email = value, error = null, emailCheck = check)
        if (!check.checking) return
        emailJob = viewModelScope.launch {
            delay(400)
            // Only the server knows about blocked domains and screened
            // addresses. It stays quiet about a taken one here, so no answer
            // and a happy answer look alike — and both mean "carry on".
            val result = runCatchingCancellable { client.checkEmail(trimmed) }.getOrNull()
            _signup.value = _signup.value.copy(
                emailCheck = EmailCheck(rejected = result?.errors?.firstOrNull()?.takeIf { result.failed != null }),
            )
        }
    }

    fun updateUsername(value: String) {
        _signup.value = _signup.value.copy(username = value, usernameCheck = UsernameCheck(checking = true))
        usernameJob?.cancel()
        if (value.length < 3) {
            _signup.value = _signup.value.copy(usernameCheck = UsernameCheck())
            return
        }
        usernameJob = viewModelScope.launch {
            // 400ms is enough that typing a name doesn't fire a request per key.
            delay(400)
            val result = runCatchingCancellable { client.checkUsername(value) }.getOrNull()
            _signup.value = _signup.value.copy(
                usernameCheck = UsernameCheck(
                    available = result?.available,
                    suggestion = result?.suggestion,
                    checking = false,
                ),
            )
        }
    }

    fun updateSignupPassword(value: String) {
        _signup.value = _signup.value.copy(password = value, error = null)
    }

    fun updateGender(value: String) {
        _signup.value = _signup.value.copy(gender = value)
    }

    fun toggleInterest(node: NodeSummary) {
        val current = _signup.value.picked
        _signup.value = _signup.value.copy(
            picked = when {
                current.any { it.id == node.id } -> current.filterNot { it.id == node.id }
                // Three is the cap; picking a fourth is simply ignored.
                current.size < 3 -> current + node
                else -> current
            },
        )
    }

    /**
     * The site decides what a signup must answer, so the gender step is built
     * from `site.json` rather than from a list held here: a dropdown's value is
     * matched against the field's own options and anything else is treated as
     * unanswered.
     */
    fun loadSignupFields() {
        viewModelScope.launch {
            val site = services.siteRepository
            site.siteResponse()
            val field = site.signupUserFields().firstOrNull { it.fieldType == "dropdown" }
            _signup.value = _signup.value.copy(genderField = field)
        }
    }

    fun loadInterestCandidates() {
        viewModelScope.launch {
            val site = services.siteRepository
            site.siteResponse()
            val nodes = site.categories().values
                .filter { it.parentCategoryId != null }
                .map(NodeSummaryFactory::summary)
            _signup.value = _signup.value.copy(candidates = nextBatch(nodes))
        }
    }

    fun shuffleInterestCandidates() {
        val site = services.siteRepository
        val nodes = site.categories().values
            .filter { it.parentCategoryId != null }
            .map(NodeSummaryFactory::summary)
        _signup.value = _signup.value.copy(candidates = nextBatch(nodes))
    }

    /** A fresh eight, never dealing back something already picked. */
    private fun nextBatch(nodes: List<NodeSummary>): List<NodeSummary> {
        val picked = _signup.value.interests
        return nodes.filterNot { it.id in picked }.shuffled().take(8)
    }

    /** Returns true when the account was active immediately (no email step). */
    fun submitSignup(onResult: (SignupResult) -> Unit) {
        val current = _signup.value
        if (current.isSubmitting) return
        viewModelScope.launch {
            _signup.value = current.copy(isSubmitting = true, error = null)
            try {
                // Interest nodes are parked first: a fresh account may need
                // email activation before it can join anything, and this way
                // the choice survives to the next successful sign-in.
                preferences.setPendingInterestNodes(current.interests)
                val result = authService.signup(
                    username = current.username,
                    name = current.username,
                    email = current.email,
                    password = current.password,
                    userFields = buildMap {
                        val field = current.genderField
                        val gender = current.gender
                        if (field != null && gender != null) put(field.id, gender)
                    },
                )
                if (result is SignupResult.SignedIn) session.onSignedIn()
                _signup.value = _signup.value.copy(isSubmitting = false)
                onResult(result)
            } catch (error: AuthError.SignupNeedsActivation) {
                _signup.value = _signup.value.copy(isSubmitting = false)
                onResult(SignupResult.NeedsActivation(error.text(context)))
            } catch (error: AuthError) {
                // The server's own sentence, as the login path already does:
                // "用户名已被占用" is something the user can act on, and
                // replacing it with "please try again later" throws away the
                // only part of the answer that was worth reading.
                _signup.value = _signup.value.copy(isSubmitting = false, error = error.text(context))
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _signup.value = _signup.value.copy(
                    isSubmitting = false,
                    error = context.getString(R.string.auth_error_signup_failed),
                )
            }
        }
    }

    // ------------------------------------------------------------ register

    fun updateRegister(transform: (RegisterState) -> RegisterState) {
        val before = _register.value
        val after = transform(before)
        // A field that changed loses the server's complaint about it.
        val cleared = after.fieldErrors.filterKeys { key ->
            when (key) {
                "email" -> after.email == before.email
                "email_code" -> after.code == before.code && after.email == before.email
                "username" -> after.username == before.username
                "password" -> after.password == before.password
                "invite_code" -> after.inviteCode == before.inviteCode
                "reason" -> after.reason == before.reason
                else -> true
            }
        }
        _register.value = after.copy(fieldErrors = cleared, error = null)
        if (after.username != before.username) checkRegisterUsername(after.username)
    }

    private fun checkRegisterUsername(value: String) {
        registerUsernameJob?.cancel()
        val name = value.trim()
        if (name.length < 3) {
            _register.value = _register.value.copy(usernameCheck = UsernameCheck())
            return
        }
        _register.value = _register.value.copy(usernameCheck = UsernameCheck(checking = true))
        registerUsernameJob = viewModelScope.launch {
            delay(400)
            val result = runCatchingCancellable { client.checkUsername(name) }.getOrNull()
            _register.value = _register.value.copy(
                usernameCheck = UsernameCheck(available = result?.available, suggestion = result?.suggestion),
            )
        }
    }

    /** 发送验证码, then a minute before it can be asked again. */
    fun sendRegisterCode() {
        val current = _register.value
        if (!current.emailValid || current.sendingCode || current.resendIn > 0) return
        _register.value = current.copy(sendingCode = true, error = null, fieldErrors = current.fieldErrors - "email" - "email_code")
        viewModelScope.launch {
            try {
                authService.requestSignupEmailCode(current.email)
                _register.value = _register.value.copy(sendingCode = false, codeSent = true)
                startResendCountdown()
            } catch (error: AuthError.SignupRejected) {
                _register.value = _register.value.copy(
                    sendingCode = false,
                    fieldErrors = _register.value.fieldErrors + error.fields.ifEmpty { mapOf("email" to error.text(context)) },
                )
            } catch (error: AuthError) {
                _register.value = _register.value.copy(sendingCode = false, error = error.text(context))
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _register.value = _register.value.copy(sendingCode = false, error = context.getString(R.string.error_action_failed))
            }
        }
    }

    private fun startResendCountdown() {
        resendJob?.cancel()
        resendJob = viewModelScope.launch {
            for (left in 60 downTo 1) {
                _register.value = _register.value.copy(resendIn = left)
                delay(1000)
            }
            _register.value = _register.value.copy(resendIn = 0)
        }
    }

    fun submitRegister(onResult: (SignupResult) -> Unit) {
        val current = _register.value
        if (!current.canSubmit) return
        _register.value = current.copy(isSubmitting = true, error = null, fieldErrors = emptyMap())
        viewModelScope.launch {
            try {
                val result = authService.register(
                    username = current.username,
                    email = current.email,
                    password = current.password,
                    emailCode = current.code,
                    inviteCode = current.inviteCode,
                    reason = current.reason.takeIf { current.needsReason },
                )
                if (result is SignupResult.SignedIn) session.onSignedIn()
                _register.value = _register.value.copy(isSubmitting = false)
                onResult(result)
            } catch (error: AuthError.SignupRejected) {
                val fields = error.fields - "captcha"
                _register.value = _register.value.copy(
                    isSubmitting = false,
                    // Asked for 申请理由: registration review is on. The field
                    // appears now, with the server's words under it.
                    needsReason = _register.value.needsReason || "reason" in fields,
                    fieldErrors = fields,
                    error = error.text(context).takeIf { fields.isEmpty() },
                )
            } catch (error: AuthError) {
                _register.value = _register.value.copy(isSubmitting = false, error = error.text(context))
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                _register.value = _register.value.copy(
                    isSubmitting = false,
                    error = context.getString(R.string.auth_error_signup_failed),
                )
            }
        }
    }
}
