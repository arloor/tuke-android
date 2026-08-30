package com.arloor.tuke.feature.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

@Composable
internal fun NativeMermaidDiagram(
    source: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val backgroundColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val darkTheme = backgroundColor.luminance() < 0.5f
    var webView by remember { mutableStateOf<MermaidWebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var rendered by remember(source, darkTheme) { mutableStateOf(false) }
    var renderError by remember(source, darkTheme) { mutableStateOf<String?>(null) }
    var diagramHeight by remember(source) { mutableStateOf(MERMAID_INITIAL_HEIGHT) }
    val bridge = remember {
        MermaidJavaScriptBridge(
            readyCallback = { ready = true },
            heightCallback = { cssPixels ->
                diagramHeight = cssPixels.dp.coerceIn(
                    MERMAID_MIN_HEIGHT,
                    MERMAID_MAX_HEIGHT,
                )
            },
            renderedCallback = {
                rendered = true
                renderError = null
            },
            errorCallback = { message ->
                webView?.apply {
                    removeJavascriptInterface(MERMAID_BRIDGE_NAME)
                    stopLoading()
                    destroy()
                }
                webView = null
                renderError = message
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                removeJavascriptInterface(MERMAID_BRIDGE_NAME)
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    if (renderError != null) {
        MermaidRenderFallback(
            source = source,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(diagramHeight)
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .testTag("agent.mermaid-diagram"),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                createMermaidWebView(context, bridge).also {
                    ready = false
                    webView = it
                    it.loadUrl(MERMAID_ASSET_URL)
                }
            },
            update = { view ->
                if (ready) {
                    val requestKey = source to darkTheme
                    if (view.appliedRequest != requestKey) {
                        rendered = false
                        renderError = null
                        view.appliedRequest = requestKey
                        view.evaluateJavascript(
                            "window.renderMermaid(${JSONObject.quote(source)}, $darkTheme);",
                            null,
                        )
                    }
                }
            },
        )
        if (!rendered) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun MermaidRenderFallback(
    source: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        Text(
            text = "流程图渲染失败，已显示 Mermaid 源码。",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        NativeMarkdownCodeBlock(
            codeBlock = MarkdownCodeBlock(source, "mermaid"),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMermaidWebView(
    context: Context,
    bridge: MermaidJavaScriptBridge,
): MermaidWebView = MermaidWebView(context).apply {
    setBackgroundColor(AndroidColor.TRANSPARENT)
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    isVerticalScrollBarEnabled = true
    isHorizontalScrollBarEnabled = true
    overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowContentAccess = false
        allowFileAccess = true
        allowFileAccessFromFileURLs = true
        allowUniversalAccessFromFileURLs = false
        blockNetworkLoads = true
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = request.url.toString() != MERMAID_ASSET_URL
    }
    addJavascriptInterface(bridge, MERMAID_BRIDGE_NAME)
}

private class MermaidWebView(context: Context) : WebView(context) {
    var appliedRequest: Pair<String, Boolean>? = null
}

private class MermaidJavaScriptBridge(
    private val readyCallback: () -> Unit,
    private val heightCallback: (Int) -> Unit,
    private val renderedCallback: () -> Unit,
    private val errorCallback: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady() {
        mainHandler.post { readyCallback() }
    }

    @JavascriptInterface
    fun onHeight(cssPixels: Int) {
        mainHandler.post { heightCallback(cssPixels) }
    }

    @JavascriptInterface
    fun onRendered() {
        mainHandler.post { renderedCallback() }
    }

    @JavascriptInterface
    fun onError(message: String) {
        mainHandler.post { errorCallback(message) }
    }
}

private const val MERMAID_BRIDGE_NAME = "AndroidMermaid"
private const val MERMAID_ASSET_URL = "file:///android_asset/mermaid.html"
private val MERMAID_INITIAL_HEIGHT = 160.dp
private val MERMAID_MIN_HEIGHT = 96.dp
private val MERMAID_MAX_HEIGHT = 720.dp
