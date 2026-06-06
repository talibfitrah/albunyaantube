package com.albunyaan.tube.data.extractor.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import com.albunyaan.tube.BuildConfig
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe

/**
 * Mints YouTube poTokens by running Google's BotGuard JavaScript inside a hidden, network-blocked
 * [WebView] (the WebView is used purely as a JS VM; the two BotGuard HTTP calls go through the
 * NewPipe downloader). Ported from NewPipe (GPLv3) `org.schabi.newpipe.util.potoken.PoTokenWebView`,
 * with its RxJava flow rewritten with Kotlin coroutines to match this codebase.
 *
 * The WebView lives on the main thread. Provider calls ([generatePoToken]) suspend the (background)
 * extraction coroutine until a JavaScript bridge callback completes the matching deferred.
 */
class PoTokenWebView private constructor(
    context: Context,
) : PoTokenGenerator {

    private val webView = WebView(context)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Completes once the BotGuard `integrityToken` is loaded into the JS context (or fails). */
    private val initialized = CompletableDeferred<Unit>()

    /** identifier -> pending poToken request, resolved by the JS bridge callbacks. */
    private val poTokenDeferreds = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private lateinit var expirationInstant: Instant

    //region Initialization
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.userAgentString = USER_AGENT
        // The WebView is a pure JS sandbox; every network request is performed by the NewPipe
        // downloader, so the WebView itself must never touch the network.
        settings.blockNetworkLoads = true
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    // Everything fallible in po_token.html is guarded by try/catch, so an uncaught
                    // error means the WebView is too old to parse the modern JS — i.e. it's broken.
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    Log.e(TAG, "Broken WebView implementation: $fmt")
                    onInitError(BadWebViewException(fmt))
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private suspend fun loadHtmlAndObtainBotguard(context: Context) {
        val html = withContext(Dispatchers.IO) {
            context.assets.open("po_token.html").bufferedReader().use { it.readText() }
        }
        withContext(Dispatchers.Main.immediate) {
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                html.replaceFirst(
                    "</script>",
                    // run downloadAndRunBotguard() once the page is loaded
                    "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
                ),
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        scope.launch {
            try {
                val responseBody = makeBotguardRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    "[ \"$REQUEST_KEY\" ]",
                )
                val parsedChallengeData = parseChallengeData(responseBody)
                webView.evaluateJavascript(
                    """try {
                        data = $parsedChallengeData
                        runBotGuard(data).then(function (result) {
                            this.webPoSignalOutput = result.webPoSignalOutput
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                        }, function (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        })
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                    null,
                )
            } catch (t: Throwable) {
                onInitError(t)
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, "Initialization error from JavaScript: $error")
        onInitError(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        scope.launch {
            try {
                val responseBody = makeBotguardRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
                )
                val (integrityToken, expirationSeconds) = parseIntegrityTokenData(responseBody)
                // leave 10 minutes of margin just to be sure
                expirationInstant = Instant.now().plusSeconds(expirationSeconds - 600)
                webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "initialization finished, expiration=${expirationSeconds}s")
                    }
                    initialized.complete(Unit)
                }
            } catch (t: Throwable) {
                onInitError(t)
            }
        }
    }
    //endregion

    //region Obtaining poTokens
    override suspend fun generatePoToken(identifier: String): String {
        val deferred = CompletableDeferred<String>()
        poTokenDeferreds[identifier] = deferred
        return try {
            withContext(Dispatchers.Main.immediate) {
                val u8Identifier = stringToU8(identifier)
                webView.evaluateJavascript(
                    """try {
                            identifier = "$identifier"
                            u8Identifier = $u8Identifier
                            poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                            poTokenU8String = ""
                            for (i = 0; i < poTokenU8.length; i++) {
                                if (i != 0) poTokenU8String += ","
                                poTokenU8String += poTokenU8[i]
                            }
                            $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                        } catch (error) {
                            $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                        }""",
                    null,
                )
            }
            withTimeout(GENERATE_TIMEOUT_MS) { deferred.await() }
        } finally {
            poTokenDeferreds.remove(identifier)
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Log.e(TAG, "obtainPoToken error from JavaScript: $error")
        poTokenDeferreds[identifier]?.completeExceptionally(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            poTokenDeferreds[identifier]?.completeExceptionally(t)
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Generated poToken: identifier=$identifier")
        }
        poTokenDeferreds[identifier]?.complete(poToken)
    }

    override fun isExpired(): Boolean = Instant.now().isAfter(expirationInstant)
    //endregion

    //region Utils
    /**
     * POSTs [data] to [url] with the BotGuard headers via the NewPipe downloader. Runs on IO.
     * Throws [PoTokenException] on any non-200 response.
     */
    private suspend fun makeBotguardRequest(url: String, data: String): String =
        withContext(Dispatchers.IO) {
            val response = NewPipe.getDownloader().post(
                url,
                mapOf(
                    "User-Agent" to listOf(USER_AGENT),
                    "Accept" to listOf("application/json"),
                    "Content-Type" to listOf("application/json+protobuf"),
                    "x-goog-api-key" to listOf(GOOGLE_API_KEY),
                    "x-user-agent" to listOf("grpc-web-javascript/0.1"),
                ),
                data.toByteArray(),
            )
            val code = response.responseCode()
            if (code != 200) {
                throw PoTokenException("Invalid BotGuard response code: $code")
            }
            response.responseBody()
        }

    private fun onInitError(error: Throwable) {
        if (!initialized.isCompleted) {
            initialized.completeExceptionally(error)
        }
        poTokenDeferreds.keys.toList().forEach { id ->
            poTokenDeferreds.remove(id)?.completeExceptionally(error)
        }
        close()
    }

    override fun close() {
        runOnMainThread {
            runCatching {
                webView.clearHistory()
                // ensure the WebView isn't doing anything while being destroyed
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            }
            scope.cancel()
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }
    //endregion

    companion object : PoTokenGenerator.Factory {
        private const val TAG = "PoTokenWebView"

        // Public API key used by BotGuard, obtained from inspecting BotGuard requests.
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val JS_INTERFACE = "PoTokenWebView"

        private const val INIT_TIMEOUT_MS = 20_000L
        private const val GENERATE_TIMEOUT_MS = 10_000L

        override suspend fun newPoTokenGenerator(context: Context): PoTokenGenerator {
            val appContext = context.applicationContext
            val webViewHolder = withContext(Dispatchers.Main.immediate) {
                PoTokenWebView(appContext).also { it.configure() }
            }
            try {
                webViewHolder.loadHtmlAndObtainBotguard(appContext)
                withTimeout(INIT_TIMEOUT_MS) { webViewHolder.initialized.await() }
            } catch (t: Throwable) {
                webViewHolder.close()
                throw t
            }
            return webViewHolder
        }
    }
}
