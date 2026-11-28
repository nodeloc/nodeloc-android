package com.nodeloc.app.feature.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.User
import com.composables.icons.lucide.UserPlus
import com.composables.icons.lucide.Users
import com.composables.icons.lucide.X
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.design.pressScale
import com.nodeloc.app.core.design.rememberPressSource
import com.nodeloc.app.core.network.DiscourseConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the guest was about to do, which decides the sheet's headline and mark. */
sealed class LoginPromptContext(@StringRes val titleRes: Int, @StringRes val messageRes: Int, val icon: ImageVector) {
    data object Reply : LoginPromptContext(R.string.login_prompt_reply, R.string.login_prompt_reply_d, Lucide.MessageCircle)
    data object Vote : LoginPromptContext(R.string.login_prompt_vote, R.string.login_prompt_vote_d, Lucide.ArrowUp)
    data object Bookmark : LoginPromptContext(R.string.login_prompt_bookmark, R.string.login_prompt_bookmark_d, Lucide.Star)
    data object Follow : LoginPromptContext(R.string.login_prompt_follow, R.string.login_prompt_follow_d, Lucide.UserPlus)
    data class JoinNode(val name: String) : LoginPromptContext(R.string.login_prompt_join, R.string.login_prompt_join_d, Lucide.Users)
    data object Content : LoginPromptContext(R.string.login_prompt_content, R.string.login_prompt_content_d, Lucide.Lock)
    data object Chat : LoginPromptContext(R.string.login_prompt_chat, R.string.login_prompt_chat_d, Lucide.MessagesSquare)
    data object Generic : LoginPromptContext(R.string.login_prompt_generic, R.string.login_prompt_generic_d, Lucide.User)
}

/**
 * The one gate a guest meets when they tap something that needs an account,
 * raisable from any screen, including ones that hold no [AppViewModel].
 * Never two at once: chaining sheets loses the second.
 */
object LoginGate {
    private val _request = MutableStateFlow<LoginPromptContext?>(null)
    val request: StateFlow<LoginPromptContext?> = _request.asStateFlow()

    fun show(context: LoginPromptContext = LoginPromptContext.Generic) {
        _request.value = context
    }

    fun dismiss() {
        _request.value = null
    }
}

/**
 * 未登录时的登录提示 (App-Android-AuthSheet, AuthSheet2): what the guest was
 * about to do, what an account gives them, then 登录 / 注册新账号, the terms
 * line and 先不登录，继续看. It always leads into the in-app auth flow rather
 * than the cold-start welcome screen — losing your place because you tapped
 * "bookmark" is the wrong trade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginGateSheet(app: AppViewModel, onOpenAuth: (signup: Boolean) -> Unit) {
    val request by app.loginGate.collectAsState()
    val context = request ?: return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { app.dismissLoginGate() },
        sheetState = sheetState,
        containerColor = Nocturne.bg,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Nocturne.selected),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(context.icon, null, tint = Nocturne.accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = if (context is LoginPromptContext.JoinNode) {
                        stringResource(context.titleRes, context.name)
                    } else {
                        stringResource(context.titleRes)
                    }
                    Text(title, style = Type.heading(21, FontWeight.Bold), color = Nocturne.text)
                    Text(stringResource(context.messageRes), style = Type.body(14), color = Nocturne.textSecondary)
                }
                val closeLabel = stringResource(R.string.common_close)
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Nocturne.neutral300)
                        .clickable(role = Role.Button, onClickLabel = closeLabel) { app.dismissLoginGate() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, closeLabel, tint = Nocturne.textSecondary, modifier = Modifier.size(14.dp))
                }
            }

            // What an account gives; scrolls rather than truncating in longer languages.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PerkChip(Lucide.MessageCircle, stringResource(R.string.login_prompt_perk_post))
                PerkChip(Lucide.ArrowUp, stringResource(R.string.login_prompt_perk_vote))
                PerkChip(Lucide.Star, stringResource(R.string.login_prompt_perk_save))
                PerkChip(Lucide.Users, stringResource(R.string.login_prompt_perk_join))
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(stringResource(R.string.common_login), filled = true) {
                    app.dismissLoginGate()
                    onOpenAuth(false)
                }
                SheetButton(stringResource(R.string.login_prompt_signup), filled = false) {
                    app.dismissLoginGate()
                    onOpenAuth(true)
                }
            }

            TermsLine()

            Text(
                stringResource(R.string.login_prompt_later),
                style = Type.body(15, FontWeight.SemiBold),
                color = Nocturne.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { app.dismissLoginGate() }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PerkChip(icon: ImageVector, label: String) {
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(Nocturne.neutral300).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, tint = Nocturne.text, modifier = Modifier.size(14.dp))
        Text(label, style = Type.body(12, FontWeight.SemiBold), color = Nocturne.text, maxLines = 1)
    }
}

/** 登录 is ink-filled, 注册新账号 an ink outline: the design's pair, 50 high. */
@Composable
private fun SheetButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val press = rememberPressSource()
    Box(
        Modifier
            .pressScale(press)
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .then(
                if (filled) Modifier.background(Nocturne.text)
                else Modifier.border(1.2.dp, Nocturne.text.copy(alpha = 0.85f), CircleShape),
            )
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.body(16, FontWeight.Bold), color = if (filled) Nocturne.bg else Nocturne.text)
    }
}

@Composable
private fun TermsLine() {
    val linkStyle = TextLinkStyles(SpanStyle(color = Nocturne.textSecondary, fontWeight = FontWeight.SemiBold))
    val terms = stringResource(R.string.login_prompt_terms)
    val privacy = stringResource(R.string.login_prompt_privacy)
    val prefix = stringResource(R.string.login_prompt_terms_prefix)
    val and = stringResource(R.string.login_prompt_terms_and)
    val text = buildAnnotatedString {
        append(prefix)
        withLink(LinkAnnotation.Url("${DiscourseConfig.BASE_URL}/tos", linkStyle)) { append(terms) }
        append(and)
        withLink(LinkAnnotation.Url("${DiscourseConfig.BASE_URL}/privacy", linkStyle)) { append(privacy) }
    }
    Text(
        text,
        style = Type.body(12),
        color = Nocturne.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
