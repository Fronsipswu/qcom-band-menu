package dev.qcom.bandmenu.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.qcom.bandmenu.network.AttemptMeasurement
import dev.qcom.bandmenu.network.AttemptOutcome
import dev.qcom.bandmenu.network.BrowserTestTask
import dev.qcom.bandmenu.network.DataTestKind
import dev.qcom.bandmenu.network.PageLoadTracker
import dev.qcom.bandmenu.network.PageResourceStream
import dev.qcom.bandmenu.network.YoutubePlaybackTracker
import dev.qcom.bandmenu.network.YoutubeSnapshot
import dev.qcom.bandmenu.network.validHttpUrl
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.json.JSONTokener
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** A visible foreground browser belongs to exactly one attempt, including its callbacks. */
@Composable
fun BrowserTestDialog(task: BrowserTestTask?, onStop: () -> Unit) {
    if (task == null) return
    key(task.id) { ActiveBrowserTest(task, onStop) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ActiveBrowserTest(task: BrowserTestTask, onStop: () -> Unit) {
    val c = task.config
    val streaming = c.kind == DataTestKind.STREAMING
    val startedMs = remember { SystemClock.elapsedRealtime() }
    val page = remember { PageLoadTracker(c.effectiveTransferBytes, startedMs) }
    val player = remember { YoutubePlaybackTracker(c.effectiveVideoMinimumSeconds, c.effectiveVideoHeight, startedMs) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val released = remember { AtomicBoolean(false) }
    var pageFinished by remember { mutableStateOf(false) }
    var qualityConfirmed by remember { mutableStateOf(false) }
    val confirmedQuality = remember { AtomicBoolean(false) }
    var progress by remember { mutableStateOf("Opening ${if (streaming) "YouTube player" else "reference webpage"}…") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun cancel(reason: String) {
        if (!task.result.isCompleted) task.finish(task.latest.copy(error = reason), AttemptOutcome.CANCELLED)
    }
    fun fail(reason: String, instrument: Boolean = false) {
        if (task.control.isCancelled || task.result.isCompleted) return
        if (streaming) task.finish(task.latest.copy(error = reason, contentVerified = false,
            measurementAvailable = task.latest.measurementAvailable && !instrument), AttemptOutcome.FAILED)
        else page.fail(reason, instrument)
    }
    fun verifyPageComplete(view: WebView) {
        view.evaluateJavascript("JSON.stringify({ready:document.readyState==='complete',images:Array.from(document.images).every(function(i){return i.complete&&i.naturalWidth>0;})})") { raw ->
            if (!task.result.isCompleted && !task.control.isCancelled) {
                val state = decodeJavascriptObject(raw)
                if (state == null) page.fail("Cannot read webpage completion state from the browser", instrument = true)
                if (state?.optBoolean("ready") == true && !state.optBoolean("images")) page.fail("A webpage image failed to render")
                page.result(state?.optBoolean("ready") == true, SystemClock.elapsedRealtime())?.let {
                    task.latest = it.first
                    task.finish(it.first, it.second)
                }
            }
        }
    }

    DisposableEffect(task.id, lifecycle) {
        task.snapshot = {
            val now = SystemClock.elapsedRealtime()
            if (streaming) player.measurement(now, confirmedQuality.get()) else page.measurement(now)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                webView?.stopLoading()
                webView?.onPause()
                cancel("Browser test interrupted because the app left the foreground")
                onStop()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            released.set(true)
            lifecycle.removeObserver(observer)
            val incomplete = !task.result.isCompleted
            cancel("Browser closed before the attempt completed")
            if (incomplete) onStop()
            task.snapshot = null
            webView?.let { view ->
                view.stopLoading()
                view.onPause()
                view.removeAllViews()
                view.destroy()
            }
            webView = null
        }
    }

    LaunchedEffect(task.id, webView) {
        val view = webView ?: return@LaunchedEffect
        while (!task.result.isCompleted && !task.control.isCancelled) {
            val now = SystemClock.elapsedRealtime()
            if (streaming) {
                view.evaluateJavascript("JSON.stringify(window.qbmSnapshot ? window.qbmSnapshot() : {ready:false})") { raw ->
                    if (!task.result.isCompleted && !task.control.isCancelled) {
                        val snapshot = YoutubeSnapshot.parseJavascript(raw)
                        val decoded = decodeJavascriptObject(raw)
                        if (decoded == null || (decoded.optBoolean("ready") && snapshot == null))
                            fail("Cannot read valid timing data from the YouTube player", instrument = true)
                        if (snapshot != null) {
                            val measured = player.update(SystemClock.elapsedRealtime(), snapshot, qualityConfirmed)
                            task.latest = measured
                            progress = when {
                                snapshot.autoplayBlocked && !measured.playbackStarted -> "Tap Play in the player to begin."
                                measured.playbackStarted -> "${snapshot.positionSeconds.toInt()} / ${snapshot.durationSeconds.toInt()} s • buffering ${measured.freezeDurationMs / 1000.0} s"
                                else -> "Waiting for playback to start…"
                            }
                            when {
                                measured.error != null -> task.finish(measured, AttemptOutcome.FAILED)
                                measured.playbackCompleted -> task.finish(measured, AttemptOutcome.SUCCESS)
                            }
                        }
                    }
                }
                task.latest = player.measurement(now, qualityConfirmed)
            } else {
                task.latest = page.measurement(now)
                progress = "${task.latest.bytes} / ${c.effectiveTransferBytes} bytes • ${(task.latest.durationMs / 1000).toInt()} s"
                page.result(false, now)?.let { task.finish(it.first, it.second) }
                if (pageFinished) {
                    verifyPageComplete(view)
                }
            }
            delay(250)
        }
        view.stopLoading()
        view.onPause()
    }

    Dialog(onDismissRequest = { cancel("Browser test stopped by operator"); onStop() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)
            .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(c.kind.title, style = MiuixTheme.textStyles.title3)
            Text(if (streaming)
                "Keep this player visible. Play the entire video at normal speed without seeking or pausing. Avoid other network activity."
            else "Loading the complete reference page and its resources over the selected cellular network.",
                style = MiuixTheme.textStyles.body2)
            AndroidView(modifier = Modifier.fillMaxWidth().height(260.dp), factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    keepScreenOn = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = streaming
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mediaPlaybackRequiresUserGesture = false
                    clearCache(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (!streaming && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                                (message.message().contains("Content Security Policy", ignoreCase = true) || message.message().startsWith("Uncaught ")))
                                fail("Unsupported webpage script: ${message.message().take(180)}", instrument = true)
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { pageFinished = false }
                        override fun onPageFinished(view: WebView, url: String) {
                            if (!streaming && url != "about:blank" && !task.result.isCompleted && !task.control.isCancelled) {
                                pageFinished = true
                                page.documentFinished(SystemClock.elapsedRealtime())
                                verifyPageComplete(view)
                            }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (request.isForMainFrame && (!streaming || !request.url.toString().startsWith(PLAYER_ORIGIN))) {
                                fail("Navigation away from the configured test page is unsupported", instrument = true)
                                return true
                            }
                            return false
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (streaming) null else interceptPage(task, page, request)
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (!streaming || request.isForMainFrame) fail("Browser resource failed (${error.errorCode}): ${error.description}",
                                instrument = error.errorCode == ERROR_UNSUPPORTED_SCHEME)
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (!streaming || request.isForMainFrame) fail("Browser HTTP error ${response.statusCode}")
                        }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            fail("Browser TLS certificate validation failed")
                        }
                        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                            fail("Browser renderer stopped before completion", instrument = true)
                            return true
                        }
                    }
                    setDownloadListener { _, _, _, _, _ -> fail("A download was requested instead of a rendered webpage", instrument = true) }
                    webView = this
                    val ownedView = this
                    task.control.track(Closeable { ownedView.post {
                        if (!released.get()) { ownedView.stopLoading(); ownedView.onPause() }
                    } })
                    if (streaming) loadDataWithBaseURL(PLAYER_ORIGIN, youtubeHtml(c.videoId), "text/html", "UTF-8", null)
                    else loadUrl(c.url)
                }
            })
            Text(progress, style = MiuixTheme.textStyles.body2)
            if (streaming) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(state = ToggleableState(qualityConfirmed), onClick = {
                        qualityConfirmed = !qualityConfirmed
                        confirmedQuality.set(qualityConfirmed)
                    })
                    Text("I set and verified ${c.effectiveVideoHeight}p in this player's Quality menu for this attempt.",
                        style = MiuixTheme.textStyles.body2)
                }
                Text("Resolution is operator-confirmed. Without confirmation the result stays unverified.",
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TextButton("Stop test", onClick = { cancel("Browser test stopped by operator"); onStop() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Strict reference-page mode: every network resource must be an uncached, successful GET. */
private fun interceptPage(task: BrowserTestTask, tracker: PageLoadTracker, request: WebResourceRequest): WebResourceResponse {
    tracker.begin(SystemClock.elapsedRealtime())
    var connection: HttpURLConnection? = null
    try {
        task.control.check()
        if (request.method != "GET") throw BrowserInstrumentationException("Unsupported webpage request method ${request.method}")
        val url = request.url.toString()
        if (!validHttpUrl(url)) throw BrowserInstrumentationException("Unsupported resource URL; only HTTP(S) without credentials or fragments is measurable")
        val conn = task.control.track(task.io.openHttp(URL(url)))
        connection = conn
        conn.connectTimeout = task.config.effectiveTimeoutSeconds * 1000
        conn.readTimeout = task.config.effectiveTimeoutSeconds * 1000
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        for ((name, value) in request.requestHeaders) {
            if (name.lowercase() !in setOf("host", "connection", "accept-encoding", "if-none-match", "if-modified-since"))
                conn.setRequestProperty(name, value)
        }
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-cache, no-store")
        conn.setRequestProperty("Pragma", "no-cache")
        val status = conn.responseCode
        if (request.isForMainFrame) tracker.mainStatus(status)
        if (status in 300..399) throw BrowserInstrumentationException("Redirects are unsupported; configure the final page and resource URLs")
        if (status !in 200..299) throw IOException("Page resource returned HTTP $status")
        val encoding = conn.getHeaderField("Content-Encoding")
        if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true))
            throw BrowserInstrumentationException("Resource compression ignored Accept-Encoding: identity; byte size is unverifiable")
        val contentLength = conn.getHeaderField("Content-Length")
        val length = contentLength?.toLongOrNull()
        if (contentLength != null && (length == null || length < 0)) throw BrowserInstrumentationException("Invalid resource Content-Length")
        val contentType = conn.contentType.orEmpty()
        val mime = contentType.substringBefore(';').ifBlank { "application/octet-stream" }
        val charset = Regex("charset\\s*=\\s*[\"']?([^;\"' ]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        val headers = conn.headerFields.entries.filter { it.key != null }.associate { it.key to it.value.joinToString(", ") }.toMutableMap()
        headers["Cache-Control"] = "no-store"
        if (mime.contains("html", true)) {
            val old = headers.entries.firstOrNull { it.key.equals("Content-Security-Policy", true) }?.value
            headers.keys.filter { it.equals("Content-Security-Policy", true) }.toList().forEach(headers::remove)
            headers["Content-Security-Policy"] = listOfNotNull(old,
                "connect-src http: https:; worker-src 'none'; form-action 'none'").joinToString(", ")
        }
        val input = task.control.track(PageResourceStream(conn.inputStream, length, tracker,
            SystemClock::elapsedRealtime) { conn.disconnect() })
        return WebResourceResponse(mime, charset, status, conn.responseMessage?.takeIf { it.isNotBlank() } ?: "OK", headers, input)
    } catch (e: Exception) {
        connection?.disconnect()
        tracker.end(SystemClock.elapsedRealtime(), e.message ?: "Page resource request failed",
            instrument = e is BrowserInstrumentationException || e !is IOException)
        return WebResourceResponse("text/plain", "UTF-8", 502, "Resource failed", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}

private fun decodeJavascriptObject(raw: String): JSONObject? = runCatching {
    when (val value = JSONTokener(raw).nextValue()) { is String -> JSONObject(value); is JSONObject -> value; else -> null }
}.getOrNull()

private const val PLAYER_ORIGIN = "https://dev.qcom.bandmenu"
private class BrowserInstrumentationException(message: String) : IOException(message)

private fun youtubeHtml(videoId: String): String {
    val id = JSONObject.quote(videoId)
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <style>html,body{margin:0;width:100%;height:100%;background:#000}#player{width:100%;height:100%;min-width:200px;min-height:200px}</style></head>
        <body><div id="player"></div><script>
        var player=null,ready=false,playerError=0,autoplayBlocked=false;
        function onYouTubeIframeAPIReady(){player=new YT.Player('player',{width:'100%',height:'100%',videoId:$id,
          playerVars:{autoplay:1,controls:1,playsinline:1,origin:'$PLAYER_ORIGIN',fs:0,rel:0},
          events:{onReady:function(e){ready=true;e.target.playVideo();},
            onError:function(e){playerError=e.data;},onAutoplayBlocked:function(){autoplayBlocked=true;}}});}
        window.qbmSnapshot=function(){return {ready:ready||playerError!==0,error:playerError,autoplayBlocked:autoplayBlocked,
          state:ready?player.getPlayerState():-1,position:ready?player.getCurrentTime():0,
          duration:ready?player.getDuration():0,loaded:ready?player.getVideoLoadedFraction():0,rate:ready?player.getPlaybackRate():1};};
        </script><script src="https://www.youtube.com/iframe_api"></script></body></html>""".trimIndent()
}
