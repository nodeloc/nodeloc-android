package com.nodeloc.app.feature.media

import android.content.Context
import android.content.Intent
import com.nodeloc.app.R

internal fun shareWebUrl(context: Context, url: String, title: String?) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, if (title.isNullOrBlank()) url else "$title\n$url")
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.common_share)))
    }
}
