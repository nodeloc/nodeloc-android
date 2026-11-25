package com.nodeloc.app.feature.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.nodeloc.app.R
import com.nodeloc.app.ServiceLocator
import com.nodeloc.app.core.design.CountBadge
import com.nodeloc.app.core.design.HeaderIconButton

/**
 * The root screens' hamburger. For a moderator it carries the pending-review
 * count (design: the menu button shows 待处理数), because the drawer is where
 * the managed nodes and the dashboard live.
 */
@Composable
fun DrawerMenuButton(onClick: () -> Unit) {
    val pending by ServiceLocator.get.moderation.menuBadge.collectAsState()
    Box {
        HeaderIconButton(Lucide.Menu, stringResource(R.string.common_menu), onClick = onClick)
        CountBadge(pending, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 8.dp))
    }
}
