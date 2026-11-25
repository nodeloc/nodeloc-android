package com.nodeloc.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mail
import com.composables.icons.lucide.Send
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.PillTone
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.colors
import com.nodeloc.app.core.network.DiscourseConfig
import com.nodeloc.app.feature.nav.Navigator
import com.nodeloc.app.feature.nav.Route

/** One notification type in the matrix. Keys are the server's `notifyRows`; the order and grouping are the web page's. */
internal data class NotifyRow(val key: String, @StringRes val label: Int, @StringRes val desc: Int? = null, @StringRes val group: Int? = null)

internal object NotifyRows {
    val all = listOf(
        NotifyRow("reply", R.string.settings2_n_reply, group = R.string.settings2_interaction),
        NotifyRow("reply_comment", R.string.settings2_n_reply_comment),
        NotifyRow("mention", R.string.settings2_n_mention),
        NotifyRow("vote", R.string.settings2_n_vote, R.string.settings2_n_vote_d),
        NotifyRow("repost", R.string.settings2_n_repost),
        NotifyRow("follow", R.string.settings2_n_follow, group = R.string.settings2_g_social),
        NotifyRow("message", R.string.settings2_n_message),
        NotifyRow("watching", R.string.settings2_n_watching),
        NotifyRow("followed_topic", R.string.settings2_n_followed_topic),
        NotifyRow("bookmark", R.string.settings2_n_bookmark, R.string.settings2_n_bookmark_d),
        NotifyRow("node_announce", R.string.settings2_n_node_announce, R.string.settings2_n_node_announce_d, R.string.settings2_g_node),
        NotifyRow("node_topic", R.string.settings2_n_node_topic, R.string.settings2_n_node_topic_d),
        NotifyRow("node_hot_topic", R.string.settings2_n_node_hot_topic, R.string.settings2_n_node_hot_topic_d),
        NotifyRow("mod", R.string.settings2_n_mod, R.string.settings2_n_mod_d),
        NotifyRow("trade", R.string.settings2_n_trade, R.string.settings2_n_trade_d, R.string.settings2_g_trade),
        NotifyRow("energy", R.string.settings2_n_energy),
        NotifyRow("level", R.string.settings2_n_level, R.string.settings2_n_level_d, R.string.settings2_g_level),
        NotifyRow("safe", R.string.settings2_n_safe, R.string.settings2_n_safe_d, R.string.settings2_g_safe),
    )
}

