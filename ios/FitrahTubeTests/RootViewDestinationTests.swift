import AVFoundation
import FitrahAPI
import Foundation
import InnerTubeKit
import Synchronization
import SwiftUI
import Testing
@testable import FitrahTube

/// `RootView.destination(for:)` is the launch switch, and this walks it the same way
/// `MainShellRoutingTests` walks the shell's: down the `_ConditionalContent` chain a `@ViewBuilder`
/// switch produces, to the leaf that was actually chosen. The 10-line walker is COPIED, not shared —
/// it is a test-local mirror of a switch in a different file, and coupling the two would make one
/// task's arm change the other's failure.
@Suite(.perTest)
struct RootViewDestinationTests {
    private func leafTypeName(for destination: SplashDestination) -> String {
        var mirror = Mirror(reflecting: RootView().destination(for: SplashOutcome(destination: destination)))
        while String(describing: mirror.subjectType).hasPrefix("_ConditionalContent"),
              let storage = mirror.children.first(where: { $0.label == "storage" }) {
            let payload = Mirror(reflecting: storage.value)
            guard let inner = payload.children.first else { break }
            mirror = Mirror(reflecting: inner.value)
        }
        return String(describing: mirror.subjectType)
    }

    @Test func everySplashDestinationRendersItsScreen() {
        #expect(leafTypeName(for: .onboarding) == "OnboardingView")
        #expect(leafTypeName(for: .main) == "MainShellView")
        #expect(leafTypeName(for: .profileBootstrap) == "ProfileBootstrapScreen")
        #expect(leafTypeName(for: .emailVerification) == "EmailVerificationScreen")
        // Owner ruling 2026-09-24: the forced sign-in root (in a stack only for its title bar —
        // nothing is pushed under it, so there is no back affordance) and the `/me` hold.
        #expect(leafTypeName(for: .signIn) == "NavigationStack<NavigationPath, SignInScreen>")
        // A plain spinner, not a replay of the ~2.75 s launch animation after every sign-in.
        #expect(leafTypeName(for: .awaitingAccount) != "SplashView")
        #expect(leafTypeName(for: .awaitingAccount).contains("ProgressView<"))
    }

