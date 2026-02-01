package com.mobileai.notes.ui.widgets

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val html = remember(text) { buildHtml(text) }
    val density = LocalDensity.current
    val densityState = rememberUpdatedState(density)
    var contentHeightDp by remember(text) { mutableStateOf(1.dp) }
    val onHeightReport = rememberUpdatedState<(Double) -> Unit> { heightCssPx ->
        val hPx = heightCssPx.toFloat().coerceAtLeast(1f)
        contentHeightDp = with(densityState.value) { hPx.toDp() }
    }
    LaunchedEffect(html) {
        // Avoid keeping a stale height when rendering a new expression.
        contentHeightDp = 1.dp
    }
    AndroidView(
        modifier = modifier.then(Modifier.height(contentHeightDp.coerceAtLeast(1.dp))),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setBackgroundColor(0x00000000)
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.allowFileAccess = true
                settings.allowContentAccess = true

                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onHeight(height: Double) {
                            // JavaScriptInterface may be invoked off the UI thread on some devices.
                            post { onHeightReport.value(height) }
                        }
                    },
                    "AndroidBridge",
                )
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(
                    "file:///android_asset/",
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

private fun buildHtml(text: String): String {
    val normalized = normalizeLatex(text.trim()).replace(Regex("""\n{3,}"""), "\n\n")
    val safe = escapeHtml(normalized)
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0" />
          <link rel="stylesheet" href="katex/katex.min.css" />
          <style>
            body { margin: 0; padding: 0; font-family: sans-serif; color: #1c1b1f; }
            #content { font-size: 16px; line-height: 1.5; white-space: pre-wrap; }
            .katex-display { margin: 0.2em 0; text-align: left; }
            .katex-display > .katex { text-align: left; }
          </style>
        </head>
        <body>
          <div id="content">$safe</div>
          <script src="katex/katex.min.js"></script>
          <script src="katex/auto-render.min.js"></script>
          <script>
            (function() {
              function reportHeight() {
                try {
                  var dpr = (window.devicePixelRatio || 1);
                  var h = Math.max(
                    document.body.scrollHeight || 0,
                    document.documentElement.scrollHeight || 0
                  );
                  if (window.AndroidBridge && window.AndroidBridge.onHeight) {
                    // Send physical pixels so Android side can convert reliably.
                    window.AndroidBridge.onHeight(h * dpr);
                  }
                } catch (e) {}
              }

              function renderNow() {
                var el = document.getElementById('content');
                if (!el) return;

                var canRender = (typeof renderMathInElement === 'function');
                if (canRender) {
                  try {
                    renderMathInElement(el, {
                      delimiters: [
                        {left: "$$", right: "$$", display: true},
                        {left: "$", right: "$", display: false}
                      ],
                      throwOnError: false
                    });
                  } catch (e) {}
                }

                // Always report height (even if KaTeX failed to load) to avoid blank space.
                try {
                  setTimeout(reportHeight, 0);
                  setTimeout(reportHeight, 50);
                  setTimeout(reportHeight, 200);
                } catch (e) {}

                // If the auto-render script hasn't initialized yet, retry a bit later.
                if (!canRender) {
                  try {
                    setTimeout(renderNow, 80);
                    setTimeout(renderNow, 300);
                  } catch (e) {}
                }
              }

              if (document.readyState === 'complete') {
                renderNow();
              } else {
                window.addEventListener('load', renderNow);
              }

              window.addEventListener('resize', function() { setTimeout(reportHeight, 0); });
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun normalizeLatex(text: String): String {
    // Many models output JSON strings with extra escaping:
    // - JSON needs "\\" to represent a single "\".
    // - Some models double-escape again, resulting in "\\lim" in the final text.
    //
    // Goal: turn common sequences like "\\lim" / "\\frac" / "\\to" into "\lim" / "\frac" / "\to".
    // Keep LaTeX linebreak "\\" (when NOT followed by a letter) as-is.
    var t = text

    // First, reduce long backslash runs (e.g., "\\\\lim" -> "\\lim").
    while (t.contains("\\\\\\\\")) {
        t = t.replace("\\\\\\\\", "\\\\")
    }

    // Then, collapse double-backslash before commands/delimiters.
    t =
        t
            // Commands like \\lim, \\frac, \\to ...
            .replace(Regex("""\\\\(?=[A-Za-z])"""), """\""")
            // Common math delimiters \\( \\) \\[ \\]
            .replace("\\\\(", "\\(")
            .replace("\\\\)", "\\)")
            .replace("\\\\[", "\\[")
            .replace("\\\\]", "\\]")

    // Support \( \) and \[ \] from some generators.
    t = t.replace("\\(", "$").replace("\\)", "$")
    t = t.replace("\\[", "$$").replace("\\]", "$$")
    return t
}

private fun escapeHtml(text: String): String {
    return buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
