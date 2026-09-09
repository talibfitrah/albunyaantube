import Foundation
import InnerTubeKit
import Observation
import SwiftUI
import Synchronization
@testable import FitrahTube

/// Shared test doubles and fixtures (gate B2-3). Each of these was copy-pasted verbatim into three
/// or more test files -- `FakeFilterStore` into four, and two of the three `Gate` doc comments
/// literally said "same shape as `HomeViewModelTests.Gate`", i.e. the duplication was noticed and
/// left in place. Nothing enforced that the copies stayed in sync, so one `FilterStore` protocol
/// change or one `ContentItem` initializer change meant editing N independent copies and hoping.
///
/// Same target as every test file, so no import is needed at the use site.

/// In-memory `FilterStore` with `UserDefaultsFilterStore`'s empty-string-is-nil normalisation.
@MainActor @Observable final class FakeFilterStore: FilterStore {
    private(set) var state: FilterState

    init(state: FilterState = FilterState()) { self.state = state }

    func setCategory(id: String?, name: String?) {
        let id = id?.isEmpty == true ? nil : id
        state.categoryId = id
        state.categoryName = id == nil ? nil : name
    }

    func clearCategory() { setCategory(id: nil, name: nil) }
}

/// A rendezvous point: `block()` suspends until `release()` is called; `waitUntilBlocked()`
/// suspends until some caller has actually entered `block()` -- whichever of the two arrives first
/// at the actor just hands off to the other, so there is no timing race either way. Lets a test
/// observe a ViewModel's state *while an await is genuinely in flight*, with no real sleeping.
actor Gate {
    private var blockedContinuation: CheckedContinuation<Void, Never>?
    private var releaseContinuation: CheckedContinuation<Void, Never>?

    func block() async {
        await withCheckedContinuation { continuation in
            releaseContinuation = continuation
            blockedContinuation?.resume()
            blockedContinuation = nil
        }
    }

    func waitUntilBlocked() async {
        if releaseContinuation != nil { return }
        await withCheckedContinuation { blockedContinuation = $0 }
    }

    func release() {
        releaseContinuation?.resume()
        releaseContinuation = nil
    }
}

/// `count` video items with ids `"<prefix>-0"`, `"<prefix>-1"`, … -- the fixture every list/search
/// ViewModel suite builds its canned pages from.
func items(count: Int, prefix: String) -> [ContentItem] {
    (0..<count).map { i in
        ContentItem(id: "\(prefix)-\(i)", type: .video, title: "Item \(prefix)-\(i)", category: nil,
                    description: nil, thumbnailURL: nil, durationSeconds: 60, uploadedDaysAgo: 1,
                    viewCount: nil, channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil)
    }
}

/// Debounce-clock stub: tests assert on the requested `Duration`, never wait out real time.
func noSleep(_ duration: Duration) async throws {}

/// Which branch a `@ViewBuilder` switch actually chose, by name. `_ConditionalContent<A, B>` stores
/// `.trueContent(A)` / `.falseContent(B)`, so this descends until the subject is no longer one of
/// them and reports the leaf's type.
///
/// Lived as a `private func` on `MainShellRoutingTests` until Task 25 needed the same walk for
/// `MySubmissionsScreen.stateView(_:)`'s four arms — the M6 move, not a second copy.
@MainActor func leafTypeName(of view: some SwiftUI.View) -> String {
    var mirror = Mirror(reflecting: view)
    while String(describing: mirror.subjectType).hasPrefix("_ConditionalContent"),
          let storage = mirror.children.first(where: { $0.label == "storage" }) {
        let payload = Mirror(reflecting: storage.value)
        guard let inner = payload.children.first else { break }
        mirror = Mirror(reflecting: inner.value)
    }
    return String(describing: mirror.subjectType)
}

