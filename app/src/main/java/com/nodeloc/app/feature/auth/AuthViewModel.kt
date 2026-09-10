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

    /** Website OAuth in a Custom Tab; the redirect comes back to MainActivity. */
    fun startWebsiteAuth(context: Context) {
        runCatchingCancellable {
            val url = authService.buildAuthorizationUrl()
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
        }.onFailure { ToastCenter.showError(it) }
    }

    fun forgotPassword(context: Context) {
        val login = _login.value.identifier.trim()
        viewModelScope.launch {
            if (login.isNotEmpty()) {
                runCatchingCancellable { client.requestPasswordReset(login) }
                    .onSuccess { ToastCenter.show(R.string.security_reset_sent) }
                    .onFailure { ToastCenter.showError(it) }
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
}
