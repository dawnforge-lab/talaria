package lab.dawnforge.hermes.feature.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.chat.MediaKind

/** A formula or a diagram, drawn by KaTeX or Mermaid in a WebView, as the iOS app draws them. */
internal enum class MarkupKind { Math, Diagram }

/**
 * A formula or diagram drawn in place. It takes no touches in the transcript (a tap opens it whole);
 * one that can't be drawn in [DRAW_TIMEOUT_MS] (no network, a source the library refuses) shows its
 * source instead. The libraries come from jsDelivr, pinned and checked with the same integrity
 * hashes as the iOS app's; nothing else may load.
 */
@Composable
internal fun MarkupBlock(kind: MarkupKind, source: String) {
    val colors = markupColors()
    val html = remember(kind, source, colors) { markupPage(kind, source, colors) }
    var height by remember(html) { mutableIntStateOf(0) }
    var failed by remember(html) { mutableStateOf(false) }
    var whole by remember { mutableStateOf(false) }
    LaunchedEffect(html) {
        delay(DRAW_TIMEOUT_MS)
        if (height == 0) failed = true
    }
    if (failed) {
        ChatMarkdown("```${if (kind == MarkupKind.Math) "latex" else "mermaid"}\n$source\n```", streaming = false)
        return
    }
    Box(
        Modifier.fillMaxWidth().height(
            if (height ==
                0
            ) {
                PLACEHOLDER_HEIGHT.dp
            } else {
                height.coerceAtMost(MAX_MARKUP_HEIGHT).dp
            },
        ),
    ) {
        SandboxedWebView(
            html = html,
            baseUrl = MARKUP_ORIGIN,
            allowRequest = ::isMarkupLibrary,
            onHeight = { height = it },
            onFailed = { failed = true },
            modifier = Modifier.matchParentSize(),
        )
        if (height == 0) CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
        Box(Modifier.matchParentSize().clickable { whole = true })
    }
    if (whole) {
        WholeScreen(onClose = { whole = false }) {
            SandboxedWebView(html, MARKUP_ORIGIN, ::isMarkupLibrary, onHeight = {
            }, onFailed = {}, zoom = true, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * An HTML page on the host (`::preview{file=…}`), drawn in place at its own height; a tap opens it
 * whole, where it can be used. It runs its scripts, but can't navigate, open windows, frame pages,
 * send forms or start workers: the same policy as the iOS app's. One that can't be read or drawn
 * is a file card.
 */
@Composable
internal fun PagePreview(path: String) {
    val media = LocalMediaActions.current ?: return
    val colors = markupColors()
    val page by produceState<String?>(null, path) {
        val file = media.load(path, null, 0)
        value = file?.bytes?.takeIf { it.size <= MAX_PAGE_BYTES }
            ?.let {
                runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it)).toString() }.getOrNull()
            }
            ?: FAILED
    }
    var height by remember(path) { mutableIntStateOf(0) }
    var failed by remember(path) { mutableStateOf(false) }
    var whole by remember { mutableStateOf(false) }
    val html = page
    LaunchedEffect(html) {
        if (html == null || html == FAILED) return@LaunchedEffect
        delay(DRAW_TIMEOUT_MS)
        if (height == 0) failed = true
    }
    when {
        html == null -> Box(
            Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        html == FAILED || failed -> PageFileCard(path)

        else -> {
            val prepared = remember(html, colors) { previewPage(html, colors) }
            val shown = if (height == 0) PAGE_PLACEHOLDER_HEIGHT else height.coerceIn(MIN_PAGE_HEIGHT, MAX_PAGE_HEIGHT)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(shown.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SandboxedWebView(
                    html = prepared,
                    baseUrl = null,
                    allowRequest = null,
                    onHeight = { height = it },
                    onFailed = { failed = true },
                    modifier = Modifier.matchParentSize(),
                )
                Box(Modifier.matchParentSize().clickable { whole = true })
            }
            if (whole) {
                WholeScreen(onClose = { whole = false }) {
                    SandboxedWebView(prepared, null, null, onHeight = {
                    }, onFailed = {}, interactive = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** A page from the host, whole and usable, under the same policy as [PagePreview]. */
@Composable
internal fun PageSheet(html: String, onClose: () -> Unit) {
    val colors = markupColors()
    val prepared = remember(html, colors) { previewPage(html, colors) }
    WholeScreen(onClose = onClose) {
        SandboxedWebView(prepared, null, null, onHeight = {
        }, onFailed = {}, interactive = true, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PageFileCard(path: String) {
    ReplyFileCard(path, label = null, kind = MediaKind.Page)
}

/** A full-screen sheet for a block or page, with a close button. */
@Composable
private fun WholeScreen(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(top = 48.dp)) { content() }
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feature_chat_close_image))
                }
            }
        }
    }
}

/**
 * A WebView that loads [html] and nothing it isn't allowed: [allowRequest] (when given) decides
 * each request, and no link navigates it. With [interactive], a tapped web link opens in the
 * browser. The page reports its height and failures through `HermesBlock`.
 */
@SuppressLint("SetJavaScriptEnabled") // The formula and diagram libraries, and previews' charts, are scripts.
@Composable
private fun SandboxedWebView(
    html: String,
    baseUrl: String?,
    allowRequest: ((String) -> Boolean)?,
    onHeight: (Int) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
    zoom: Boolean = false,
    interactive: Boolean = false,
) {
    val heightUpdated by rememberUpdatedState(onHeight)
    val failedUpdated by rememberUpdatedState(onFailed)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.Transparent.toArgb())
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.setGeolocationEnabled(false)
                settings.builtInZoomControls = zoom
                settings.displayZoomControls = false
                val main = Handler(Looper.getMainLooper())
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun height(px: Int) {
                            main.post { if (px > 0) heightUpdated(px) }
                        }

                        @JavascriptInterface
                        fun failed() {
                            main.post { failedUpdated() }
                        }
                    },
                    "HermesBlock",
                )
                webViewClient = SandboxClient(context, allowRequest, interactive, page = baseUrl?.let { it to html })
                // A page with an origin is served from it by the client, so its libraries load as from
                // a site; loaded as data with a base URL, the WebView fetched that URL instead.
                if (baseUrl != null) loadUrl(baseUrl) else loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
    )
}

