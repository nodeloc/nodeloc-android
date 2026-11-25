package com.nodeloc.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.network.ApiTwoFactorStatus
import com.nodeloc.app.core.network.ApiUser
import com.nodeloc.app.core.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AccountSettingsState(
    val loading: Boolean = true,
    /** The settings bag itself did not load; every other part is optional, as on the web. */
    val failed: Boolean = false,
    val settings: ApiAccountSettings = ApiAccountSettings(),
    val delivery: ApiDelivery = ApiDelivery(),
    val defaults: Map<String, ApiChannels> = emptyMap(),
    val user: ApiUser? = null,
    val account: ApiAccountInfo? = null,
    val twoFactor: ApiTwoFactorStatus? = null,
    val identities: List<ApiLoginIdentity> = emptyList(),
    val export: ApiDataExport? = null,
    val telegram: ApiTelegramStatus = ApiTelegramStatus(),
    /** A Telegram enable is in flight: the server sends a probe message first, which takes a moment. */
    val telegramBusy: Boolean = false,
    /** The probe failed: the reader has to open the bot and press Start before it can reach them. */
    val telegramRemedy: String? = null,
) {
    val emailVerified: Boolean get() = user?.emailVerified == true

    /** Matches the web's `flag()`: a switch never written reads as its default. */
    fun flag(key: String, fallback: Boolean): Boolean = settings.notify[key] ?: fallback

    val mailOn: Boolean get() = flag("email", true)
    val telegramOn: Boolean get() = telegram.enabled && flag("tg", true)

    fun channelOn(channel: NotifyChannel): Boolean = when (channel) {
        NotifyChannel.Site -> true
        NotifyChannel.Mail -> mailOn && emailVerified
        NotifyChannel.Telegram -> telegramOn
    }

    /** Whether one cell of the matrix is ticked, before the channel itself is taken into account. */
    fun cellOn(key: String, channel: NotifyChannel): Boolean {
        // The server refuses to store these two; they are always on.
        if (key == "safe" && channel != NotifyChannel.Telegram) return true
        val default = defaults[key]
        return when (channel) {
            NotifyChannel.Site -> {
                // An account that only ever set "reply" expects comment replies
                // to follow it — the row was split out of "reply" later.
                if (key == "reply_comment" && "reply_comment" !in settings.notify && "reply" in settings.notify) {
                    settings.notify["reply"] != false
                } else {
                    flag(key, default?.site != false)
                }
            }
            NotifyChannel.Mail -> flag("$key.mail", default?.mail == true)
            NotifyChannel.Telegram -> flag("$key.tg", default?.tg == true)
        }
    }

    fun count(channel: NotifyChannel): Int = NotifyRows.all.count { cellOn(it.key, channel) }

    val telegramIdentity: ApiLoginIdentity? get() = identities.firstOrNull { it.provider == "telegram" && it.linked }
}

enum class NotifyChannel(val suffix: String) { Site(""), Mail(".mail"), Telegram(".tg") }

class AccountSettingsViewModel : ViewModel() {
    private val services = ServiceLocator.get
    private val api = AccountSettingsApi(services.client.api)

    private val _state = MutableStateFlow(AccountSettingsState())
    val state: StateFlow<AccountSettingsState> = _state.asStateFlow()

    private var loaded = false

    /** Held here rather than in the screen so a card can send the reader to another tab ("更换邮箱" → 账号). */
    private val _tab = MutableStateFlow(SettingsTab.Account)
    val tab: StateFlow<SettingsTab> = _tab.asStateFlow()

