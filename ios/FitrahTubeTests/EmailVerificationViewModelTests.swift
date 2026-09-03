import FitrahAPI
import Foundation
import InnerTubeKit
import Synchronization
import Testing
@testable import FitrahTube

/// Task 11. Three things nothing else pins: the auto-send latch survives process death (so an
/// account is mailed exactly once per install), the 60 s cooldown is a pure function of an INJECTED
/// clock rather than the wall clock, and the send order is backend-first with Firebase reached only
/// when the backend answered unsuccessfully.
///
/// The "was Firebase asked?" assertions are made through `FakeAuthClient.nextError`: a poisoned
/// next-call error that is still queued afterwards is proof the client was never called, and one
/// that came back as the VM's error is proof it was. No clock, no network, no sleeps.
@Suite(.perTest)
@MainActor
struct EmailVerificationViewModelTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let meJSON = #"{"uid":"fake-uid","email":"student@fitrah.test","status":"active","role":"user"}"#

    private static let unverified = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                             isEmailVerified: false, providerIDs: ["password"])
    private static let verified = AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                           isEmailVerified: true, providerIDs: ["password"])
    private static let key = "email_verification_last_sent_at.fake-uid"
    private nonisolated static let t0 = Date(timeIntervalSince1970: 1_700_000_000)

    /// The injected clock. `Mutex` because `now` crosses into a `@Sendable` closure; `advance` is
    /// how a test moves through the cooldown without waiting out one second of real time.
    private nonisolated final class Clock: Sendable {
        private let value: Mutex<Date>
        init(_ start: Date) { value = Mutex(start) }
        var now: Date { value.withLock { $0 } }
        func advance(_ seconds: TimeInterval) { value.withLock { $0 += seconds } }
    }

    private struct Fixture {
        let model: EmailVerificationViewModel
        let transport: ScriptedTransport
        let session: AccountSession
        let defaults: UserDefaults
        let suiteName: String
        let clock: Clock
    }

    private func make(auth: FakeAuthClient, responses: [HTTPResponse] = [],
                      seedLastSentAt: Date? = nil) -> Fixture {
        let suiteName = "EmailVerificationViewModelTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        if let seedLastSentAt { defaults.set(seedLastSentAt, forKey: Self.key) }
        let clock = Clock(Self.t0)
        let transport = ScriptedTransport(responses)
        let account = AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1"))
        let session = AccountSession(auth: auth, account: account, stores: [],
                                     status: AccountStatusCenter(), sleep: { _ in })
        let model = EmailVerificationViewModel(auth: auth, session: session, account: account,
                                               defaults: defaults, now: { clock.now })
        return Fixture(model: model, transport: transport, session: session,
                       defaults: defaults, suiteName: suiteName, clock: clock)
    }

    /// Drives `AccountSession.start()` to a loaded account — the only way `session.user` is
    /// populated, which is what `checkNow()`'s hand-off writes over. Bounded `Task.yield()`, never
    /// a sleep.
    private func started(_ fixture: Fixture) async -> Task<Void, Never> {
        let session = fixture.session
        let running = Task { await session.start() }
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }
        return running
    }

    // MARK: - Auto-send, once per account per install

    @Test func theAutoSendCallsTheBackendOnceAndLatchesAcrossProcessDeath() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        let fixture = make(auth: auth, responses: [.json(200, "{}")])
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        await fixture.model.send()

        #expect(fixture.model.state.email == "student@fitrah.test")
        #expect(fixture.model.state.error == nil)
        #expect(fixture.model.state.isResending == false)
        #expect(fixture.transport.sent.map(\.method) == ["POST"])
        #expect(fixture.transport.sent.first?.url.path == "/api/account/send-verification-email")
        #expect(fixture.model.state.lastSentAt == Self.t0)
        #expect(fixture.defaults.object(forKey: Self.key) as? Date == Self.t0)

        // Process death: a SECOND view model over the same persisted domain. The latch is the
        // stored timestamp, not an in-memory flag, so this one must send nothing at all.
        let second = EmailVerificationViewModel(
            auth: auth, session: fixture.session,
            account: AccountClient(transport: fixture.transport, baseURL: Self.base,
                                   deviceId: DeviceId(value: "dev-1")),
            defaults: fixture.defaults, now: { Self.t0 })
        await second.send()

        #expect(fixture.transport.sent.count == 1, "the auto-send fired a second time")
        #expect(second.state.lastSentAt == Self.t0, "the persisted latch was not restored")
        #expect(second.state.email == "student@fitrah.test")
    }

    // MARK: - Cooldown (pure function of the injected clock)

    @Test func aResendInsideTheCooldownIsRefusedWithNoCall() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        let fixture = make(auth: auth, seedLastSentAt: Self.t0)
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }
        await fixture.model.send()

        fixture.clock.advance(59)
        await fixture.model.resend()

        #expect(fixture.model.state.error == .rateLimited)
        #expect(fixture.transport.sent.isEmpty, "a refused resend reached the network")
        #expect(fixture.model.canResend(at: fixture.clock.now) == false)
    }

    /// Android's gate is `now - last < COOLDOWN_MS` (`EmailVerificationViewModel.kt:142`), so the
    /// boundary itself SENDS — 60 s exactly is outside the cooldown, not inside it.
    @Test func aResendAtTheCooldownBoundarySends() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        let fixture = make(auth: auth, responses: [.json(200, "{}")], seedLastSentAt: Self.t0)
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }
        await fixture.model.send()

        fixture.clock.advance(EmailVerificationViewModel.cooldown)
        #expect(fixture.model.canResend(at: fixture.clock.now))
        await fixture.model.resend()

        #expect(fixture.transport.sent.count == 1)
        #expect(fixture.model.state.error == nil)
        #expect(fixture.model.state.lastSentAt == fixture.clock.now)
        #expect(fixture.defaults.object(forKey: Self.key) as? Date == fixture.clock.now)
    }

    // MARK: - Backend first, Firebase only on an unsuccessful backend response

    @Test func aSuccessfulBackendSendNeverAsksFirebase() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        // Poisoned: if the fallback ran, this is what the VM would report.
        auth.nextError = .unknown
        let fixture = make(auth: auth, responses: [.json(200, "{}")])
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        await fixture.model.send()

        #expect(fixture.model.state.error == nil)
        #expect(fixture.model.state.lastSentAt == Self.t0)
        #expect(auth.nextError == .unknown, "the Firebase fallback was asked after a 200")
    }

    @Test func anUnsuccessfulBackendResponseFallsBackToFirebase() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        // Only Firebase can produce `.rateLimited` from a 500, so reading it back is proof the
        // fallback ran — and in this order, after the backend.
        auth.nextError = .tooManyRequests
        let fixture = make(auth: auth, responses: [.json(500, "{}")])
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        await fixture.model.send()

        #expect(fixture.transport.sent.count == 1, "the backend was not tried first")
        #expect(auth.nextError == nil, "the Firebase fallback was never asked")
        #expect(fixture.model.state.error == .rateLimited)
        #expect(fixture.model.state.lastSentAt == nil, "a failed send latched a timestamp")
        #expect(fixture.defaults.object(forKey: Self.key) == nil)
    }

    /// The backend enforces the same 60 s per uid (`AccountController.java:112-116`). Routing
    /// around it through Firebase would defeat the server's own limit, so a 429 stops here.
    @Test func aBackend429IsRateLimitedWithoutAskingFirebase() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        auth.nextError = .unknown
        let fixture = make(auth: auth, responses: [.json(429, #"{"retryAfterSeconds":60}"#)])
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        await fixture.model.send()

        #expect(fixture.model.state.error == .rateLimited)
        #expect(auth.nextError == .unknown, "a 429 fell through to Firebase")
        #expect(fixture.model.state.lastSentAt == nil)
    }

    /// A transport failure is "the request did not happen" — asking Firebase over the same dead
    /// radio is a second doomed call, so the fallback is not reached (Android's IOException leg
    /// escapes ahead of it too).
    @Test func aTransportFailureIsANetworkErrorAndSkipsTheFallback() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        auth.nextError = .unknown
        let fixture = make(auth: auth, responses: [.failing(URLError(.notConnectedToInternet))])
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        await fixture.model.send()

        #expect(fixture.model.state.error == .network)
        #expect(auth.nextError == .unknown)
        #expect(fixture.model.state.lastSentAt == nil)
    }

    @Test func aCancelledSendWritesNoErrorState() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        let fixture = make(auth: auth, responses: [.failing(URLError(.notConnectedToInternet))])
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        let task = Task { await fixture.model.send() }
        task.cancel()
        await task.value

        #expect(fixture.model.state.error == nil, "a cancellation was swallowed into an error state")
        #expect(fixture.model.state.lastSentAt == nil)
    }

    // MARK: - "I've verified"

    @Test func checkNowOnAVerifiedReloadMovesTheSessionOffTheStaleIdentity() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        // Firebase's auth listener does NOT fire on a reload: `state` keeps the stale identity
        // while `reload()` answers the verified one. That gap is what this screen has to close.
        auth.reloadedUser = Self.verified
        let fixture = make(auth: auth, responses: [.json(200, Self.meJSON)], seedLastSentAt: Self.t0)
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }
        let running = await started(fixture)
        defer { running.cancel() }
        #expect(fixture.session.user?.isEmailVerified == false)

        let verified = await fixture.model.checkNow()

        #expect(verified)
        #expect(fixture.model.state.error == nil)
        #expect(fixture.model.state.isChecking == false)
        #expect(fixture.session.user?.isEmailVerified == true)
        // Task 8's matrix, re-run over the session `RootView` reads: the account is off this screen.
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true,
                                           isEmailVerified: fixture.session.user?.isEmailVerified ?? false,
                                           status: fixture.session.state.me?.status)
        #expect(outcome.destination == .main)
    }

    @Test func checkNowOnAnUnverifiedReloadSaysNotYetVerified() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        let fixture = make(auth: auth, seedLastSentAt: Self.t0)
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        let verified = await fixture.model.checkNow()

        #expect(verified == false)
        #expect(fixture.model.state.error == .notYetVerified)
        #expect(fixture.model.state.isChecking == false)
    }

    @Test func aFailedReloadSeparatesThrottlingFromEverythingElse() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        let fixture = make(auth: auth, seedLastSentAt: Self.t0)
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }

        auth.nextError = .tooManyRequests
        #expect(await fixture.model.checkNow() == false)
        #expect(fixture.model.state.error == .rateLimited)

        auth.nextError = .network
        #expect(await fixture.model.checkNow() == false)
        #expect(fixture.model.state.error == .network)
    }

    // MARK: - Back = sign out (spec §13)

    @Test func signOutDropsTheSessionAndTheAuthClient() async {
        let auth = FakeAuthClient(state: .signedIn(Self.unverified))
        let fixture = make(auth: auth, responses: [.json(200, Self.meJSON)], seedLastSentAt: Self.t0)
        defer { fixture.defaults.removePersistentDomain(forName: fixture.suiteName) }
        await fixture.session.refresh()
        #expect(fixture.session.state.me != nil)

        fixture.model.signOut()

        #expect(fixture.session.state == .signedOut)
        #expect(await auth.currentUser() == nil, "the auth client was left signed in")
    }
}
