import Foundation
import InnerTubeKit
import Observation
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

/// Task 5: the `OAuthSignInProvider` double. Canned credential, no SDK, no UI, no network.
///
/// `isAvailable == false` FAILS instead of returning a credential, so "an unavailable provider is
/// never asked" (ruling F11) is a property a caller's test can actually break: a screen that asks
/// one anyway gets an error, not a silent success. `presentCount` is how a caller's test proves it
/// was not asked at all.
@MainActor final class FakeOAuthProvider: OAuthSignInProvider {
    let isAvailable: Bool
    let credential: OAuthCredential
    let error: AuthErrorCode
    private(set) var presentCount = 0

    init(isAvailable: Bool = true,
         credential: OAuthCredential = OAuthCredential(providerID: "google.com",
                                                       idToken: "fake-id-token",
                                                       accessTokenOrNonce: "fake-access-token"),
         error: AuthErrorCode = .googleSignInFailed) {
        self.isAvailable = isAvailable
        self.credential = credential
        self.error = error
    }

    func presentSignIn() async throws(AuthErrorCode) -> OAuthCredential {
        presentCount += 1
        guard isAvailable else { throw error }
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
