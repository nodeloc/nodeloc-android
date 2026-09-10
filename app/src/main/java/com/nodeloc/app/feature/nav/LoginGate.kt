package com.nodeloc.app.feature.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type

/**
 * The one gate a guest meets when they tap something that needs an account.
 *
 * Every entry point raises this same sheet, and it always leads into the in-app
 * auth flow rather than resetting to the cold-start welcome screen — losing
 * your place because you tapped "bookmark" is the wrong trade.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginGateSheet(app: AppViewModel, onOpenAuth: (signup: Boolean) -> Unit) {
    val visible by app.loginGateVisible.collectAsState()
    if (!visible) return

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = { app.dismissLoginGate() },
        sheetState = sheetState,
        containerColor = Nocturne.bg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            NodelocLoader(height = 60.dp)
            Text(stringResource(R.string.auth_gate_title), style = Type.heading(20), color = Nocturne.text)
            Text(
                stringResource(R.string.auth_gate_detail),
                style = Type.body(14),
                color = Nocturne.muted(0.6f),
                textAlign = TextAlign.Center,
            )
            Column(Modifier.height(8.dp)) {}
            // Two buttons that did the same thing: both opened the login form,
            // so somebody with no account had to spot the signup link on it.
            FilledAccentButton(stringResource(R.string.common_signup)) {
                app.dismissLoginGate()
                onOpenAuth(true)
            }
            SecondaryButton(stringResource(R.string.common_login), block = true) {
                app.dismissLoginGate()
                onOpenAuth(false)
            }
        }
    }
}