/// Task 5: the `OAuthSignInProvider` double. Canned credential, no SDK, no UI, no network.
///
/// `isAvailable == false` FAILS instead of returning a credential, so "an unavailable provider is
/// never asked" (ruling F11) is a property a caller's test can actually break: a screen that asks
/// one anyway gets an error, not a silent success. `presentCount` is how a caller's test proves it
/// was not asked at all.
/// Task 10 adds two knobs, both defaulted so every existing call site is unchanged: `error` now
/// also fails an AVAILABLE provider (the cancel and SDK-failure legs -- `isAvailable == false` can
/// only ever express "never asked"), and `gate` holds the flow open so a caller's re-entrancy guard
/// is testable with no clock.
@MainActor final class FakeOAuthProvider: OAuthSignInProvider {
    let isAvailable: Bool
    let credential: OAuthCredential
    /// `var`: a suite that needs the SECOND presentation to be refused (a delete confirmation the
    /// user backs out of) cannot rebuild the provider mid-flow.
    var error: OAuthSignInFailure?
    let gate: Gate?
    private(set) var presentCount = 0

    init(isAvailable: Bool = true,
         credential: OAuthCredential = OAuthCredential(providerID: "google.com",
                                                       idToken: "fake-id-token",
                                                       accessTokenOrNonce: "fake-access-token"),
         error: OAuthSignInFailure? = nil,
         gate: Gate? = nil) {
        self.isAvailable = isAvailable
        self.credential = credential
        self.error = error
        self.gate = gate
    }

    /// Stage 4 / I1: how many times the provider SDK was asked to forget its own session.
    private(set) var signOutCount = 0

    func signOutProvider() { signOutCount += 1 }

    func presentSignIn() async throws(OAuthSignInFailure) -> OAuthCredential {
        presentCount += 1
        if let gate { await gate.block() }
        guard isAvailable else { throw error ?? .failed(.googleSignInFailed) }
        if let error { throw error }
        return credential
    }
}

// MARK: - Live-leg InnerTube doubles (three byte-identical copies before the Phase 3 fold-in)

/// The backend availability gate, always affirmative — the live-gated suites talk to YouTube
/// directly and must not depend on a running backend.
struct AlwaysAvailable: AvailabilityGate {
    func verify(videoId: String, sourceChannelId: String?) async throws -> Bool { true }
}

/// `KeyValueStore` in memory: no `UserDefaults` domain to leak between suites.
nonisolated final class MemoryKV: KeyValueStore, @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String: Data] = [:]
    func get(_ key: String) -> Data? { lock.withLock { storage[key] } }
    func set(_ key: String, _ value: Data) { lock.withLock { storage[key] = value } }
}

// MARK: - The scripted `SyncTransporting`

/// Task 22 declared `SyncTransporting` as "the seam Task 23's tests script" — so this double sits at
/// that seam, not at `HTTPTransport`: `SyncClient`'s query names, paths and cursor-id validator are
/// `SyncClientTests`' subject and re-proving them through every manager test would couple the two.
///
/// Shared by `SyncMutexTests` and `SyncManagerTests` -- which is why it lives here rather than
/// beside one of them (Task 23 review M6). Responses are
/// consumed in order and running the queue dry THROWS — a silent repeat would let an unexpected
/// extra request pass unnoticed, which is exactly what `ScriptedTransport` refuses for the same
/// reason.
final class ScriptedSyncClient: SyncTransporting {

    enum Call: Equatable, Sendable {
        case pull
        case put(SyncEntityType, String)
        case delete(SyncEntityType, String)
    }

    enum PullReply: Sendable {
        case page(SyncResponse)
        case failure(any Error & Sendable)
    }

    enum PutReply: Sendable {
        case reply(Int, SyncRowEcho?)
        case failure(any Error & Sendable)
    }

    enum Failure: Error, Equatable { case exhausted(String) }

    private struct State {
        var pulls: [PullReply]
        var puts: [PutReply]
        var deletes: [Int]
        var calls: [Call] = []
        var bodies: [Data] = []
        var pullIds: [[String: String]] = []
        var inFlight = 0
        var peak = 0
    }

    private let state: Mutex<State>
    /// Awaited INSIDE each method, after the call is recorded and before it is answered, with this
    /// call's 1-based index — so a test can park exactly one request while it is genuinely in flight.
    private let hook: (@Sendable (Call, Int) async -> Void)?

    init(pulls: [PullReply] = [], puts: [PutReply] = [], deletes: [Int] = [],
         hook: (@Sendable (Call, Int) async -> Void)? = nil) {
        state = Mutex(State(pulls: pulls, puts: puts, deletes: deletes))
        self.hook = hook
    }

