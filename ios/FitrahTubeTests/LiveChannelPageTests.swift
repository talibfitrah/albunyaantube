import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Owner 2026-11-02: live-channel mode reads a channel's new live off its YouTube page on the phone, and the backend
/// decides whether it is served (`?live=`: curation, never a YouTube read). Hand-made fixtures; the network test is opt-in.
@Suite(.perTest)
struct LiveChannelPageTests {
    nonisolated static let channel = "UCOll3M-P7oKs5cSrQ9ytt6g"

    /// The page's `videoDetails`, shaped as the 2026-11-02 probe: `keywords` may sit between `isLive` and `channelId`,
    /// and `shortDescription` always follows `channelId`.
    nonisolated static func details(id: String = "D4Vhq0AJAUs", live: Bool = true, channel: String = channel) -> String {
        #""videoDetails":{"videoId":"\#(id)","title":"ZAD TV","lengthSeconds":"0","isLive":\#(live),"#
            + #""keywords":["zad","makkah"],"channelId":"\#(channel)","shortDescription":"24/7","isLiveContent":true}"#
    }

    nonisolated static let livePage = """
    <html><head><title>ZAD TV - YouTube</title>
    <link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs">
    <meta name="title" content="ZAD TV &amp; Makkah &quot;Live&quot; 24/7">
    </head><body><script>var ytInitialPlayerResponse = {\(details())};</script>
    </body></html>
    """
    nonisolated static let notLivePage = """
    <html><head><link rel="canonical" href="https://www.youtube.com/channel/\(channel)">
    <meta property="og:title" content="ZAD TV"></head></html>
    """
    nonisolated static let consentPage = """
    <html><head><title>Before you continue to YouTube</title></head>
    <body><form action="https://consent.youtube.com/save" method="POST"></form></body></html>
    """
    /// A scheduled stream's waiting room: a watch canonical, but not live now.
    nonisolated static let upcomingPage = """
    <link rel="canonical" href="https://www.youtube.com/watch?v=eC4LfEVxvKg"><meta name="title" content="Soon">
    <script>{"videoDetails":{"videoId":"eC4LfEVxvKg","isLiveContent":true,"isUpcoming":true,"channelId":"\(channel)","shortDescription":""}}</script>
    """

    // MARK: - Parser

