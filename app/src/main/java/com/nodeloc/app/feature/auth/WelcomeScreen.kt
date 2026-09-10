package com.nodeloc.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nodeloc.app.R
import com.nodeloc.app.core.design.FilledAccentButton
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLogo
import com.nodeloc.app.core.design.SecondaryButton
import com.nodeloc.app.core.design.Space
import com.nodeloc.app.core.design.Type

/**
 * Cold-start welcome.
 *
 * "游客浏览" is a first-class choice, not a fallback: reading the whole site
 * needs no account, and the sign-in prompts only appear where an account is
 * genuinely required.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onLogin: () -> Unit,
    onBrowseAsGuest: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Nocturne.bg)
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The wordmark, not `NodelocLoader` and not the name set in type: one
        // is the app's spinner, and the other was a second-hand rendering of a
        // logo the site already draws itself.
        NodelocLogo(height = 52.dp)
        Spacer(Modifier.height(Space.s4))
        Text(
            stringResource(R.string.welcome_tagline),
            style = Type.body(15),
            color = Nocturne.muted(0.55f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        FilledAccentButton(stringResource(R.string.welcome_start), onClick = onGetStarted)
        Spacer(Modifier.height(Space.s3))
        SecondaryButton(
            stringResource(R.string.welcome_have_account),
            // The pair reads as one choice, so they are one size.
            modifier = Modifier.height(50.dp),
            block = true,
            onClick = onLogin,
        )
        Spacer(Modifier.height(Space.s6))
        Text(
            stringResource(R.string.welcome_browse),
            style = Type.body(13),
            color = Nocturne.muted(0.5f),
            modifier = Modifier.clickable(onClick = onBrowseAsGuest).padding(8.dp),
        )
    }
}