@Composable
internal fun NotifyTab(state: AccountSettingsState, viewModel: AccountSettingsViewModel, navigator: Navigator) {
    val tg = state.telegram
    val tgName = state.telegramIdentity?.name?.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }
        ?: stringResource(R.string.settings2_tg_you)
    val mailNeedsVerify = stringResource(R.string.settings2_mail_need_verify)
    val unavailable = stringResource(R.string.settings2_tg_unavailable)

    // Binding needs the Telegram sign-in, which is an OAuth round trip the app
    // does not host: without it the web page does the authorising.
    val bind: () -> Unit = {
        when {
            !tg.available -> ToastCenter.show(unavailable)
            state.telegramIdentity == null -> navigator.openBrowser("${DiscourseConfig.BASE_URL}/settings#notify")
            else -> viewModel.enableTelegram()
        }
    }

    SetPage {
        SetIntro(stringResource(R.string.settings2_channels), stringResource(R.string.settings2_channels_detail), top = 20)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChannelCard(
                icon = Lucide.Bell,
                tile = Nocturne.text,
                name = stringResource(R.string.settings2_site),
                status = stringResource(R.string.settings2_always_on),
                tone = PillTone.Accent,
                switch = true to false,
                onToggle = { ToastCenter.show(R.string.settings2_site_locked) },
                detail = stringResource(R.string.settings2_site_detail, state.count(NotifyChannel.Site)),
            ) {
                // The phone's own delivery lives on its page: it needs the system
                // permission and per-category filters this card has no room for.
                SetButton(stringResource(R.string.settings_push), SetButtonTone.Soft, compact = true) {
                    navigator.openSettingsRoute(Route.SettingsPush)
                }
            }

            val email = state.user?.email.orEmpty()
            val verified = state.emailVerified
            ChannelCard(
                icon = Lucide.Mail,
                tile = Nocturne.info,
                name = stringResource(R.string.settings2_mail),
                status = stringResource(
                    when {
                        !verified -> R.string.settings2_status_unverified
                        state.mailOn -> R.string.settings2_status_on
                        else -> R.string.settings2_status_paused
                    },
                ),
                tone = if (verified && state.mailOn) PillTone.Accent else PillTone.Neutral,
                switch = (state.mailOn && verified) to true,
                onToggle = {
                    if (!verified) {
                        ToastCenter.show(mailNeedsVerify)
                    } else {
                        val next = !state.mailOn
                        viewModel.setNotify("email", next, if (next) R.string.settings2_mail_on_toast else R.string.settings2_mail_paused_toast)
                    }
                },
                detail = when {
                    !verified -> stringResource(R.string.settings2_mail_unverified, email)
                    state.mailOn -> stringResource(R.string.settings2_mail_on, email, state.count(NotifyChannel.Mail))
                    else -> stringResource(R.string.settings2_mail_paused, email)
                },
            ) {
                SetButton(stringResource(R.string.settings2_mail_change), SetButtonTone.Soft, compact = true) {
                    viewModel.requestTab(SettingsTab.Account)
                }
                if (verified) {
                    SetButton(stringResource(R.string.settings2_send_test), SetButtonTone.Plain, compact = true) {
                        viewModel.sendTest(NotifyChannel.Mail)
                    }
                }
            }

            when {
                !tg.available -> ChannelCard(
                    icon = Lucide.Send, tile = Nocturne.info, name = stringResource(R.string.settings2_tg),
                    status = stringResource(R.string.settings2_status_unavailable), tone = PillTone.Neutral,
                    detail = unavailable, dashed = true,
                )
                !tg.enabled -> ChannelCard(
                    icon = Lucide.Send, tile = Nocturne.info, name = stringResource(R.string.settings2_tg),
                    status = stringResource(R.string.settings2_status_unbound), tone = PillTone.Neutral,
                    detail = tg.bot?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings2_tg_unbound_bot, "@$it") }
                        ?: stringResource(R.string.settings2_tg_unbound),
                    dashed = true,
                ) {
                    SetButton(stringResource(R.string.settings2_tg_bind), SetButtonTone.Primary, compact = true, enabled = !state.telegramBusy, onClick = bind)
                }
                else -> ChannelCard(
                    icon = Lucide.Send, tile = Nocturne.info, name = stringResource(R.string.settings2_tg),
                    status = stringResource(if (state.telegramOn) R.string.settings2_status_on else R.string.settings2_status_paused),
                    tone = if (state.telegramOn) PillTone.Accent else PillTone.Neutral,
                    switch = state.telegramOn to true,
                    onToggle = {
                        val next = !state.telegramOn
                        viewModel.setNotify("tg", next, if (next) R.string.settings2_tg_on_toast else R.string.settings2_tg_paused_toast)
                    },
                    detail = if (state.telegramOn) {
                        stringResource(R.string.settings2_tg_on, tgName, state.count(NotifyChannel.Telegram))
                    } else {
                        stringResource(R.string.settings2_tg_paused, tgName)
                    },
                ) {
                    SetButton(stringResource(R.string.settings2_send_test), SetButtonTone.Soft, compact = true) {
                        viewModel.sendTest(NotifyChannel.Telegram)
                    }
                    SetButton(stringResource(R.string.settings2_tg_unbind), SetButtonTone.PlainDanger, compact = true) {
                        viewModel.disableTelegram()
                    }
                }
            }
        }

        SetIntro(stringResource(R.string.settings2_content), stringResource(R.string.settings2_content_detail))
        NotifyMatrix(state, viewModel, onBind = bind, mailNeedsVerify = mailNeedsVerify)

        SetIntro(stringResource(R.string.settings2_delivery), stringResource(R.string.settings2_delivery_detail))
        DeliverySection(state, viewModel, onBind = bind)

        Box(Modifier.padding(top = 20.dp)) {
            var confirm by remember { mutableStateOf(false) }
            SetButton(stringResource(R.string.settings2_reset)) { confirm = true }
            if (confirm) {
                com.nodeloc.app.core.design.ConfirmDialog(
                    title = stringResource(R.string.settings2_reset),
                    body = stringResource(R.string.settings2_reset_body),
                    confirmLabel = stringResource(R.string.settings2_reset),
                    onConfirm = {
                        confirm = false
                        viewModel.resetNotify()
                    },
                    onDismiss = { confirm = false },
                )
            }
        }
    }

    state.telegramRemedy?.let { message ->
        TelegramRemedyDialog(
            bot = tg.bot,
            message = message.ifBlank { null },
            busy = state.telegramBusy,
            onOpen = {
                navigator.openBrowser(tg.bot?.takeIf { it.isNotBlank() }?.let { "https://t.me/$it?start=notify" } ?: "https://t.me/")
            },
            onRetry = viewModel::enableTelegram,
            onDismiss = viewModel::dismissTelegramRemedy,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelCard(
    icon: ImageVector,
    tile: Color,
    name: String,
    status: String,
    tone: PillTone,
    detail: String,
    /** Shown, enabled. Null hides the switch (an unbound or unavailable channel has nothing to switch). */
    switch: Pair<Boolean, Boolean>? = null,
    onToggle: () -> Unit = {},
    dashed: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Nocturne.bg)
            .border(BorderStroke(1.dp, if (dashed) Nocturne.textTertiary else Nocturne.neutral400), shape)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = Nocturne.bg, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = Type.heading(15, FontWeight.Bold), color = Nocturne.text)
                val (ground, ink) = tone.colors()
                Text(
                    status,
                    style = Type.body(12, FontWeight.Bold),
                    color = ink,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(CircleShape)
                        .background(ground)
                        .padding(horizontal = 8.dp),
                )
            }
            if (switch != null) {
                // The site channel's switch is drawn locked rather than hidden:
                // it says "this is on and stays on", which an absent switch does not.
                SetSwitch(switch.first, name, enabled = switch.second, onClick = onToggle)
            }
        }
        Text(detail, style = Type.body(13), color = Nocturne.textSecondary)
        if (actions != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
            }
        }
    }
}