    @Test func aWatchCanonicalOfALiveVideoIsTheChannelsLiveWithTheDecodedPageTitle() {
        #expect(LiveChannelPage.parse(Self.livePage, channelId: Self.channel)
                == [.init(id: "D4Vhq0AJAUs", title: "ZAD TV & Makkah \"Live\" 24/7")])
    }

    @Test func ogTitleWinsOverMetaTitle() {
        let html = #"<link rel="canonical" href="https://www.youtube.com/watch?v=eC4LfEVxvKg">"#
            + #"<meta property="og:title" content="Makkah Live"><meta name="title" content="Other">"# + Self.details(id: "eC4LfEVxvKg")
        #expect(LiveChannelPage.parse(html, channelId: Self.channel) == [.init(id: "eC4LfEVxvKg", title: "Makkah Live")])
    }

    @Test func aLiveWithNoTitleMetaHasABlankTitle() {
        let html = #"<link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs">"# + Self.details()
        #expect(LiveChannelPage.parse(html, channelId: Self.channel) == [.init(id: "D4Vhq0AJAUs", title: "")])
    }

    @Test func numericEntitiesInTheTitleAreDecoded() {
        let html = #"<link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs">"#
            + #"<meta name="title" content="Zad&#39;s &#x27;TV&#x27; &#1586; &amp;#39;">"# + Self.details()
        #expect(LiveChannelPage.parse(html, channelId: Self.channel) == [.init(id: "D4Vhq0AJAUs", title: "Zad's 'TV' ز &#39;")])
    }

    @Test func theChannelsOwnCanonicalIsNotLive() {
        #expect(LiveChannelPage.parse(Self.notLivePage, channelId: Self.channel) == [])
        // Even with a live player response on the page: the canonical is the channel's own.
        #expect(LiveChannelPage.parse(Self.notLivePage + Self.details(), channelId: Self.channel) == [])
    }

    nonisolated static let liveCanonical = #"<link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs">"#

    /// I3: the live check reads only the page's own `videoDetails`: its video, its channel, live now.
    @Test(arguments: [
        liveCanonical + details(channel: "UCos52azQNBgW63_9uDJoPDA"),                     // another channel's live
        liveCanonical + details(id: "eC4LfEVxvKg"),                                       // another video's details
        liveCanonical + details(live: false) + #"{"isLive":true}"#,                       // isLive only outside
        liveCanonical + #"{"isLive":true}"# + details(live: false),
        liveCanonical + #"{"isLive":true,"videoId":"D4Vhq0AJAUs","channelId":"UCOll3M-P7oKs5cSrQ9ytt6g"}"#, // no details
        liveCanonical + details().replacingOccurrences(of: #""shortDescription""#, with: #""description""#), // no end
        liveCanonical + #""videoDetails":{"videoId":"D4Vhq0AJAUs","isLive":true,"channelId":"UCOll3M-P7oKs5cSrQ9ytt6g","#
            + String(repeating: "x", count: 64 << 10) + #""shortDescription":""}"#,       // end past the 64 KB window
    ])
    func aLiveOutsideThePagesOwnVideoDetailsIsNoAnswer(html: String) {
        #expect(LiveChannelPage.parse(html, channelId: Self.channel) == nil)
    }

    @Test(arguments: [
        consentPage,
        upcomingPage,                                                                            // P2: not live now
        "",
        "<html>garbage</html>",
        #"<link rel="canonical" href="https://www.youtube.com/watch?v=short">"# + details(id: "short"), // not an 11-char id
        #"<link rel="canonical" href="https://www.youtube.com/watch?v=D4Vhq0AJAUs&list=x">"# + details(),
        #"<link rel="canonical" href="https://www.youtube.com/channel/UCos52azQNBgW63_9uDJoPDA">"#, // another channel
        #"<link rel="canonical" href="https://consent.youtube.com/m">"#,
    ])
    func anythingElseIsNoAnswer(html: String) {
        #expect(LiveChannelPage.parse(html, channelId: Self.channel) == nil)
    }

    // MARK: - Doubles

    private nonisolated final class StubTransport: BoundedHTTPTransport, @unchecked Sendable {
        private let lock = NSLock()
        private let answer: @Sendable () async throws -> HTTPResponse
        private var sent: [(HTTPRequest, Int)] = []
        init(_ answer: @escaping @Sendable () async throws -> HTTPResponse) { self.answer = answer }
        convenience init(status: Int = 200, html: String) {
            self.init { HTTPResponse(status: status, headers: [:], body: Data(html.utf8)) }
        }
        var requests: [HTTPRequest] { lock.withLock { sent.map(\.0) } }
        var caps: [Int] { lock.withLock { sent.map(\.1) } }
        func send(_ request: HTTPRequest, maxBodyBytes: Int) async throws -> HTTPResponse {
            lock.withLock { sent.append((request, maxBodyBytes)) }
            return try await answer()
        }
    }

    /// The backend's `GET /v1/live/channels/{id}[?live=]`: records each `live` it was asked with.
    private nonisolated final class Backend: @unchecked Sendable {
        private let lock = NSLock()
        private var asked: [String?] = []
        private let answer: @Sendable (String?) throws -> LiveChannelLives
        init(_ answer: @escaping @Sendable (String?) throws -> LiveChannelLives) { self.answer = answer }
        convenience init(_ lives: LiveChannelLives) { self.init { _ in lives } }
        var calls: [String?] { lock.withLock { asked } }
        @Sendable func lookup(_ live: String?) async throws -> LiveChannelLives {
            lock.withLock { asked.append(live) }
            return try answer(live)
        }
    }

    nonisolated static let a = LiveChannelLives.Live(id: "eC4LfEVxvKg", title: "Show A")
    nonisolated static let b = LiveChannelLives.Live(id: "D4Vhq0AJAUs", title: "ZAD TV & Makkah \"Live\" 24/7")

    private func lives(_ transport: StubTransport, _ backend: Backend, channel: String = channel,
                       notFound: @escaping @Sendable () async -> Void = {}) async throws -> LiveChannelLives {
        try await LiveChannelPage(transport: transport, sleep: { _ in try await Task.sleep(for: .seconds(3600)) })
            .lives(channelId: channel, backend: backend.lookup, notFound: notFound)
    }

    /// Counts `notFound` reports.
    private nonisolated final class Reports: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var calls: Int { lock.withLock { count } }
        @Sendable func report() async { lock.withLock { count += 1 } }
    }

    // MARK: - Live on the page: the backend decides (`?live=`)

    @Test func aLivePageAsksTheBackendWithTheLiveAndFeedsItsAnswer() async throws {
        let transport = StubTransport(html: Self.livePage)
        let backend = Backend(LiveChannelLives(lives: [Self.b]))
        #expect(try await lives(transport, backend) == LiveChannelLives(lives: [Self.b], partial: true))   // the page's one live
        #expect(backend.calls == ["D4Vhq0AJAUs"])
        let request = try #require(transport.requests.first)
        #expect(request.url.absoluteString == "https://www.youtube.com/channel/\(Self.channel)/live")
        #expect(request.headers["User-Agent"]?.hasPrefix("Mozilla/5.0 (Macintosh;") == true)
        #expect(request.headers["Accept-Language"] == "en")
        #expect(request.headers["Cookie"] == "SOCS=CAE=")     // I4: NewPipe 0.26.5's getCookieHeader(), as Android sends
        #expect(transport.caps.first.map { $0 > 0 && $0 <= 8 << 20 } == true)       // P3: a bounded read
    }

    /// P0: an excluded/blocked/ended B is left out by the backend, and the player never switches to it.
    @Test func aLiveTheBackendLeavesOutIsNeverSwitchedTo() async throws {
        let answer = try await lives(StubTransport(html: Self.livePage), Backend(LiveChannelLives(lives: [])))
        #expect(answer.lives.isEmpty)
        var recovery = LiveChannelRecovery(currentId: Self.a.id, currentTitle: Self.a.title, startedAt: .now)
        guard case .wait = recovery.next(.ended, lives: answer.lives, now: .now) else {
            Issue.record("expected a wait"); return
        }
    }

    /// P0: a channel no longer in a live category stays the backend's 404, as before.
    @Test func theBackends404StaysNotFound() async throws {
        let backend = Backend { _ in throw LiveChannelNotFound() }
        await #expect(throws: LiveChannelNotFound.self) { try await lives(StubTransport(html: Self.livePage), backend) }
        #expect(backend.calls == ["D4Vhq0AJAUs"])
    }

    /// I2: only the page's live counts from the backend's answer: a stale ended current id it still lists never blocks
    /// the switch.
    @Test func aStaleEndedCurrentIdInTheBackendsAnswerNeverBlocksTheSwitch() async throws {
        let answer = try await lives(StubTransport(html: Self.livePage), Backend(LiveChannelLives(lives: [Self.a, Self.b])))
        #expect(answer == LiveChannelLives(lives: [Self.b], partial: true))
        var recovery = LiveChannelRecovery(currentId: Self.a.id, currentTitle: Self.a.title, startedAt: .now)
        #expect(recovery.next(.ended, lives: answer.lives, now: .now) == .switchTo(id: Self.b.id, title: Self.b.title))
    }

    @Test func aBackendAnswerWithoutThePagesLiveIsEmpty() async throws {
        #expect(try await lives(StubTransport(html: Self.livePage), Backend(LiveChannelLives(lives: [Self.a]))).lives.isEmpty)
    }

    /// I1, fail-closed: a backend that cannot answer is no answer (live-channel mode retries); curation is never skipped.
    @Test(arguments: [LiveChannelUnexpectedStatus(statusCode: 503, retryAfter: 60) as any Error,
                      LiveChannelUnexpectedStatus(statusCode: 500, retryAfter: nil), LiveChannelUnexpectedStatus(statusCode: 400, retryAfter: nil),
                      URLError(.notConnectedToInternet), LiveChannelUnsupported()])
    func aBackendThatCannotAnswerIsNoAnswer(error: any Error) async throws {
        let backend = Backend { _ in throw error }
        let thrown = await #expect(throws: (any Error).self) { try await lives(StubTransport(html: Self.livePage), backend) }
        #expect(thrown.map { String(reflecting: $0) } == String(reflecting: error))
        #expect((thrown as? LiveChannelUnexpectedStatus)?.retryAfter == (error as? LiveChannelUnexpectedStatus)?.retryAfter)
        #expect(backend.calls == ["D4Vhq0AJAUs"])
    }

    /// I6b: a cancelled backend call for a live page stays a cancellation.
    @Test func aBackendCancellationIsRethrown() async throws {
        let backend = Backend { _ in throw CancellationError() }
        await #expect(throws: CancellationError.self) { try await lives(StubTransport(html: Self.livePage), backend) }
    }

    /// I6a: a lookup cancelled while the page is still loading never asks the backend.
    @Test func aLookupCancelledDuringThePageFetchNeverAsksTheBackend() async throws {
        let backend = Backend(LiveChannelLives(lives: [Self.a]))
        let started = AsyncStream<Void>.makeStream()
        let hanging = StubTransport {
            started.continuation.yield()
            try await Task.sleep(for: .seconds(3600))
            return HTTPResponse(status: 200, headers: [:], body: Data(Self.livePage.utf8))
        }
        let task = Task { try await lives(hanging, backend) }
        var iterator = started.stream.makeAsyncIterator()
        await iterator.next()
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(backend.calls.isEmpty)
    }

    // MARK: - Not live: an answer, plus one plain backend call it ignores

    /// The fire-and-forget plain call lands after `lives` returns: wait for `calls` to reach `expected` (2 s at most).
    private func settled(_ backend: Backend, at expected: [String?]) async throws -> [String?] {
        for _ in 0..<200 where backend.calls != expected { try await Task.sleep(for: .milliseconds(10)) }
        return backend.calls
    }

    /// The plain call (no `live`) only makes the backend refresh its snapshot of the channel, so an ended live leaves
    /// the LIVE category; the page's "not live" stays the answer, whatever the backend says.
    @Test func aNotLivePageIsAnAnswerAndSendsTheBackendOnePlainCall() async throws {
        let backend = Backend(LiveChannelLives(lives: [Self.a]))
        #expect(try await lives(StubTransport(html: Self.notLivePage), backend).lives.isEmpty)
        #expect(try await settled(backend, at: [nil]) == [nil])
    }

    @Test(arguments: [LiveChannelNotFound() as any Error, LiveChannelUnexpectedStatus(statusCode: 503, retryAfter: 60),
                      URLError(.notConnectedToInternet)])
    func aFailingPlainCallChangesNothing(_ error: any Error) async throws {
        let backend = Backend { _ in throw error }
        #expect(try await lives(StubTransport(html: Self.notLivePage), backend).lives.isEmpty)
        #expect(try await settled(backend, at: [nil]) == [nil])
    }

    /// Cubic K3: the plain call's 404 (the channel left its live category) is reported, so the incident stops at once;
    /// the answer stays "not live".
    @Test func aPlainCall404IsReported() async throws {
        let backend = Backend { _ in throw LiveChannelNotFound() }
        let reports = Reports()
        #expect(try await lives(StubTransport(html: Self.notLivePage), backend, notFound: reports.report).lives.isEmpty)
        for _ in 0..<200 where reports.calls == 0 { try await Task.sleep(for: .milliseconds(10)) }
        #expect(reports.calls == 1)
    }

    /// Any other plain-call answer (a 503, no connection, a list) is ignored, as before.
    @Test(arguments: [LiveChannelUnexpectedStatus(statusCode: 503, retryAfter: 60) as any Error, URLError(.notConnectedToInternet),
                      nil])
    func anyOtherPlainCallAnswerIsNotReported(_ error: (any Error)?) async throws {
        let backend = Backend { _ in if let error { throw error }; return LiveChannelLives(lives: [Self.a]) }
        let reports = Reports()
        #expect(try await lives(StubTransport(html: Self.notLivePage), backend, notFound: reports.report).lives.isEmpty)
        #expect(try await settled(backend, at: [nil]) == [nil])
        try await Task.sleep(for: .milliseconds(100))             // room for a stray report to land
        #expect(reports.calls == 0)
    }

    @Test func aLivePageMakesNoPlainCall() async throws {
        let backend = Backend(LiveChannelLives(lives: [Self.b]))
        _ = try await lives(StubTransport(html: Self.livePage), backend)
        try await Task.sleep(for: .milliseconds(200))             // room for a stray unstructured call to land
        #expect(backend.calls == ["D4Vhq0AJAUs"])
    }

    // MARK: - No answer: the backend as before (no `live`)

    @Test(arguments: [consentPage, upcomingPage])
    func anUnreadablePageAsksTheBackendAsBefore(html: String) async throws {
        let backend = Backend(LiveChannelLives(lives: [Self.a]))
        #expect(try await lives(StubTransport(html: html), backend) == LiveChannelLives(lives: [Self.a]))
        #expect(backend.calls == [nil])
    }

    @Test func aNon2xxAsksTheBackendAsBefore() async throws {
        let backend = Backend(LiveChannelLives(lives: [Self.a]))
        #expect(try await lives(StubTransport(status: 429, html: Self.livePage), backend) == LiveChannelLives(lives: [Self.a]))
        #expect(backend.calls == [nil])
    }

    @Test func aTransportErrorAsksTheBackendAndKeepsItsErrors() async throws {
        let backend = Backend { _ in throw LiveChannelNotFound() }
        await #expect(throws: LiveChannelNotFound.self) {
            try await lives(StubTransport { throw URLError(.notConnectedToInternet) }, backend)
        }
        #expect(backend.calls == [nil])
    }

    /// P3: an id that is not a channel id never reaches a URL.
    @Test(arguments: ["UCx", "UCOll3M-P7oKs5cSrQ9ytt6g/../x", "../watch?v=D4Vhq0AJAUs", "PLOll3M-P7oKs5cSrQ9ytt6g"])
    func anInvalidChannelIdIsNeverFetched(channel: String) async throws {
        let transport = StubTransport(html: Self.livePage)
        let backend = Backend(LiveChannelLives(lives: [Self.a]))
        #expect(try await lives(transport, backend, channel: channel) == LiveChannelLives(lives: [Self.a]))
        #expect(transport.requests.isEmpty)
        #expect(backend.calls == [nil])
    }

    /// The player gives the whole lookup 10 s; a page that never answers must leave the backend time to.
    @Test func aPageThatNeverAnswersAsksTheBackendAfterItsOwnTimeout() async throws {
        let backend = Backend(LiveChannelLives(lives: [Self.a]))
        let hanging = StubTransport { try await Task.sleep(for: .seconds(3600)); throw CancellationError() }
        let page = LiveChannelPage(transport: hanging, sleep: { _ in })
        #expect(try await page.lives(channelId: Self.channel, backend: backend.lookup) == LiveChannelLives(lives: [Self.a]))
        #expect(backend.calls == [nil])
    }

    /// Opt-in live probe (never in the default gate): `TEST_RUNNER_LIVE_CHANNEL_PROBE=1 xcodebuild test-without-building
    /// ... "-only-testing:FitrahTubeTests/LiveChannelPageTests/probeTheRealPage()"`.
    @Test(.enabled(if: ProcessInfo.processInfo.environment["LIVE_CHANNEL_PROBE"] == "1"))
    func probeTheRealPage() async throws {
        let lives = try await LiveChannelPage().lives(channelId: Self.channel) { live in
            print("LIVE_CHANNEL_PROBE backend asked with live=\(live ?? "nil")")
            return LiveChannelLives(lives: [])            // stands in for a backend that serves nothing new
        }
        print("LIVE_CHANNEL_PROBE \(Self.channel) -> \(lives.lives)")
    }
}
