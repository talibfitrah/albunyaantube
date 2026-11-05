package com.albunyaan.tube.data

import com.albunyaan.tube.data.channel.Page
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * A YouTube that answers NewPipe's playlist continuation calls from canned pages, so a repository test runs NewPipe's
 * real parsing offline. Install it with `NewPipe.init(FakeInnerTube())`.
 */
class FakeInnerTube : Downloader() {
    /** Continuation token the request carries -> the answer. */
    val pages = mutableMapOf<String, String>()

    /** Runs on each InnerTube call, e.g. to let a test clock pass the time a page takes. */
    var onCall: () -> Unit = {}

    override fun execute(request: Request): Response {
        // NewPipe reads the web client version off sw.js before its first InnerTube call.
        if (request.url().contains("sw.js")) return Response(200, "", emptyMap(), SW_JS, request.url())
        onCall()
        val body = request.dataToSend()?.let { String(it) }.orEmpty()
        val answer = pages.entries.firstOrNull { body.contains("\"continuation\":\"${it.key}\"") }?.value ?: "{}"
        return Response(200, "", emptyMap(), answer, request.url())
    }

    companion object {
        private const val SW_JS =
            """var x={"INNERTUBE_API_KEY":"AIzaTest","INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260120.01.00"};"""

        /** A continuation answer listing [items] (videoId to its channel's UC id), followed by [next] when there is more. */
        fun continuation(items: List<Pair<String, String>>, next: String? = null): String {
            val rows = items.joinToString(",") { (video, channel) ->
                """{"playlistVideoRenderer":{"videoId":"$video","title":{"runs":[{"text":"Lesson $video"}]},"lengthSeconds":"600",
                   "shortBylineText":{"runs":[{"text":"Shaykh","navigationEndpoint":{"browseEndpoint":{"browseId":"$channel"}}}]}}}"""
            }
            val more = next?.let { """,{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$it"}}}}""" }
                .orEmpty()
            return """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[$rows$more]}}]}"""
        }

        /** The app's page token for the continuation [token]. */
        fun page(token: String) = Page(
            url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            id = null, ids = null, cookies = null,
            body = """{"continuation":"$token"}""".toByteArray(),
        )
    }
}
