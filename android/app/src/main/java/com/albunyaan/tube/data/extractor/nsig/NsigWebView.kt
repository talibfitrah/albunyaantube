package com.albunyaan.tube.data.extractor.nsig

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Solves YouTube's throttling (`n`) parameter by running the player's own JavaScript inside a
 * hidden [WebView] (used purely as a V8 sandbox — [WebView.getSettings] blocks all network).
 *
 * Why a WebView and not NewPipe: NewPipe v0.26.2's Rhino-based extractor mis-extracts `nsig` on
 * current players — its regexes fall through to a loose pattern that matches a URL-query-appender
 * (`vx`/`VJ`), so it returns the URL unchanged and every web-family dub segment 403s. The current
 * player also restructured `nsig` beyond those regexes. We instead run yt-dlp's vendored
 * AST-based solver ([assets/nsig_solver.js] = meriyah + astring + yt.solver.core) over the full
 * player code; the WebView's Chrome V8 runs it exactly like yt-dlp's deno path (verified
 * off-device: `n=5uljeci-…` → `9WifTCWdsdoW3`). See memory/player-dubs-phase2-spike.md.
 *
 * The WebView lives on the main thread; [solve] suspends the (background) caller until the JS
 * bridge posts the result. Solves are serialized via [solveMutex] — the bundle preprocesses one
 * player per call and the dub path solves at most once per video.
 */
class NsigWebView private constructor(
    context: Context,
) {

    private val webView = WebView(context)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Completes once [assets/nsig_solver.js] has parsed and defined the solver (or fails). */
    private val initialized = CompletableDeferred<Unit>()

    private val solveMutex = Mutex()

    // Read by the JS bridge for the in-flight solve. Guarded by [solveMutex] (one solve at a time).
    @Volatile private var pendingPlayerCode: String = ""
    @Volatile private var pendingChallenges: String = "[]"
    @Volatile private var pendingDeferred: CompletableDeferred<Map<String, String>>? = null

    //region Initialization
    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        // Pure JS sandbox: it only ever runs the player code we hand it; never touches the network.
        settings.blockNetworkLoads = true
        webView.addJavascriptInterface(Bridge(), JS_INTERFACE)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    // The bundle's top level is guarded; an uncaught error at load means the WebView
                    // is too old to parse the modern solver JS — treat as a broken implementation.
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    Log.e(TAG, "Broken WebView for nsig: $fmt")
                    onInitError(BadNsigWebViewException(fmt))
                }
                return super.onConsoleMessage(m)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // The bundle is inlined in the page, so once the page is parsed __jsc + the
                // solveChallenges bridge entry point are defined and we're ready to solve.
                if (!initialized.isCompleted) initialized.complete(Unit)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                // Sandbox renderer killed (OEM low-memory killer on API <= 28, as with poToken).
                // Fail the in-flight solve so the owner recreates+retries instead of timing out.
                Log.e(TAG, "nsig WebView renderer gone (didCrash=${detail?.didCrash()})")
                onInitError(NsigException("WebView renderer process gone"))
                return true
            }
        }
    }

    private suspend fun loadSolver(context: Context) {
        val bundle = withContext(Dispatchers.IO) {
            context.assets.open(SOLVER_ASSET).bufferedReader().use { it.readText() }
        }
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><script>" +
            bundle + "</script></body></html>"
        withContext(Dispatchers.Main.immediate) {
            // baseURL = youtube.com so the solver's location stubs match the real player origin.
            webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
        }
    }
    //endregion

    //region Solving
    /**
     * Transform each `n` challenge to its deobfuscated value using [playerCode] (the full base.js).
     * Returns a challenge -> solved map. Throws on timeout / JS error / a no-op solve (the solver
     * returning the input unchanged, which yt-dlp flags as a failed extraction). Call off the main
     * thread.
     */
    suspend fun solve(playerCode: String, challenges: List<String>): Map<String, String> =
        solveMutex.withLock {
            initialized.await()
            val deferred = CompletableDeferred<Map<String, String>>()
            pendingPlayerCode = playerCode
            pendingChallenges = JSONArray(challenges).toString()
            pendingDeferred = deferred
            try {
                withContext(Dispatchers.Main.immediate) {
                    webView.evaluateJavascript("solveChallenges()", null)
                }
                val result = withTimeout(SOLVE_TIMEOUT_MS) { deferred.await() }
                // A solver that returns the input unchanged means extraction silently failed; the n
                // would still 403. Reject so the caller falls back to VR original (never breaks).
                challenges.forEach { c ->
                    val solved = result[c]
                    if (solved == null || solved == c) {
                        throw NsigException("nsig no-op for '$c' (solver returned '$solved')")
                    }
                }
                result
            } finally {
                pendingDeferred = null
                pendingPlayerCode = ""
            }
        }

    private inner class Bridge {
        @JavascriptInterface
        fun getPlayerCode(): String = pendingPlayerCode

        @JavascriptInterface
        fun getChallenges(): String = pendingChallenges

        @JavascriptInterface
        fun onResult(json: String) {
            val map = try {
                val obj = JSONObject(json)
                val parsed = HashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next() as String
                    parsed[key] = obj.getString(key)
                }
                parsed
            } catch (t: Throwable) {
                pendingDeferred?.completeExceptionally(NsigException("bad nsig result: ${t.message}"))
                return
            }
            pendingDeferred?.complete(map)
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.w(TAG, "nsig solve error from JS: ${message.take(200)}")
            pendingDeferred?.completeExceptionally(NsigException(message.take(300)))
        }
    }
    //endregion

    private fun onInitError(error: Throwable) {
        if (!initialized.isCompleted) initialized.completeExceptionally(error)
        pendingDeferred?.completeExceptionally(error)
        close()
    }

    fun close() {
        runOnMainThread {
            runCatching {
                webView.clearHistory()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            }
            scope.cancel()
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    companion object {
        private const val TAG = "NsigWebView"
        private const val JS_INTERFACE = "NsigBridge"
        private const val SOLVER_ASSET = "nsig_solver.js"
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val SOLVE_TIMEOUT_MS = 60_000L

        /** Create the WebView and wait until the solver bundle has loaded. Call off the main thread. */
        suspend fun create(context: Context): NsigWebView {
            val appContext = context.applicationContext
            val holder = withContext(Dispatchers.Main.immediate) {
                NsigWebView(appContext).also { it.configure() }
            }
            try {
                holder.loadSolver(appContext)
                withTimeout(INIT_TIMEOUT_MS) { holder.initialized.await() }
            } catch (t: Throwable) {
                holder.close()
                throw t
            }
            return holder
        }
    }
}

class NsigException(message: String) : Exception(message)

class BadNsigWebViewException(message: String) : Exception(message)