    /// The terminal alert is blocked/deleted and nothing else: `.signedOut` is the user's own
    /// sign-out, and popping a non-dismissible dialog on every sign-out would trap the guest.
    @Test func theTerminalAlertCoversBlockedAndDeletedOnly() {
        #expect(AccountStatusAlert(.blocked)
                == AccountStatusAlert(titleKey: "account_blocked_title", bodyKey: "account_blocked_body"))
        #expect(AccountStatusAlert(.deleted)
                == AccountStatusAlert(titleKey: "account_deleted_title", bodyKey: "account_deleted_body"))
        #expect(AccountStatusAlert(.signedOut) == nil)
    }

    /// Fix round 1 / I3. `signOut`/`alert` are ADVISORY on the outcome (Task 8) — `RootView` is the
    /// caller that must act on both, and the dispatch said "its tests must pin both". Only the alert
    /// MAPPING was pinned (above, as data); the leg that actually drops a blocked account's session at
    /// launch was untested. `RootView.act` is the seam that makes it reachable: static and taking the
    /// session, because `@Environment` is only populated while a view is being rendered and these
    /// tests construct `RootView()` directly.
    @Test @MainActor func theOutcomeAdvisoriesDropTheSessionAndRaiseTheAlert() async {
        let container = AppContainer.fake(auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser)))
        let session = container.session
        await session.refresh()
        #expect(session.state.me != nil, "the fixture container answers one canned /me")

        var alert: AccountStatusAlert?
        RootView.act(on: SplashOutcome(destination: .main), session: session, alert: &alert)
        #expect(session.state.me != nil, "a plain .main outcome touches neither leg")
        #expect(alert == nil)

        RootView.act(on: SplashOutcome(destination: .main, alert: .blocked),
                     session: session, alert: &alert)
        #expect(session.state == .signedOut)
        #expect(await container.auth.currentUser() == nil, "the auth client was signed out, not just the state")
        #expect(alert == AccountStatusAlert(.blocked))
    }

    /// Stage 5 / M5: the `.deleted` advisory is the SAME verdict the 403 envelope carries, and that
    /// path wipes the device (ruling C13). Routing it through `signOut()` made it a fourth residue
    /// for one server state — session dropped, every local row of a deleted account still on the
    /// device. It goes through `handle(_:)`, which is what reaches `handleDeletion()`.
    @Test @MainActor func theDeletedAdvisoryWipesRatherThanMerelySigningOut() async {
        let wipes = Mutex(0)
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let transport = ScriptedTransport([.json(200, #"{"uid":"fake-uid","status":"active","role":"user"}"#)])
        let base = URL(string: "https://api.fitrah.test/")!
        let session = AccountSession(auth: auth,
                                     account: AccountClient(transport: transport, baseURL: base,
                                                            deviceId: DeviceId(value: "dev-1")),
                                     stores: [], status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: { _ in wipes.withLock { $0 += 1 }; return nil })
        await session.refresh()
        #expect(session.state.me != nil)

        var alert: AccountStatusAlert?
        RootView.act(on: SplashOutcome(destination: .main, alert: .deleted),
                     session: session, alert: &alert)
        for _ in 0..<500 where wipes.withLock({ $0 }) == 0 { await Task.yield() }

        #expect(wipes.withLock { $0 } == 1, "a server-deleted account was signed out but not wiped")
        #expect(alert == AccountStatusAlert(.deleted))
    }

    // MARK: - Sign-in wall: what `RootView` feeds the router

    /// A signed-in session whose ONE `/me` answer is scripted, driven by `start()`.
    @MainActor private func session(_ responses: [HTTPResponse],
                                    park: (@Sendable (Int) async -> Void)? = nil) async -> (AccountSession, Task<Void, Never>) {
        let session = AccountSession(auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser)),
                                     account: AccountClient(transport: ScriptedTransport(responses, park: park),
                                                            baseURL: URL(string: "https://api.fitrah.test/")!,
                                                            deviceId: DeviceId(value: "dev-1")),
                                     stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil })
        let running = Task { await session.start() }
        for _ in 0..<500 where session.user == nil { await Task.yield() }
        return (session, running)
    }

    private static let active = #"{"uid":"fake-uid","status":"active","role":"user"}"#

    /// `/me` in flight holds the splash; `/me` failed before anything was loaded is the wall. A
    /// mapping that always said "awaiting" would park a failed launch on the splash forever.
    @Test @MainActor func aStatusInFlightHoldsTheSplashAndAFailedOneIsTheWall() async {
        let gate = Gate()
        let (session, running) = await session([.json(500, "{}")], park: { _ in await gate.block() })
        defer { running.cancel() }
        await gate.waitUntilBlocked()
        #expect(RootView.outcome(onboardingCompleted: true, session: session).destination
                == .awaitingAccount)

        await gate.release()
        for _ in 0..<500 where session.state == .loading { await Task.yield() }
        #expect(RootView.outcome(onboardingCompleted: true, session: session).destination == .signIn)
    }

    /// P1-a: Android routes once, at cold start. A later refresh that fails (5xx) for the account
    /// already in the shell must not eject it to the wall — only a sign-out may.
    @Test @MainActor func aFailedRefreshForALoadedAccountKeepsItInTheShell() async {
        let (session, running) = await session([.json(200, Self.active), .json(500, "{}")])
        defer { running.cancel() }
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }

        await session.refreshIfSignedIn(maxAttempts: 1)
        #expect(RootView.outcome(onboardingCompleted: true, session: session).destination == .main)

        session.signOut()
        #expect(RootView.outcome(onboardingCompleted: true, session: session).destination == .signIn)
    }

    /// Patch round 2: a verdict whose sign-out Firebase REFUSES leaves `user` set and writes
    /// `.failed` (`dropSession`'s catch). A blocked account must land on the wall, not back in the
    /// shell on a remembered status.
    @Test @MainActor func aBlockedVerdictWhoseSignOutIsRefusedLandsOnTheWall() async {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let session = AccountSession(auth: auth,
                                     account: AccountClient(transport: ScriptedTransport([.json(200, Self.active)]),
                                                            baseURL: URL(string: "https://api.fitrah.test/")!,
                                                            deviceId: DeviceId(value: "dev-1")),
                                     stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil })
        let running = Task { await session.start() }
        defer { running.cancel() }
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }

        auth.nextError = .unknown
        var alert: AccountStatusAlert?
        RootView.route(AccountStatusSignal(event: .blocked, uid: FakeAuthClient.defaultUser.uid),
                       session: session, alert: &alert)
        #expect(session.user != nil, "the fixture's sign-out was supposed to be refused")

        #expect(RootView.outcome(onboardingCompleted: true, session: session).destination == .signIn)
    }

    /// Patch round 3: the wall is not only `user == nil` — a refused sign-out and a failed `/me`
    /// both show it with a user still set. The cleanup is keyed on the DESTINATION: nothing for the
    /// shell, everything for the wall.
    @Test @MainActor func theCleanupRunsWhenTheWallIsShownAndOnlyThen() {
        let router = Router()
        router.paths[.home] = [.search]
        RootView.didRoute(to: .main, router: router, container: .fake(),
                          players: NSHashTable<AVPlayer>.weakObjects(), clearNowPlaying: {})
        #expect(router.paths[.home] == [.search], "the shell's own stack was torn down")

        var cleared = false
        RootView.didRoute(to: .signIn, router: router, container: .fake(),
                          players: NSHashTable<AVPlayer>.weakObjects(), clearNowPlaying: { cleared = true })
        #expect(router.paths[.home]?.isEmpty == true)
        #expect(cleared, "the lock screen kept the last video")
    }

    /// Patch round 3 (Cubic): "awaiting" is POSITIVELY in flight. A cancelled launch round used to
    /// restore the `.signedOut` it found while `user` was already set — nothing in flight, no
    /// record, not failed — and the splash held forever.
    @Test @MainActor func aCancelledLaunchRoundDoesNotHoldTheSplashForever() async {
        let gate = Gate()
        let (session, running) = await session([.json(200, Self.active)], park: { _ in await gate.block() })
        await gate.waitUntilBlocked()
        running.cancel()
        await gate.release()
        for _ in 0..<500 where session.state == .loading { await Task.yield() }

        #expect(session.user != nil)
        #expect(RootView.outcome(onboardingCompleted: true, session: session).destination != .awaitingAccount,
                "stuck on the splash at \(session.state)")
    }

    /// The `.onChange` glue itself, not a re-statement of it: tabs popped, the player
    /// stopped, the cast asked to end (a no-op without a cast context).
    @Test @MainActor func theSignOutGluePopsEveryTabAndStopsPlayback() throws {
        let router = Router()
        router.paths[.home] = [.search]
        router.paths[.channels] = [.search]
        let url = URL(string: "https://127.0.0.1:9/glue.m3u8")!
        let player = try #require(PlayerHostView.player(
            for: .ready(Resolved(stream: .hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []),
                                 client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)),
            replacing: nil))
        let table = NSHashTable<AVPlayer>.weakObjects()
        table.add(player)

        RootView.didRoute(to: .signIn, router: router, container: .fake(), players: table, clearNowPlaying: {})

        #expect(Tab.allCases.allSatisfy { router.paths[$0]?.isEmpty ?? true })
        #expect(player.rate == 0)
        #expect(player.currentItem == nil)
    }

    // MARK: - The mid-session signal (CF-A-48)

    /// A session `start()` has driven to a loaded account — `user` is what `handle` attributes
    /// against, and only the auth stream sets it. The wipe is the caller's, because a `Mutex` is
    /// noncopyable and cannot ride out in the tuple.
    @MainActor private func signedInSession(
        wipe: @escaping @MainActor @Sendable (() -> Bool) async -> Error?
    ) async -> (session: AccountSession, running: Task<Void, Never>) {
        let transport = ScriptedTransport([.json(200, #"{"uid":"fake-uid","status":"active","role":"user"}"#)])
        let session = AccountSession(auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser)),
                                     account: AccountClient(transport: transport,
                                                            baseURL: URL(string: "https://api.fitrah.test/")!,
                                                            deviceId: DeviceId(value: "dev-1")),
                                     stores: [], status: AccountStatusCenter(), sleep: { _ in },
                                     wipe: wipe)
        let running = Task { await session.start() }
        // A DEADLINE, not a yield count: 500 yields on an idle main actor is only ~20-100 ms, and
        // that raced the cooperative pool's scheduling tail under the two-simulator gate
        // (`DeleteAccountTests.yieldUntil(timeout:)` has the measurements). Condition first, the
        // clock only as the ceiling.
        let deadline = ContinuousClock.now + .seconds(10)
        while session.state.me == nil, ContinuousClock.now < deadline { await Task.yield() }
        return (session, running)
    }

    /// Task 33 / CF-A-44's delivery end. What this pins is `RootView.route` — NOT the `.onChange`
    /// closure that calls it: a SwiftUI closure cannot be constructed here, so reverting that
    /// closure to `session.handle(signal.event)` would still pass. The closure is one line that
    /// forwards to `route`, which is as far as a unit test reaches. A `.deleted` minted
    /// for another account must reach `handle` WITH its uid: `handle(signal.event)` alone is the
    /// unattributed arm, which is honoured unconditionally — so dropping the uid here wipes the
    /// signed-in account's library for a stranger's deletion and tells them their account is gone.
    @Test @MainActor func aSignalForAnotherAccountNeitherWipesNorRaisesTheAlert() async {
        let wipes = Mutex(0)
        let (session, running) = await signedInSession { _ in wipes.withLock { $0 += 1 }; return nil }
        defer { running.cancel() }
        #expect(session.user?.uid == FakeAuthClient.defaultUser.uid)

        var alert: AccountStatusAlert?
        RootView.route(AccountStatusSignal(event: .deleted, uid: "uid-stranger"), session: session, alert: &alert)
        for _ in 0..<500 where wipes.withLock({ $0 }) == 0 { await Task.yield() }

        #expect(wipes.withLock { $0 } == 0, "a stranger's deletion wiped the signed-in account's library")
        #expect(alert == nil, "the refused verdict still told the signed-in user their account was deleted")
        #expect(session.state.me != nil, "the stranger's deletion dropped the wrong session")
    }

    /// The positive control: a route that refused everything would pass the test above and
    /// silently disable the wipe. The signed-in account's own signal acts, and raises the alert.
    @Test @MainActor func aSignalForTheSignedInAccountWipesAndRaisesTheAlert() async {
        let wipes = Mutex(0)
        let (session, running) = await signedInSession { _ in wipes.withLock { $0 += 1 }; return nil }
        defer { running.cancel() }

        var alert: AccountStatusAlert?
        RootView.route(AccountStatusSignal(event: .deleted, uid: FakeAuthClient.defaultUser.uid),
                       session: session, alert: &alert)
        // POSITIVE wait on a DETACHED wipe, so a deadline (see `signedInSession`). The stranger
        // test above keeps its yield COUNT on purpose: it proves an absence, and spinning that to
        // a multi-second deadline would slow it for nothing.
        let deadline = ContinuousClock.now + .seconds(10)
        while wipes.withLock({ $0 }) == 0, ContinuousClock.now < deadline { await Task.yield() }

        #expect(wipes.withLock { $0 } == 1, "the account's own deletion was refused")
        #expect(alert == AccountStatusAlert(.deleted))
    }
}