    fun requestTab(tab: SettingsTab) {
        _tab.value = tab
    }

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            // Everything but the bag itself is optional, as on the web: a part
            // that is not deployed yet (export) must not blank the page.
            val me = async { runCatchingCancellable { api.me().user }.getOrNull() }
            val account = async { runCatchingCancellable { api.account() }.getOrNull() }
            val twoFactor = async { runCatchingCancellable { api.twoFactor() }.getOrNull() }
            val identities = async { runCatchingCancellable { api.identities() }.getOrNull() }
            val export = async { runCatchingCancellable { api.latestExport() }.getOrNull() }
            val telegram = async { runCatchingCancellable { api.telegram() }.getOrNull() }
            val bag = runCatchingCancellable { api.settings() }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    failed = bag == null,
                    settings = bag?.settings ?: it.settings,
                    delivery = bag?.delivery ?: it.delivery,
                    defaults = bag?.notifyDefaults ?: it.defaults,
                    user = me.await() ?: it.user,
                    account = account.await() ?: it.account,
                    twoFactor = twoFactor.await() ?: it.twoFactor,
                    identities = identities.await() ?: it.identities,
                    export = export.await(),
                    telegram = telegram.await() ?: it.telegram,
                )
            }
            if (bag == null) loaded = false
        }
    }

    // ------------------------------------------------------------- settings

    /**
     * Optimistic, and the rollback undoes only this write's own field: two
     * switches flipped in quick succession each own their revert, so the
     * first one failing does not also undo the second.
     */
    private fun write(
        body: JsonObject,
        apply: (AccountSettingsState) -> AccountSettingsState,
        revert: (AccountSettingsState) -> AccountSettingsState,
        done: Int? = R.string.settings2_saved,
    ) {
        _state.update(apply)
        viewModelScope.launch {
            runCatchingCancellable { api.putSettings(body) }
                .onSuccess { done?.let(ToastCenter::show) }
                .onFailure {
                    _state.update(revert)
                    ToastCenter.showError(it)
                }
        }
    }

    fun setFlag(wireKey: String, value: Boolean, lens: (ApiAccountSettings, Boolean) -> ApiAccountSettings) {
        val before = lensValue(wireKey)
        write(
            buildJsonObject { put(wireKey, value) },
            { it.copy(settings = lens(it.settings, value)) },
            { it.copy(settings = lens(it.settings, before ?: !value)) },
        )
    }

    private fun lensValue(wireKey: String): Boolean? = with(_state.value.settings) {
        when (wireKey) {
            "allow_follow" -> allowFollow
            "search_index" -> searchIndex
            "show_upvotes" -> showUpvotes
            "show_joined_nodes" -> showJoinedNodes
            "show_online" -> showOnline
            "show_level" -> showLevel
            else -> null
        }
    }

    fun setMessagePolicy(value: String) {
        val before = _state.value.settings.messagePolicy
        write(
            buildJsonObject { put("message_policy", value) },
            { it.copy(settings = it.settings.copy(messagePolicy = value)) },
            { it.copy(settings = it.settings.copy(messagePolicy = before)) },
        )
    }

    fun setMentionPolicy(value: String) {
        val before = _state.value.settings.mentionPolicy
        write(
            buildJsonObject { put("mention_policy", value) },
            { it.copy(settings = it.settings.copy(mentionPolicy = value)) },
            { it.copy(settings = it.settings.copy(mentionPolicy = before)) },
        )
    }

    fun setDigest(value: String) {
        val before = _state.value.settings.digest
        write(
            buildJsonObject { put("digest", value) },
            { it.copy(settings = it.settings.copy(digest = value)) },
            { it.copy(settings = it.settings.copy(digest = before)) },
        )
    }

    /** One switch in the `notify` map — a matrix cell or a whole channel. */
    fun setNotify(key: String, value: Boolean, done: Int? = R.string.settings2_saved) {
        val before = _state.value.settings.notify[key]
        write(
            buildJsonObject { put("notify", buildJsonObject { put(key, value) }) },
            { it.copy(settings = it.settings.copy(notify = it.settings.notify + (key to value))) },
            {
                val restored = if (before == null) it.settings.notify - key else it.settings.notify + (key to before)
                it.copy(settings = it.settings.copy(notify = restored))
            },
            done,
        )
    }

    /** Not optimistic: the server runs it through the site's text check and may refuse it. */
    fun saveSignature(value: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { api.putSettings(buildJsonObject { put("signature", value.trim()) }) }
                .onSuccess { bag ->
                    _state.update { it.copy(settings = it.settings.copy(signature = bag.settings.signature)) }
                    ToastCenter.show(R.string.settings2_saved)
                    onDone()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    // ------------------------------------------------------------- delivery

    /**
     * The zone rides along with every delivery write: quiet hours are
     * wall-clock times, and the server has no other way to learn which wall.
     */
    fun setDelivery(field: String, value: Any, lens: (ApiDelivery) -> ApiDelivery) {
        val before = _state.value.delivery
        val body = buildJsonObject {
            put("delivery", buildJsonObject {
                put("tz", java.time.ZoneId.systemDefault().id)
                when (value) {
                    is Boolean -> put(field, value)
                    else -> put(field, JsonPrimitive(value.toString()))
                }
            })
        }
        write(body, { it.copy(delivery = lens(it.delivery)) }, { it.copy(delivery = before) })
    }

    fun resetNotify() {
        viewModelScope.launch {
            runCatchingCancellable { api.putSettings(buildJsonObject { put("notify_reset", true) }) }
                .onSuccess { bag ->
                    _state.update { it.copy(settings = bag.settings, delivery = bag.delivery, defaults = bag.notifyDefaults.ifEmpty { it.defaults }) }
                    ToastCenter.show(R.string.settings2_reset_done)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    // ------------------------------------------------------------- telegram

    /** Turning Telegram on probes the bot first; a refusal opens the remedy steps rather than a bare toast. */
    fun enableTelegram() {
        if (_state.value.telegramBusy) return
        _state.update { it.copy(telegramBusy = true, telegramRemedy = null) }
        viewModelScope.launch {
            runCatchingCancellable {
                api.setTelegram(true)
                // A channel paused earlier would leave the fresh binding silent.
                if (_state.value.settings.notify["tg"] == false) api.putSettings(buildJsonObject { put("notify", buildJsonObject { put("tg", true) }) })
            }
                .onSuccess {
                    _state.update {
                        it.copy(
                            telegramBusy = false,
                            telegram = it.telegram.copy(enabled = true),
                            settings = it.settings.copy(notify = it.settings.notify - "tg"),
                        )
                    }
                    ToastCenter.show(R.string.settings2_tg_bound_toast)
                }
                .onFailure { error ->
                    // The server's own sentence names the actual cause (bot blocked,
                    // Telegram unreachable); the dialog falls back to the steps alone.
                    val message = (error as? com.nodeloc.app.core.network.DiscourseError)?.serverMessage
                    _state.update { it.copy(telegramBusy = false, telegramRemedy = message.orEmpty()) }
                }
        }
    }

    fun dismissTelegramRemedy() {
        _state.update { it.copy(telegramRemedy = null) }
    }

    fun disableTelegram() {
        viewModelScope.launch {
            runCatchingCancellable { api.setTelegram(false) }
                .onSuccess {
                    _state.update { it.copy(telegram = it.telegram.copy(enabled = false)) }
                    ToastCenter.show(R.string.settings2_tg_unbound_toast)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun sendTest(channel: NotifyChannel) {
        val wire = if (channel == NotifyChannel.Mail) "mail" else "tg"
        viewModelScope.launch {
            runCatchingCancellable { api.notifyTest(wire) }
                .onSuccess {
                    if (channel == NotifyChannel.Mail) {
                        ToastCenter.show(R.string.settings2_mail_test_sent, _state.value.user?.email.orEmpty())
                    } else {
                        ToastCenter.show(R.string.settings2_tg_test_sent)
                    }
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    // -------------------------------------------------------------- account

    fun setPhone(value: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { api.setPhone(value.trim()) }
                .onSuccess {
                    _state.update { it.copy(account = (it.account ?: ApiAccountInfo()).copy(phone = value.trim().ifEmpty { null })) }
                    ToastCenter.show(R.string.settings2_saved)
                    onDone()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun requestEmailChange(email: String, secret: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { api.requestEmailChange(email.trim(), secret) }
                .onSuccess { pending ->
                    val shown = pending ?: email.trim()
                    _state.update { it.copy(account = (it.account ?: ApiAccountInfo()).copy(pendingEmail = shown)) }
                    ToastCenter.show(R.string.settings2_email_sent, shown)
                    onDone()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun changePassword(current: String, new: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { api.changePassword(current, new) }
                .onSuccess {
                    _state.update { it.copy(account = it.account?.copy(passwordChangedAt = java.time.Instant.now().toString())) }
                    ToastCenter.show(R.string.settings2_password_done)
                    onDone()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    /** An account with no password gets the reset mail, which is how it sets one. */
    fun sendPasswordSetup() {
        val login = _state.value.user?.email ?: services.session.username ?: return
        viewModelScope.launch {
            runCatchingCancellable { services.client.requestPasswordReset(login) }
                .onSuccess { ToastCenter.show(R.string.security_reset_sent) }
                .onFailure { if (it !is com.nodeloc.app.core.network.CaptchaCancelled) ToastCenter.showError(it) }
        }
    }

    fun unlink(provider: String) {
        viewModelScope.launch {
            runCatchingCancellable { api.unlinkIdentity(provider) }
                .onSuccess {
                    _state.update { s ->
                        s.copy(identities = s.identities.map { if (it.provider == provider) it.copy(linked = false, name = null, email = null) else it })
                    }
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun requestExport() {
        viewModelScope.launch {
            runCatchingCancellable { api.requestExport() }
                .onSuccess { job ->
                    _state.update { it.copy(export = job ?: ApiDataExport(status = "pending")) }
                    ToastCenter.show(R.string.settings2_export_requested)
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }

    fun deleteAccount(secret: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { api.deleteAccount(secret) }
                .onSuccess {
                    ToastCenter.show(R.string.settings2_deleted)
                    onDeleted()
                }
                .onFailure { ToastCenter.showError(it) }
        }
    }
}