@Composable
private fun NotifyMatrix(
    state: AccountSettingsState,
    viewModel: AccountSettingsViewModel,
    onBind: () -> Unit,
    mailNeedsVerify: String,
) {
    val tg = state.telegram
    val channels = NotifyChannel.entries
    val names = mapOf(
        NotifyChannel.Site to stringResource(R.string.settings2_ch_site),
        NotifyChannel.Mail to stringResource(R.string.settings2_ch_mail),
        NotifyChannel.Telegram to "Telegram",
    )
    val safeLocked = stringResource(R.string.settings2_safe_locked)
    val mailPaused = stringResource(R.string.settings2_mail_paused_cell)
    val tgPaused = stringResource(R.string.settings2_tg_paused_cell)

    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(stringResource(R.string.settings2_types), style = Type.body(12), color = Nocturne.textSecondary, modifier = Modifier.weight(1f))
            channels.forEach { channel ->
                val (sub, warn) = when {
                    channel == NotifyChannel.Telegram && !tg.available -> stringResource(R.string.settings2_status_unavailable) to true
                    channel == NotifyChannel.Telegram && !tg.enabled -> stringResource(R.string.settings2_status_unbound) to true
                    channel == NotifyChannel.Mail && !state.emailVerified -> stringResource(R.string.settings2_status_unverified) to true
                    !state.channelOn(channel) -> stringResource(R.string.settings2_status_paused) to false
                    else -> stringResource(R.string.settings2_count_types, state.count(channel)) to false
                }
                Column(
                    Modifier.width(58.dp).alpha(if (state.channelOn(channel)) 1f else 0.5f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(names.getValue(channel), style = Type.body(11, FontWeight.Bold), color = Nocturne.text, maxLines = 1)
                    Text(sub, style = Type.body(11), color = if (warn) Nocturne.danger else Nocturne.textSecondary, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral400))

        NotifyRows.all.forEach { row ->
            row.group?.let {
                Text(
                    stringResource(it),
                    style = Type.body(13, FontWeight.Bold),
                    color = Nocturne.textSecondary,
                    modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
                )
            }
            val label = stringResource(row.label)
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(label, style = Type.body(15, FontWeight.Medium), color = Nocturne.text)
                        row.desc?.let { Text(stringResource(it), style = Type.body(13), color = Nocturne.textSecondary) }
                    }
                    channels.forEach { channel ->
                        val locked = row.key == "safe" && channel != NotifyChannel.Telegram
                        val available = state.channelOn(channel)
                        val on = state.cellOn(row.key, channel)
                        SetCheckCell(
                            checked = locked || on,
                            available = locked || available,
                            label = "$label · ${names.getValue(channel)}",
                        ) {
                            when {
                                locked -> ToastCenter.show(safeLocked)
                                !available -> when {
                                    channel == NotifyChannel.Telegram && !tg.enabled -> onBind()
                                    channel == NotifyChannel.Mail && !state.emailVerified -> ToastCenter.show(mailNeedsVerify)
                                    channel == NotifyChannel.Mail -> ToastCenter.show(mailPaused)
                                    else -> ToastCenter.show(tgPaused)
                                }
                                else -> viewModel.setNotify(row.key + channel.suffix, !on, done = null)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral400))
            }
        }
    }
}

