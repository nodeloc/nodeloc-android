package com.nodeloc.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.HairLine
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.ToastCenter
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.util.runCatchingCancellable
import com.nodeloc.app.feature.nav.Navigator
import kotlinx.coroutines.launch

/**
 * Who this account has blocked, and the way back.
 *
 * Read off your own profile: `ignored_usernames` is on the user serializer and
 * filled from your own rows, so there is no separate list endpoint to ask.
 * Taking one off is the same call that put it on with the level set back to
 * normal.
 */
@Composable
fun BlockedUsersScreen(navigator: Navigator) {
    val scope = rememberCoroutineScope()
    val blocked = remember { mutableStateListOf<String>() }
    var loading by remember { mutableStateOf(true) }
    var allowed by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val me = ServiceLocator.get.session.username
        val profile = me?.let {
            runCatchingCancellable { ServiceLocator.get.client.user(it) }.getOrNull()?.user
        }
        blocked.clear()
        blocked.addAll(profile?.ignoredUsernames.orEmpty())
        // Whether the site lets this account block at all — `ignore_allowed_
        // groups` decides, and saying so beats an empty list that looks like
        // nobody has been blocked.
        allowed = profile?.canIgnoreUsers != false
        loading = false
    }

    SettingsScaffold(stringResource(R.string.settings_blocked), navigator::back, loading = loading) {
        if (blocked.isEmpty()) {
            Text(
                stringResource(if (allowed) R.string.blocked_empty else R.string.blocked_not_allowed),
                style = Type.body(14),
                color = Nocturne.muted(0.5f),
                modifier = Modifier.fillMaxWidth().padding(Space.page),
            )
        }
        blocked.forEach { username ->
            key(username) {
                BlockedRow(
                    username = username,
                    onOpen = { navigator.openProfile(username) },
                    onUnblock = { unblock(scope, blocked, username) },
                )
            }
        }
    }
}

@Composable
private fun BlockedRow(username: String, onOpen: () -> Unit, onUnblock: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = Space.page, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(username, style = Type.body(15), color = Nocturne.text)
            Text(
                stringResource(R.string.unblock_user),
                style = Type.body(13, FontWeight.Medium),
                color = Nocturne.accent,
                modifier = Modifier
                    .background(Nocturne.surface, androidx.compose.foundation.shape.CircleShape)
                    .clickable(onClick = onUnblock)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        HairLine()
    }
}

/**
 * Off the list first, then off the server.
 *
 * The row is what the reader is looking at; putting it back on a failure is
 * cheaper to understand than a row that stays until a round trip says it may
 * go.
 */
private fun unblock(
    scope: kotlinx.coroutines.CoroutineScope,
    blocked: SnapshotStateList<String>,
    username: String,
) {
    val at = blocked.indexOf(username)
    if (at < 0) return
    blocked.removeAt(at)
    ServiceLocator.get.blockedUsers.remove(username)
    scope.launch {
        runCatchingCancellable { ServiceLocator.get.client.unignoreUser(username) }
            .onSuccess { ToastCenter.show(R.string.unblock_user_done) }
            .onFailure {
                blocked.add(at.coerceAtMost(blocked.size), username)
                ServiceLocator.get.blockedUsers.add(username)
                ToastCenter.showError(it)
            }
    }
}