private class SandboxClient(
    private val context: Context,
    private val allowRequest: ((String) -> Boolean)?,
    private val interactive: Boolean,
    /** The page this view shows, by the URL it is served at. */
    private val page: Pair<String, String>?,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (interactive && request.hasGesture() && (url.scheme == "https" || url.scheme == "http")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        page?.takeIf { it.first == url }?.let { (_, html) ->
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
        }
        val allow = allowRequest ?: return null
        if (allow(url)) return null
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            REFUSED,
            "Refused",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}

/** The page's colours, so a formula or diagram reads in the app's theme. */
private data class MarkupColors(
    val foreground: String,
    val muted: String,
    val accent: String,
    val border: String,
    val card: String,
    val dark: Boolean,
)

@Composable
private fun markupColors(): MarkupColors {
    val scheme = MaterialTheme.colorScheme
    return MarkupColors(
        foreground = css(scheme.onSurface),
        muted = css(scheme.onSurfaceVariant),
        accent = css(scheme.primary),
        border = css(scheme.outline),
        card = css(scheme.surfaceVariant),
        dark = isSystemInDarkTheme(),
    )
}

private fun css(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

private fun isMarkupLibrary(url: String): Boolean = url.startsWith(KATEX_DIST) || url == MERMAID_JS

private fun markupPage(kind: MarkupKind, source: String, colors: MarkupColors): String {
    val text = JsonPrimitive(source).toString()
    val head = """<!doctype html><html><head><meta charset="utf-8">""" +
        """<meta name="viewport" content="width=device-width,initial-scale=1">""" +
        "<style>html,body{margin:0;padding:0;background:transparent;color:${colors.foreground};overflow:hidden}" +
        "#out{padding:4px 0}#out>svg{max-width:100%;height:auto}.katex-display{margin:0}</style>"
    val report = "function report(){var box=document.getElementById('out').getBoundingClientRect();" +
        "HermesBlock.height(Math.ceil(box.height)+8)}" +
        "window.onerror=function(){HermesBlock.failed()};"
    return when (kind) {
        MarkupKind.Math ->
            head +
                """<link rel="stylesheet" href="$KATEX_CSS" integrity="$KATEX_CSS_SRI" crossorigin="anonymous">""" +
                """<script src="$KATEX_JS" integrity="$KATEX_JS_SRI" crossorigin="anonymous"></script></head>""" +
                """<body><div id="out"></div><script>$report""" +
                """try{katex.render($text,document.getElementById('out'),""" +
                """{displayMode:true,throwOnError:true,trust:false});""" +
                """report()}catch(e){HermesBlock.failed()}</script></body></html>"""

        MarkupKind.Diagram ->
            head +
                """<script src="$MERMAID_JS" integrity="$MERMAID_SRI" crossorigin="anonymous"></script></head>""" +
                """<body><div id="out"></div><script>$report""" +
                "(async function(){try{mermaid.initialize({startOnLoad:false,securityLevel:'strict'," +
                "suppressErrorRendering:true,theme:'base',themeVariables:{darkMode:${colors.dark}," +
                "background:'transparent',primaryColor:'${colors.card}',primaryTextColor:'${colors.foreground}'," +
                "primaryBorderColor:'${colors.border}',lineColor:'${colors.muted}'," +
                "textColor:'${colors.foreground}'}});" +
                """const r=await mermaid.render('hermes-diagram',$text);""" +
                """document.getElementById('out').innerHTML=r.svg;""" +
                """requestAnimationFrame(report)}catch(e){HermesBlock.failed()}})()</script></body></html>"""
    }
}

/**
 * [html] with the iOS app's content policy first, the app's colours as CSS variables, and a script
 * that reports the page's height. Hermes' desktop pages may call `window.hermes.send`; here it does
 * nothing.
 */
private fun previewPage(html: String, colors: MarkupColors): String {
    val prelude = """<meta http-equiv="Content-Security-Policy" content="$PREVIEW_POLICY">""" +
        "<style>:root{--foreground:${colors.foreground};--muted-foreground:${colors.muted};" +
        "--accent:${colors.accent};--border:${colors.border};--card:${colors.card};" +
        // WebView only honours a dark color-scheme in a dark activity theme: the text colour is set too.
        "color:var(--foreground);color-scheme:${if (colors.dark) "dark" else "light"}}" +
        "html,body{background:transparent;margin:0}body{display:flow-root}</style>" +
        "<script>window.hermes={maxLength:500," +
        "send:function(){return Promise.resolve({ok:false,error:'undelivered'})}};" +
        "addEventListener('load',function(){" +
        // The body's own height: the document's is never less than the view, so it couldn't shrink.
        "var b=document.body;var r=function(){" +
        "HermesBlock.height(Math.ceil(Math.max(b.scrollHeight,b.getBoundingClientRect().height)))};" +
        "r();new ResizeObserver(r).observe(b)});</script>"
    val doctype = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (doctype != null) {
        html.substring(0, doctype.range.last + 1) + prelude + html.substring(doctype.range.last + 1)
    } else {
        prelude + html
    }
}

private const val MARKUP_ORIGIN = "https://hermes.local/"
private const val KATEX_DIST = "https://cdn.jsdelivr.net/npm/katex@0.16.22/dist/"
private const val KATEX_JS = KATEX_DIST + "katex.min.js"
private const val KATEX_CSS = KATEX_DIST + "katex.min.css"
private const val KATEX_JS_SRI = "sha384-cMkvdD8LoxVzGF/RPUKAcvmm49FQ0oxwDF3BGKtDXcEc+T1b2N+teh/OJfpU0jr6"
private const val KATEX_CSS_SRI = "sha384-5TcZemv2l/9On385z///+d7MSYlvIEw9FuZTIdZ14vJLqWphw7e7ZPuOiCHJcFCP"
private const val MERMAID_JS = "https://cdn.jsdelivr.net/npm/mermaid@11.16.0/dist/mermaid.min.js"
private const val MERMAID_SRI = "sha384-T/0lMUdJpd2S1ZHtRiofG3htU3xPCrFVeAQ1UUE2TJwlEJSV5NUwn30kP28n238E"
private const val PREVIEW_POLICY = "connect-src data: blob:; frame-src 'none'; worker-src 'none'; " +
    "object-src 'none'; form-action 'none'; base-uri 'none'"
private const val FAILED = "\u0000failed"
private const val DRAW_TIMEOUT_MS = 20_000L
private const val PLACEHOLDER_HEIGHT = 80
private const val MAX_MARKUP_HEIGHT = 480
private const val PAGE_PLACEHOLDER_HEIGHT = 280
private const val MIN_PAGE_HEIGHT = 60
private const val MAX_PAGE_HEIGHT = 900
private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
private const val REFUSED = 403