@Composable
private fun DeliverySection(state: AccountSettingsState, viewModel: AccountSettingsViewModel, onBind: () -> Unit) {
    val d = state.delivery
    val mailOn = state.channelOn(NotifyChannel.Mail)
    val tgBound = state.telegram.enabled
    Column(Modifier.padding(top = 4.dp)) {
        val quietLabel = stringResource(R.string.settings2_quiet)
        SetRow(quietLabel, desc = stringResource(R.string.settings2_quiet_d)) {
            SetSwitch(d.quiet, quietLabel) {
                viewModel.setDelivery("quiet", !d.quiet) { it.copy(quiet = !d.quiet) }
            }
        }
        if (d.quiet) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimeChip(d.quietFrom, stringResource(R.string.settings2_quiet_from)) { value ->
                    viewModel.setDelivery("quiet_from", value) { it.copy(quietFrom = value) }
                }
                Text(stringResource(R.string.settings2_to), style = Type.body(14), color = Nocturne.textSecondary)
                TimeChip(d.quietTo, stringResource(R.string.settings2_quiet_to)) { value ->
                    viewModel.setDelivery("quiet_to", value) { it.copy(quietTo = value) }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral400))
        }

        val skipLabel = stringResource(R.string.settings2_skip_seen)
        SetRow(skipLabel, desc = stringResource(R.string.settings2_skip_seen_d)) {
            SetSwitch(d.skipSeen, skipLabel) {
                viewModel.setDelivery("skip_seen", !d.skipSeen) { it.copy(skipSeen = !d.skipSeen) }
            }
        }

        // The segmented controls are too wide to share a row with their label
        // on a phone, so they sit under it — the board's `flex-wrap` result.
        StackedRow(stringResource(R.string.settings2_mail_freq), stringResource(R.string.settings2_mail_freq_d), dim = !mailOn) {
            SetSegmented(
                listOf(
                    "now" to stringResource(R.string.settings2_freq_now),
                    "hour" to stringResource(R.string.settings2_freq_hour),
                    "day" to stringResource(R.string.settings2_freq_day),
                ),
                d.mailFreq,
            ) { value -> viewModel.setDelivery("mail_freq", value) { it.copy(mailFreq = value) } }
        }
        StackedRow(stringResource(R.string.settings2_digest), stringResource(R.string.settings2_digest_d), dim = !mailOn) {
            SetSegmented(
                listOf(
                    "weekly" to stringResource(R.string.settings2_digest_weekly),
                    "daily" to stringResource(R.string.settings2_digest_daily),
                    "off" to stringResource(R.string.settings2_digest_off),
                ),
                state.settings.digest,
                onSelect = viewModel::setDigest,
            )
        }

        val announceLabel = stringResource(R.string.settings2_announce)
        val announceOn = state.settings.notify["announcement_email"] == true
        SetRow(announceLabel, desc = stringResource(R.string.settings2_announce_d), dim = !mailOn) {
            SetSwitch(announceOn, announceLabel) { viewModel.setNotify("announcement_email", !announceOn) }
        }

        val previewLabel = stringResource(R.string.settings2_tg_preview)
        SetRow(
            previewLabel,
            desc = stringResource(if (tgBound) R.string.settings2_tg_preview_d else R.string.settings2_tg_preview_unbound),
        ) {
            SetSwitch(tgBound && d.tgPreview, previewLabel, enabled = tgBound) {
                if (!tgBound) onBind() else viewModel.setDelivery("tg_preview", !d.tgPreview) { it.copy(tgPreview = !d.tgPreview) }
            }
        }
    }
}

