package com.nodeloc.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.nodeloc.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "离线 · 上次更新 14:05" above content replayed from disk (issue #1).
 *
 * Only once the network has actually failed: a saved list that is about to be
 * replaced by a fresh one gets no line, which would appear for a moment and
 * shove everything below it down and back up.
 */
@Composable
fun OfflineBanner(savedAt: Long, modifier: Modifier = Modifier) {
    val label = remember(savedAt) { savedAtLabel(savedAt) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Lucide.WifiOff, null, tint = Nocturne.textSecondary, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.offline_saved_at, label), style = Type.body(13), color = Nocturne.textSecondary)
    }
}

/** The time alone when it was today; the date as well when it was not. */
private fun savedAtLabel(savedAt: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(savedAt).atZone(zone)
    val skeleton = if (at.toLocalDate() == LocalDate.now(zone)) "Hm" else "MMMdHm"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
    return DateTimeFormatter.ofPattern(pattern).format(at)
}
