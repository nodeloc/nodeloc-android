package com.nodeloc.app.core.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A node, as the top bar says it: round logo, handle above, name below.
 *
 * Shared by the node page and the reader so a node looks the same wherever the
 * bar names it — they are the same node, and two spellings of it read as two
 * different places.
 */
@Composable
fun HeaderNodeIdentity(
    handle: String,
    name: String,
    logoUrl: String?,
    letter: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logoUrl != null) {
            RemoteAvatar(logoUrl, letter, size = 26.dp)
            Spacer(Modifier.width(6.dp))
        }
        // Capped so a long node name cannot push the bar's own controls off the
        // end of it; the name gives way first, the handle identifies it anyway.
        Column(Modifier.widthIn(max = 168.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // A private message addressed to a group nobody named has no handle
            // to show; an empty line above the name is not a placeholder, it is
            // a name that looks mis-aligned.
            if (handle.isNotBlank()) {
                Text(
                    handle,
                    style = Type.body(11, FontWeight.Medium),
                    color = Nocturne.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                name,
                style = Type.body(11, FontWeight.SemiBold),
                color = LocalHeaderContentColor.current.takeIf { it.isSpecified } ?: Nocturne.headerText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