@Composable
private fun StackedRow(label: String, desc: String, dim: Boolean, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 8.dp).alpha(if (dim) 0.5f else 1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                Text(label, style = Type.body(15, FontWeight.Medium), color = Nocturne.text)
                Text(desc, style = Type.body(13), color = Nocturne.textSecondary)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { control() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nocturne.neutral400))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChip(value: String, label: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SetButton(value, SetButtonTone.Outline) { open = true }
    if (!open) return
    val (h, m) = value.split(":").let { (it.getOrNull(0)?.toIntOrNull() ?: 23) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    val picker = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    AlertDialog(
        onDismissRequest = { open = false },
        containerColor = Nocturne.bg,
        title = { Text(label, style = Type.heading(20), color = Nocturne.text) },
        text = {
            TimePicker(
                state = picker,
                colors = TimePickerDefaults.colors(
                    clockDialColor = Nocturne.neutral300,
                    selectorColor = Nocturne.accent,
                    timeSelectorSelectedContainerColor = Nocturne.accent100,
                    timeSelectorSelectedContentColor = Nocturne.accent700,
                    timeSelectorUnselectedContainerColor = Nocturne.neutral300,
                    timeSelectorUnselectedContentColor = Nocturne.text,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                open = false
                val picked = String.format(java.util.Locale.ROOT, "%02d:%02d", picker.hour, picker.minute)
                if (picked != value) onPick(picked)
            }) { Text(stringResource(R.string.common_done), style = Type.body(14, FontWeight.Medium), color = Nocturne.accent) }
        },
        dismissButton = {
            TextButton(onClick = { open = false }) {
                Text(stringResource(R.string.common_cancel), style = Type.body(14, FontWeight.Medium), color = Nocturne.textSecondary)
            }
        },
    )
}

/** The board's binding flow, after the probe message bounced: open the bot, press Start, come back. */
@Composable
private fun TelegramRemedyDialog(
    bot: String?,
    message: String?,
    busy: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val botName = bot?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: stringResource(R.string.settings2_tg_bot_fallback)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.bg,
        title = { Text(stringResource(R.string.settings2_tg_bind), style = Type.heading(20), color = Nocturne.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (message != null) Text(message, style = Type.body(14), color = Nocturne.danger)
                Step(1, stringResource(R.string.settings2_tg_step1, botName))
                Box(Modifier.padding(start = 32.dp)) {
                    SetButton(stringResource(R.string.settings2_tg_open), SetButtonTone.Soft, compact = true, onClick = onOpen)
                }
                Step(2, stringResource(R.string.settings2_tg_step2))
                Step(3, stringResource(R.string.settings2_tg_step3))
                Text(stringResource(R.string.settings2_tg_note), style = Type.body(13), color = Nocturne.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry, enabled = !busy) {
                Text(
                    stringResource(if (busy) R.string.settings2_tg_checking else R.string.settings2_tg_sent),
                    style = Type.body(14, FontWeight.Medium),
                    color = if (busy) Nocturne.textTertiary else Nocturne.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), style = Type.body(14, FontWeight.Medium), color = Nocturne.textSecondary)
            }
        },
    )
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(Nocturne.accent100),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.toString(), style = Type.body(12, FontWeight.Bold), color = Nocturne.accent700)
        }
        Text(text, style = Type.body(14), color = Nocturne.text, modifier = Modifier.weight(1f))
    }
}