    var calls: [Call] { state.withLock { $0.calls } }
    /// Every PUT body, in order — what proves a synthesised `channelUrl` actually reached the wire.
    var bodies: [Data] { state.withLock { $0.bodies } }
    /// Every pull's cursor ids, in order, with the nils dropped — exactly the set `SyncClient` puts
    /// on the wire (`SyncClient.swift:53` flattens a `String??` and skips it), which is what makes
    /// "the next run pulls from no tiebreaker at all" an assertion instead of an inference.
    var pullIds: [[String: String]] { state.withLock { $0.pullIds } }
    /// Peak simultaneous calls. 1 is the whole point of the exclusion.
    var peakConcurrency: Int { state.withLock { $0.peak } }

    func pull(cursors: [String: Int], ids: [String: String?]) async throws -> SyncResponse {
        let (reply, index) = enter(.pull, ids: ids.compactMapValues { $0 })
        await run(.pull, index)
        leave()
        switch reply {
        case .none: throw Failure.exhausted("pull")
        case .some(.failure(let error)): throw error
        case .some(.page(let page)): return page
        }
    }

    func put(_ type: SyncEntityType, id: String, body: Data) async throws -> (status: Int, dto: SyncRowEcho?) {
        let call = Call.put(type, id)
        let (reply, index): (PutReply?, Int) = state.withLock {
            $0.calls.append(call)
            $0.bodies.append(body)
            $0.inFlight += 1
            $0.peak = max($0.peak, $0.inFlight)
            return ($0.puts.isEmpty ? nil : $0.puts.removeFirst(), $0.calls.count)
        }
        await run(call, index)
        leave()
        switch reply {
        case .none: throw Failure.exhausted("put \(type.rawValue)/\(id)")
        case .some(.failure(let error)): throw error
        case .some(.reply(let status, let echo)): return (status, echo)
        }
    }

    func delete(_ type: SyncEntityType, id: String) async throws -> Int {
        let call = Call.delete(type, id)
        let (status, index): (Int?, Int) = state.withLock {
            $0.calls.append(call)
            $0.inFlight += 1
            $0.peak = max($0.peak, $0.inFlight)
            return ($0.deletes.isEmpty ? nil : $0.deletes.removeFirst(), $0.calls.count)
        }
        await run(call, index)
        leave()
        guard let status else { throw Failure.exhausted("delete \(type.rawValue)/\(id)") }
        return status
    }

    private func enter(_ call: Call, ids: [String: String]) -> (PullReply?, Int) {
        state.withLock {
            $0.calls.append(call)
            $0.pullIds.append(ids)
            $0.inFlight += 1
            $0.peak = max($0.peak, $0.inFlight)
            return ($0.pulls.isEmpty ? nil : $0.pulls.removeFirst(), $0.calls.count)
        }
    }

    /// ONE suspension point even with no hook, so simultaneous callers actually overlap and
    /// `peakConcurrency` can exceed 1 — a `peak == 1` assertion is worthless otherwise.
    private func run(_ call: Call, _ index: Int) async {
        await Task.yield()
        await hook?(call, index)
    }

    private func leave() { state.withLock { $0.inFlight -= 1 } }
}

extension SyncResponse {
    /// The exhausted page: three empty item lists and no cursor — one `pullAll` iteration, then stop.
    static var empty: SyncResponse { .page() }

    static func page(subscriptions: [SubscriptionSyncDTO] = [], playlists: [PlaylistSyncDTO] = [],
                     favorites: [FavoriteSyncDTO] = [],
                     subscriptionsCursor: Int? = nil, subscriptionsCursorId: String? = nil,
                     playlistsCursor: Int? = nil, playlistsCursorId: String? = nil,
                     favoritesCursor: Int? = nil, favoritesCursorId: String? = nil) -> SyncResponse {
        SyncResponse(
            subscriptions: SyncPage(items: subscriptions, nextCursor: subscriptionsCursor,
                                    nextCursorId: subscriptionsCursorId),
            playlists: SyncPage(items: playlists, nextCursor: playlistsCursor,
                                nextCursorId: playlistsCursorId),
            favorites: SyncPage(items: favorites, nextCursor: favoritesCursor,
                                nextCursorId: favoritesCursorId))
    }
}
