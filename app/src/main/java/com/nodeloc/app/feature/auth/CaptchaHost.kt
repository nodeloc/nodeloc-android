package com.nodeloc.app.feature.auth

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nodeloc.app.R
import com.nodeloc.app.core.design.Nocturne
import com.nodeloc.app.core.design.NodelocLoader
import com.nodeloc.app.core.design.Type
import com.nodeloc.app.core.network.ApiCaptchaConfig
import com.nodeloc.app.core.network.Captcha
import com.nodeloc.app.core.network.DiscourseConfig
import java.util.Locale

/**
 * Shows the challenge whenever [Captcha] asks for one. Mounted once at the
 * app root, so sign-in from any screen (the auth pages, the login gate) gets it.
 */
@Composable
fun CaptchaHost() {
    val request by Captcha.requests.collectAsState()
    val current = request ?: return
    // Keyed on the request: a retry is a new challenge with a fresh widget,
    // never the solved one from before (tokens are single-use).
    androidx.compose.runtime.key(current) {
        CaptchaDialog(
            config = current.config,
            onToken = { current.result.complete(it) },
            onDismiss = { current.result.complete(null) },
        )
    }
}

@Composable
private fun CaptchaDialog(config: ApiCaptchaConfig, onToken: (String) -> Unit, onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Nocturne.bg)
                .padding(top = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.captcha_title),
                style = Type.heading(22, FontWeight.Normal),
                color = Nocturne.text,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                failed ?: stringResource(R.string.captcha_body),
                style = Type.body(14),
                color = if (failed != null) Nocturne.danger else Nocturne.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            // Tall enough for hCaptcha's image grid when it escalates past the
            // checkbox; the page itself is transparent around the widget.
            Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.TopCenter) {
                CaptchaWebView(
                    config = config,
                    onReady = { loading = false },
                    onToken = onToken,
                    onError = { failed = it; loading = false },
                )
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        NodelocLoader(height = 24.dp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), style = Type.body(14, FontWeight.Medium), color = Nocturne.accent)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaWebView(
    config: ApiCaptchaConfig,
    onReady: () -> Unit,
    onToken: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val errorText = stringResource(R.string.captcha_failed)
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(480.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        @JavascriptInterface fun ready() { main.post(onReady) }
                        @JavascriptInterface fun token(value: String) { main.post { onToken(value) } }
                        @JavascriptInterface fun error(code: String) { main.post { onError("$errorText ($code)") } }
                    },
                    "NodelocCaptcha",
                )
                // Served as if from the site: the provider checks the page's
                // host against the domains the site key is registered for, and
                // the website is the one already registered.
                loadDataWithBaseURL("${DiscourseConfig.BASE_URL}/", page(config), "text/html", "utf-8", null)
            }
        },
    )
}

/** The widget page. All three providers expose the same explicit `render(container, options)`. */
private fun page(config: ApiCaptchaConfig): String {
    val global = when (config.provider) {
        "turnstile" -> "turnstile"
        "recaptcha" -> "grecaptcha"
        else -> "hcaptcha"
    }
    val script = config.script.ifBlank {
        when (config.provider) {
            "turnstile" -> "https://challenges.cloudflare.com/turnstile/v0/api.js"
            "recaptcha" -> "https://www.google.com/recaptcha/api.js"
            else -> "https://js.hcaptcha.com/1/api.js"
        }
    }
    // The server's hCaptcha URL already names `nlCaptchaLoaded`; the others
    // need the explicit-render and onload parameters added.
    val src = buildString {
        append(script)
        if (!script.contains("onload=")) append(if (script.contains('?')) '&' else '?').append("onload=nlCaptchaLoaded")
        if (!script.contains("render=")) append("&render=explicit")
        if (config.provider != "turnstile") append("&hl=").append(Locale.getDefault().toLanguageTag())
    }
    val key = config.siteKey.replace("\"", "")
    return """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;background:transparent}#c{display:flex;justify-content:center;padding-top:8px}</style>
        </head><body><div id="c"></div>
        <script>
        function nlCaptchaLoaded(){
          try {
            window.$global.render('c', {
              sitekey: "$key",
              callback: function(t){ NodelocCaptcha.token(t); },
              'error-callback': function(e){ NodelocCaptcha.error(String(e || 'error')); },
              'expired-callback': function(){ NodelocCaptcha.error('expired'); }
            });
            NodelocCaptcha.ready();
          } catch (e) { NodelocCaptcha.error(String(e)); }
        }
        </script>
        <script src="${src.replace("\"", "")}" async defer></script>
        </body></html>
    """.trimIndent()
}
