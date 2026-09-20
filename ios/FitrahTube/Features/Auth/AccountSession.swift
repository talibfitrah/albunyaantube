import Foundation
import Observation

/// Where "a device wipe is still owed for this uid" survives a process death (Stage 5 / C1.2).
/// The wipe is the only thing that can run it: the server has already revoked and deleted the
/// Firebase user, so no later `/me` can re-trigger `handleDeletion()` — without a durable marker
/// the cleanup is not deferred, it is unreachable forever.
@MainActor protocol DeletionMarking: AnyObject, Sendable {
    var pendingUid: String? { get set }

    /// The last account that signed in on this device, kept DURABLY and never cleared by a
    /// sign-out (Task 34 / CF-A-44). `pendingUid` alone cannot be redeemed safely, because the
    /// question "may this wipe still run?" has two answers that look identical at launch: with
    /// nobody signed in, the deleted account being gone (redeem) and somebody ELSE's library
    /// sitting on the device (never device-wide; by uid only) both present as `currentUser == nil`. The in-memory
    /// `lastKnownUid` cannot answer it either — it is nil at launch, which is exactly when the
    /// redemption runs. With `pendingUid` above, this is all that outlives the process.
    var lastSignedInUid: String? { get set }
}

/// The production marker: two `UserDefaults` keys. `pendingUid` is written before the detached
/// cleanup starts and cleared only once the wipe reported no error. `lastSignedInUid` is written
/// by `start()`'s `.signedIn` arm and by `fetch`'s `land()`-window seed, and cleared — only while
/// it still names that account — by a redeemed deletion and by the age-ineligible teardown.
@MainActor final class UserDefaultsDeletionMarker: DeletionMarking {
    nonisolated static let defaultsKey = "com.albunyaan.tube.deletionPending"
    nonisolated static let lastSignedInKey = "com.albunyaan.tube.lastSignedInUid"
    private let defaults: UserDefaults

    init(defaults: UserDefaults) { self.defaults = defaults }

    var pendingUid: String? {
        get { defaults.string(forKey: Self.defaultsKey) }
        set {
            if let newValue { defaults.set(newValue, forKey: Self.defaultsKey) }
            else { defaults.removeObject(forKey: Self.defaultsKey) }
        }
    }

    var lastSignedInUid: String? {
        get { defaults.string(forKey: Self.lastSignedInKey) }
        set {
            if let newValue { defaults.set(newValue, forKey: Self.lastSignedInKey) }
            else { defaults.removeObject(forKey: Self.lastSignedInKey) }
        }
    }
}

/// The default, so the seven suites that never delete need no store at all — and so no test can
/// write a pending-deletion flag into `UserDefaults.standard`.
@MainActor final class InMemoryDeletionMarker: DeletionMarking {
    var pendingUid: String?
    var lastSignedInUid: String?
    init() {}
}

nonisolated enum AccountState: Sendable, Equatable {
    case signedOut, loading
    /// A lightweight (code, message) pair, never a raw response object — Android's comment
    /// (`AccountState.kt:16-27`) records a retained `ResponseBody` pinning a connection-pool slot
    /// for the life of a hot flow. iOS has no such pool, but code+message is what the UI needs.
    case failed(code: Int?, message: String)
    case loaded(AccountMe)
    /// Callers write `session.state.me?.isModerator` — there is no `.loaded?` shorthand in Swift.
    var me: AccountMe? { if case .loaded(let me) = self { me } else { nil } }
}

/// The ONE holder of account state, and the only thing that re-scopes the local stores. Everything
/// per-user in this app (favorites, saved playlists, subscriptions) keys off `currentUserId`, and
/// before this task nothing set it — every store sat on the `""` anon sentinel for the life of the
/// process, so signing in showed the guest library and signing out kept showing the account's.
@MainActor @Observable final class AccountSession {
    private let auth: any AuthClient
    private let account: AccountClient
    private let stores: [any UserScoped]
    private let status: AccountStatusCenter
    private let sleep: @Sendable (Duration) async -> Void
    /// Returns the FIRST error the wipe hit, or nil when everything went (Stage 5 / C2.2): the four
    /// SwiftData deletes used to be `try?`-swallowed, so a full or corrupt store left every row on
    /// disk while the app announced the account erased.
    private let wipe: @MainActor @Sendable () async -> Error?
    /// The uid-scoped delete (`LocalAccountWiper.wipeRows(of:)`). Its DEFAULT REFUSES — it reports
    /// an error, so the marker is kept — because a default that answered nil would "redeem" an
    /// irreversible debt having deleted nothing, for any construction that forgot to pass one.
    /// `CancellationError` for the reason `AppContainer`'s released-container arm uses it: the
    /// work did not run.
    private let wipeRows: @MainActor @Sendable (String) -> Error?
    private let marker: any DeletionMarking
    /// The federated providers, asked to forget their OWN SDK sessions on every session drop
    /// (Stage 4 / I1). Empty is the honest default for a suite with no federated sign-in.
    private let providers: [any OAuthSignInProvider]
    /// Phase 4 Task 24: the ONE consumer of "which account is current". nil in the suites with no
    /// sync to drive -- the honest default, like `providers` above.
    private let sync: (any SyncTriggering)?
    /// The uid the manager is currently bound to. `refreshIfSignedIn` runs on every foreground, so
    /// binding on each `/me` would be a merge + pull + push per foreground and the foreground
    /// trigger's own >=15 min spacing would mean nothing. A bind belongs to the IDENTITY, not to
    /// the request -- Android binds once per launch, from the splash (`SplashFragment.kt:129-141`).
    private var boundUid: String?

    private(set) var state: AccountState = .signedOut
    /// The signed-in Firebase identity, which is NOT `state.me`: `SplashRouter.outcome` needs
    /// `hasPasswordProvider`/`isEmailVerified` (spec §13), and neither is on the backend's account
    /// record. Set from the auth stream, so it is populated before `/me` has answered.
    private(set) var user: AuthUser?
    /// R7-P1 #3: the terminal under-13 verdict is up, and the session behind it is already gone.
    ///
    /// The server revokes the refresh tokens AND disables the Firebase account before it answers
    /// 422 `AGE_INELIGIBLE` (`AccountProfileService.java:130,140`), so the session is dead the
    /// moment the verdict lands: every later request 401s, `AuthorizedTransport` maps the refused
    /// forced mint's `.userDisabled` to `.blocked`, and the user was shown "your account has been
    /// blocked" — the wrong reason for a terminal state, on the one path where the reason is the
    /// whole point — with the Firebase delete never run. So the drop happens WITH the verdict, and
    /// this flag is what keeps the message on screen across it: `RootView` presents the screen over
    /// whatever the outcome resolves to (the guest shell, by then), rather than routing to a
    /// destination the very same drop tears down. Cleared by the screen's own OK.
    private(set) var isAgeIneligible = false

    init(auth: any AuthClient, account: AccountClient, stores: [any UserScoped],
         status: AccountStatusCenter, sleep: @escaping @Sendable (Duration) async -> Void,
         wipe: @escaping @MainActor @Sendable () async -> Error?,
         wipeRows: @escaping @MainActor @Sendable (String) -> Error? = { _ in CancellationError() },
         marker: any DeletionMarking = InMemoryDeletionMarker(),
         providers: [any OAuthSignInProvider] = [],
         sync: (any SyncTriggering)? = nil) {
        self.auth = auth
        self.account = account
        self.stores = stores
        self.status = status
        self.sleep = sleep
        self.wipe = wipe
        self.wipeRows = wipeRows
        self.marker = marker
        self.providers = providers
        self.sync = sync
    }

    /// The uid ANY sync trigger may run for, or nil -- the one guard the foreground and
    /// connectivity sites both consult (Task 24).
    ///
    /// Three nil arms, each for its own reason. A GUEST has no account to sync with. A `/me` still
    /// in flight has no account uid yet, and the trigger is skipped outright rather than waited on:
    /// Android suspended on the state flow instead and accumulated one waiter per foreground
    /// (`AlBunyaanApplication.kt:167-185`), and the wait buys nothing here because the sign-in path
    /// binds the moment `/me` lands. A TERMINAL verdict being handled is the sharp one: `state` is
    /// still `.loaded` across the deletion wipe -- which is detached and survives a foreground --
    /// and across the age-ineligible teardown's await of Firebase, so a pull started in either
    /// window would restore the very rows being erased.
    var syncableUid: String? {
        guard deletion == nil, !isAgeIneligible, case .loaded(let me) = state, !me.uid.isEmpty else { return nil }
        return me.uid
    }

    /// Observes `AuthClient.state`; on each change sets every store's `currentUserId` FIRST, then
    /// refreshes. The order is the whole point: a `/me` answer that landed before the stores were
    /// re-scoped would be rendered against the previous account's local rows.
    ///
    /// Runs until the stream finishes (`UnavailableAuthClient` — no plist — yields once and ends)
    /// or the calling task is cancelled.
    func start() async {
        await resumePendingDeletion()
        for await authState in auth.state {
            switch authState {
            case .signedOut:
                // Cubic round 6 / P2: the SAME drop the explicit path performs. Firebase
                // force-signs a user out INSIDE a refused forced mint
                // (`FirebaseAuthClient`'s trace of `signOutIfTokenIsInvalid`), so `.signedOut`
                // arrives HERE with the previous identity's round still in the slot — and
                // `.signedIn(B)` below then joined it instead of asking for B's own record. B
                // never fetched, A's answer was dropped as stale, and `MeTabRoot.arm` rendered
                // `.unreachable` — the "Something went wrong" Retry card — for a signed-in B.
                //
                // Guarded on `user`, because this arm also delivers the stream's FIRST element,
                // which is the CURRENT state: on a launch with no session that is `.signedOut`,
                // and it is not a sign-out. Unguarded it cancelled the `land()` round the sign-in
                // screen starts ahead of the listener (round 3 / NB1) — the app's primary
                // sign-in path, killed by its own session observer.
                // Part B gate, stage 4 S2: the SAME teardown `dropSession()` runs — provider
                // sign-out and the sync unbind included. This arm used to cancel the round only, so
                // a Firebase-initiated sign-out left the Google SDK session alive for the next
                // account and an armed push retry free to drain A's rows under B's bearer.
                if user != nil { tearDown() }
                user = nil
                scope(to: "")
                state = .signedOut
            case .signedIn(let signedIn):
                user = signedIn
                lastKnownUid = signedIn.uid
                // Durable, and never cleared by the sign-out below (Task 34 / CF-A-44): this is
                // what `resumePendingDeletion()` reads on the NEXT launch, when `lastKnownUid` is
                // nil because the process is new.
                marker.lastSignedInUid = signedIn.uid
                // A new account on this device gets its own deletion latch: without this, a second
                // account deleted in the same process would find the first one's task and wipe
                // nothing (`handleDeletion`).
                deletion = nil
                scope(to: signedIn.uid)
                await refresh()
            }
        }
    }

    /// Stage 5 / C1.2 + C2.2: the wipe a previous launch owed this device. Idempotent — every step
    /// of `LocalAccountWiper.wipe()` is a delete — so re-running it costs nothing when it already
    /// ran, and it is the ONLY thing that can recover a cleanup interrupted by process death or
    /// refused by a full store. The marker survives a wipe that reported an error, so the next
    /// launch tries again.
    ///
    /// Runs BEFORE the auth stream so a marker left by a deletion never has a signed-in account
    /// racing it back onto the screen.
    func resumePendingDeletion() async {
        guard let pending = marker.pendingUid else { return }
        let signedIn = await auth.currentUser()?.uid
        // THE INVARIANT: the DEVICE wipe runs only on POSITIVE evidence that the pending account is
        // the one holding this device — it is signed in right now, or nobody is and the durable
        // record of the last holder names it. Everything else pays the debt BY UID, and "everything
        // else" includes having no evidence at all.
        //
        // It used to be the other way round — the device wipe was the fall-through, and each fix
        // added a condition in front of it — so every path that left the holder empty inherited
        // permission to destroy everything, three rounds running:
        //   * Stage 7 fix 2 / M2: somebody else signed in right now, and B's library went for A's
        //     deletion;
        //   * Task 34 / CF-A-44: nobody signed in — the ordinary shape of a deleted account, and
        //     equally of a device whose last user simply SIGNED OUT — and a marker owed to A erased
        //     what B had accumulated since. Task 33 made that more reachable by writing a marker on
        //     the bare-401 path. (UNVERIFIED, no probe: a locked device holding a stored session
        //     may present the same way — but before first unlock `UserDefaults` is likely
        //     unreadable too, so `pendingUid` reads nil above and this is never reached.)
        //   * Round 3 / item 1: nobody signed in and NO holder on record, because
        //     `terminateAgeIneligible()` had forgotten an under-13 account in between.
        if (signedIn ?? marker.lastSignedInUid) == pending {
            if await wipe() == nil { redeemed(pending) }
            return
        }
        // Review I3: the debt is still PAYABLE here. Every per-user row carries its owner, so A's
        // are deleted by uid and everybody else's, the guest's and every device-wide step are left
        // alone. `SyncManager.switchAccount(_:from:to:)` deletes the previous uid's rows too, on
        // the next account's first bind — so merely DROPPING the marker strands A's rows only when
        // that bind never runs or rolls back (B offline, B with no syncable uid). This is the
        // DURABLE BACKSTOP for that case. A delete that reported an error keeps the marker, as the
        // device wipe above does.
        if wipeRows(pending) == nil { redeemed(pending) }
    }

    /// The debt is paid: the marker goes, and so does the deleted account's uid (review I2 — the
    /// wiper's own standard is that no record of that uid outlives the wipe, and this key sits
    /// outside its prefix sweep). ONLY while it still names that account: whoever signed in while
    /// the wipe ran is the device's holder now, and that record is the positive evidence THEIR own
    /// interrupted deletion would need (`resumePendingDeletion`'s invariant) — forgetting them
    /// would quietly downgrade it to a row-only delete.
    ///
    /// `uid` is the account the wipe RAN for, captured when the debt was recorded — never
    /// `marker.pendingUid` read at completion: a second account deleted while the first wipe was
    /// still running owns the marker by then, and the first completion used to clear ITS debt.
    private func redeemed(_ uid: String) {
        if marker.lastSignedInUid == uid { marker.lastSignedInUid = nil }
        if marker.pendingUid == uid { marker.pendingUid = nil }
    }

    /// Fix round 1 / I2: the refresh currently running, handed to a second caller instead of a
    /// second request. `SignInViewModel.land()` refreshes on the same auth transition `start()` is
    /// about to refresh on; with no guard both wrote `state` and the last writer won, so a
    /// `.loaded` account could be overwritten by the loser's `.failed` and `RootView` would then
    /// read `status == nil` and route a pending-profile account to the shell.
    private var inFlight: Task<Void, Never>?

    /// `MAX_ATTEMPTS = 3`, linear backoff `1 s * attempt`; IOException retries, 4xx/5xx NEVER
    /// (`AccountRepositoryImpl.kt:111-147`). The splash calls it with `maxAttempts: 1`.
    ///
    /// Coalesced: the FIRST caller's `maxAttempts` is the budget that runs, and a second caller
    /// awaits that work rather than racing it. Ordering with `start()` is unchanged — `start()`
    /// calls `scope(to:)` and then `refresh()` with no suspension between them, so a `/me` answer
    /// can still never land before the stores are re-scoped
    /// (`aUidChangeScopesEveryStoreBeforeTheFirstRequest`).
    ///
    /// **A `.task`-scoped caller must not be the LEADER.** Only the first caller's cancellation
    /// reaches the shared work, and every follower — `start()` included — observes whatever state
    /// that cancellation left. Stage 3 / M6: the cancellation return now restores the pre-refresh
    /// state rather than parking the whole app at `.loading`, but a screen whose `.task` leads is
    /// still deciding for every other observer, so use an unstructured `Task {}` at those sites.
    func refresh(maxAttempts: Int = 3) async {
        // A follower must NOT cancel the shared work — only the caller that started it does, which
        // is what keeps `start()`'s cancellation (Task 9 / M4) reaching the retry loop.
        if let inFlight {
            await inFlight.value
            return
        }
        // Declared ahead of the `Task` so the body can name the task that owns the slot. The
        // immutable copy below is what the cancellation handler takes: a `@Sendable` closure cannot
        // capture a mutable local under Swift 6.
        var round: Task<Void, Never>! = nil
        round = Task {
            await self.fetch(maxAttempts: maxAttempts)
            // Stage 9 round 2 / P1: released from INSIDE the round. The clear used to sit after the
            // await below, so the slot outlived the work it names by a main-actor turn (several,
            // under load) — and a caller arriving in that window JOINED a round that was already
            // over and returned having fetched nothing. For a new identity that is the same defect
            // `dropSession()`'s cancel closes, by another route: B awaits A's finished task and
            // never asks for its own record.
            //
            // Stage 9 round 3 / NB2: CONDITIONAL, and the round-2 claim that a late release is
            // harmless was wrong. `dropSession()` cancels and clears, cancellation takes several
            // async hops to surface, and `start()`'s `.signedIn(B)` arm installs B's task inside
            // that window — so an unconditional clear wiped the slot B is still using, and the
            // next caller started a SECOND concurrent round for B instead of joining. Two rounds
            // for one identity both pass the guards below, so the loser's `.failed` could land
            // over the winner's `.loaded` — fix round 1 / I2's defect by another route. Only the
            // task that owns the slot may clear it (`MeFeedRepository.refresh` does the same).
            guard self.inFlight == round else { return }
            self.inFlight = nil
        }
        let task = round!
        inFlight = task
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
    }

    /// The foreground hook's guard (`FitrahTubeApp`'s `scenePhase` arm) and nothing else.
    ///
    /// Stage 9 / P2b: a guest has no `/me` to refresh. `AccountClient.me()` has no token guard, so
    /// `BearerRetry` sent it UNSIGNED, took the 401, found `token(true)` nil and re-sent it — two
    /// `GET /api/account/me` on every return to the foreground for a signed-out user — and with
    /// `maxAttempts: 1` the 401 arm cannot `continue`, so the session ran
    /// `.signedOut -> .loading -> .failed`: an error banner over somebody who is simply not
    /// signed in.
    ///
    /// The guard is HERE and not inside `refresh(maxAttempts:)` because `refresh` is also the
    /// path taken while `user` is still nil ON PURPOSE: `SignInViewModel.land()` refreshes on the
    /// auth transition `start()` has not necessarily observed yet — its whole reason for existing —
    /// and `AccountSessionTests`' retry-budget suite drives `refresh()` on a `.signedOut` fixture
    /// precisely to assert the `.failed`/`.signedOut` end states this would silence.
    func refreshIfSignedIn(maxAttempts: Int = 3) async {
        guard user != nil else { return }
        await refresh(maxAttempts: maxAttempts)
    }

    private func fetch(maxAttempts: Int) async {
        // Stage 3 / M6: what the cancellation return below restores. A cancelled LEADER used to
        // leave `.loading` standing forever — and every follower, `start()` included, returned
        // having observed it, so `RootView` rendered a signed-in user as a guest with nothing left
        // to re-drive `/me`.
        let previousState = state
        // Stage 9 round 2 / P1: the identity this round was STARTED for. Every `state` write below
        // is guarded on it, because a `/me` answer outlives the account it was asked for: a
        // sign-out mid-refresh was overwritten by the late `.loaded(A)` (and `MeTabRoot.arm` then
        // rendered the signed-in Me screen for a guest), and a sign-out-then-sign-in-as-B inside
        // the same window left `user == B` with A's record on screen — Settings said "Signed in as
        // A" and a Profile save prefilled from A's record `PUT` A's name under B's bearer. A late
        // answer for an identity that is no longer current is DROPPED, never published.
        let startedFor = user?.uid
        // Stage 9 round 3 / NB1: a round that started with NO identity adopts whoever arrives.
        // `SignInViewModel.land()` refreshes on the auth transition `start()` has not necessarily
        // observed yet — the whole reason it exists (`refreshIfSignedIn`'s doc above) — so
        // `startedFor` is nil there and the strict `user?.uid == startedFor` dropped the answer to
        // the app's primary sign-in path: `state` stayed at the `.loading` written below with
        // nothing left to re-drive it (`MeTabRoot`'s `.loading` arm is a spinner with no Retry),
        // and `RootView` read `status == nil` and routed a PENDING_PROFILE account to the shell.
        // The request was signed with whatever bearer Firebase held, which is the identity that is
        // arriving — nil is "nobody yet", never "must still be nobody".
        func matchesIdentity() -> Bool { startedFor == nil || user?.uid == startedFor }
        // Stage 9 round 4 / NB-B: a CANCELLED round never publishes. Cancellation is advisory and
        // nothing on the `/me` path polls it (`AccountClient` → `BearerRetry` → the transport all
        // run to completion), so `dropSession()`'s `cancel()` does NOT stop a request already on
        // the wire from answering 200. For a nil-started round — `land()`'s shape, which the line
        // above deliberately admits — the identity test alone then published account A's answer
        // under whoever signed in next: `MeTabRoot.arm` reported `.signedIn`, Settings said "Signed
        // in as A", and a Profile save would `PUT` A's name under B's bearer.
        func publishable() -> Bool { !Task.isCancelled && matchesIdentity() }
        // …and it leaves `state` exactly as it found it, which is the other half. Stage 3 / M6's
        // restore used to sit INSIDE the retry arm, after the sleep, so it covered only a round
        // cancelled during the backoff: one cancelled before its first error, or between its
        // answer and the write, returned at a guard above with this round's own `.loading`
        // standing — a spinner with no Retry, contagious to every follower. One `defer` rather
        // than a fourth copy of the check at each return. Identity-guarded for the same reason the
        // publishes are (round 2 / P1): putting a dropped account's state back is a stale write
        // too. `state != previousState` so the no-op case does not fire an observation.
        // Stage 9 round 5 / NB-C: `state == .loading` is what makes the claim above literal.
        // `matchesIdentity()` short-circuits true whenever `startedFor == nil` (NB1's shape, and
        // right for PUBLISHING this round's own answer), so for a nil-started round cancellation
        // was the only guard left and the restore could write `previousState` over a value another
        // writer put there: `dropSession()` frees the slot mid-round, B signs in and publishes
        // `.loaded(B)`, and the straggler reverted the screen to `.signedOut`.
        defer {
            if Task.isCancelled, state == .loading, matchesIdentity(), state != previousState {
                state = previousState
            }
        }
        // Stage 7 fix 2 / I1(a): the account ALREADY on screen stays on screen while its own
        // refresh runs. This write used to be unconditional, and the foreground refresh (Stage 5 /
        // C2.1) then drove every return to foreground through `.loaded -> .loading -> .loaded` —
        // which `MeTabRoot.arm`'s third arm rendered as "Something went wrong" with a Retry, in the
        // Me tab and in Settings' Account section, tearing `MeSignedInView` down and re-running its
        // `.task` blocks each time. A `.failed` result still replaces the value below; a DIFFERENT
        // identity still clears it, because the uid `start()` has already set is what is compared —
        // rendering account A's record while B's `/me` is in flight is the render `scope(to:)`
        // exists to prevent.
        if state.me == nil || state.me?.uid != user?.uid { state = .loading }
        // CF-A-44, the `land()` window: a nil-started round is running for an account `start()` has
        // not observed, so `user` and `lastKnownUid` are both nil and `handle` refused that
        // account's own verdict. Firebase is the only live source of who this round is for. It only
        // ever ADDS: a nil answer (a guest's Retry) must not forget the account that just left, and
        // once `start()` has observed anybody (`user`, read AFTER the await) its write is the truth.
        if startedFor == nil, let uid = await auth.currentUser()?.uid, user == nil {
            lastKnownUid = uid
            // Durable too, for the one case the record is CONSULTED in: this account's verdict
            // wrote `pendingUid`, the session was already gone (the bare-401 path force-signs out
            // before the verdict arrives; `performDeletion` signs out itself) and the wipe failed
            // or the process died — all before `start()` drained its `.signedIn`. The relaunch then
            // finds nobody signed in and reads the holder from here; still naming the PREVIOUS
            // account, it would downgrade this one's device wipe to a row-only delete. (While
            // Firebase still holds the account, `signedIn == pending` and this is never read.)
            marker.lastSignedInUid = uid
        }
        for attempt in 1...max(1, maxAttempts) {
            do {
                let me = try await account.me()
                guard publishable() else { return }
                state = .loaded(me)
                bindSyncIfNeeded(to: me.uid)
                return
            } catch {
                guard publishable() else { return }
                switch error {
                // The transport already posts these (`AuthorizedTransport`'s 403 envelope check),
                // but a terminal account must drop its session even if that post is missed —
                // "Something went wrong" over a dead account is the wrong end state, not a banner.
                // Task 33 / review C2: ATTRIBUTED, like the transport's post. These two bypass the
                // centre entirely, so leaving them unattributed left the 403 path with a second
                // door into the wipe that the attribution never reached — A's in-flight 403
                // landing after B signed in still wiped B. `publishable()` has already established
                // that this round belongs to `user`, and there is no suspension between that guard
                // and here, so `user?.uid` IS the round's account — no `?? startedFor` fallback,
                // which Cubic showed is unreachable and only suggests a recovery that cannot run.
                case .blocked: handle(.blocked, for: user?.uid)
                case .deletedAccount: handle(.deleted, for: user?.uid)
                // Fix round 1 / I1: BOUNDED. `BearerRetry` surfaces a bare 401 in THREE cases —
                // the cross-account identity change (Task 7), `token(true)` returning nil (the
                // ordinary expired/failed-refresh path), and a freshly refreshed token still being
                // rejected. Only the first is followed by an auth transition, so parking at
                // `.loading` and waiting for the stream hung the other two forever: signed-in user,
                // no `/me`, no banner, and `RootView` rendering them as a guest with no explanation.
                // So: re-drive inside the budget (a new token may be minted between sends), then
                // fall through to `.unknown(let status)` and fail. No backoff — a rejected token is
                // not a network stall, and waiting does not make it acceptable.
                case .unknown(status: 401) where attempt < maxAttempts: continue
                case .network where attempt < maxAttempts:
                    await sleep(.seconds(attempt))
                    // Fix round 1 / M4: `AccountClient.send` maps `CancellationError` to `.network`
                    // by design and the real sleep's `try?` swallows the cancellation, so a screen
                    // that went away mid-refresh used to burn all three attempts and land on "No
                    // internet connection" — a banner over a session nobody is watching.
                    // The restore is identity-guarded too (Stage 9 round 2 / P1): `previousState`
                    // is the account this round found, and putting it back over a session that has
                    // since signed out or switched is the same stale write by another route — and
                    // since round 4 / NB-B the restore itself is the `defer` above, so a
                    // cancellation surfacing in the sleep returns here and is restored there.
                    guard publishable() else { return }
                    continue
                // Stage 9 round 2 / P2: a cached account beats an offline banner. The foreground
                // hook runs at `maxAttempts: 1`, so the retry arm above cannot fire (`1 < 1`) and
                // ONE transient failure on a return to the app replaced the loaded account with
                // "No internet connection" — a Retry card in the Me tab and in Settings' Account
                // section, and the Me-tab route to Favorites/Saved gone exactly when the device is
                // offline. With nothing loaded for this identity it still fails, as before.
                //
                // R9-P3 #13: `state.me?.uid == startedFor` was a restatement — `publishable()`
                // above has already established `user?.uid == startedFor`, and the `.loading` write
                // below it clears `state.me` unless it already matched `user`. So "a record is
                // loaded" IS "a record for this identity is loaded" by the time this case runs.
                // `startedFor != nil` stays: it is what keeps a nil-started round (NB1's shape,
                // where `matchesIdentity()` short-circuits and guarantees nothing) failing.
                case .network where startedFor != nil && state.me != nil: return
                case .network: state = .failed(code: nil, message: String(localized: "auth_error_network"))
                case .unknown(let status): state = .failed(code: status, message: String(localized: "auth_error_generic"))
                default: state = .failed(code: nil, message: String(localized: "auth_error_generic"))
                }
                return
            }
        }
    }

    /// Task 11: the identity `AuthClient.reload()` just answered. Firebase's auth-state listener
    /// does NOT fire when a reload flips `isEmailVerified`, so nothing else can move `user` off the
    /// stale value — and `RootView`'s outcome reads exactly that field, which would park a
    /// just-verified account on the verification screen forever.
    ///
    /// A DIFFERENT uid is refused: swapping identity is `start()`'s job, because only it re-scopes
    /// every store first, and doing it here would render the new account against the old one's rows.
    func adopt(_ reloaded: AuthUser) {
        guard user?.uid == reloaded.uid else { return }
        user = reloaded
    }

    /// Task 17: the account record the server just answered a profile edit with
    /// (`AccountRepository.applyProfileUpdate`). Nothing re-reads `/me` after a `PUT`, so without
    /// this every reader of `state.me` — the Profile screen's own next visit included — keeps
    /// rendering the value the edit replaced, until some unrelated refresh happens to run.
    ///
    /// A DIFFERENT uid is refused, for `adopt(_:)`'s reason: swapping identity is `start()`'s job,
    /// because only it re-scopes every per-user store first.
    func apply(_ updated: AccountMe) {
        guard state.me?.uid == updated.uid else { return }
        state = .loaded(updated)
    }

    /// Sign-out ONLY: the local library is deliberately kept (`AccountRepositoryImpl.kt:44-49`).
    /// Re-scoping to `""` is what hides the account's rows behind the guest's.
    func signOut() {
        // Nothing to drop, nothing to announce — and this is what stops `RootView`'s
        // consume -> handle -> signOut path from looping on the `.signedOut` it posts below.
        guard dropSession() else { return }
        // `.signedOut` is not a 403: it is posted so per-account holders can release state without
        // every one of them depending on the auth client (`AccountStatusCenter.swift`).
        status.post(.signedOut)
    }

    /// The drop itself, without the announcement. `false` when there was no session to drop.
    /// Split out so the deletion path can announce `.deleted` UNCONDITIONALLY: Firebase's own
    /// `delete()` fires the auth listener, which may have set `.signedOut` here first, and a
    /// terminal alert that depends on which of the two got there first is a coin toss.
    @discardableResult
    private func dropSession() -> Bool {
        // ABOVE the early return, all of it (Stage 9 round 2 / P1; Stage 9 / P2a; Task 24): the
        // round in flight belongs to the identity being dropped whatever `state` says, the SDKs
        // must forget on every path that reaches `.signedOut` first, and a queued push retry must
        // not outlive the drop. Every step is idempotent, so a drop the listener already performed
        // costs one extra no-op call and never a second spelling.
        tearDown()
        guard state != .signedOut else { return false }
        do {
            try auth.signOut()
        } catch {
            // Stage 5 / C1.3. `Auth.signOut()` calls `updateCurrentUser(nil, byForce: false,
            // savingToDisk: true)`, which assigns `_currentUser = nil` ONLY when the Keychain write
            // succeeded — so a throw means the session is still live and `idToken(forceRefresh:)`
            // still mints bearers for it. Publishing `.signedOut` over that is a lie the very next
            // request contradicts, and the next launch restores the account anyway. Stay signed in
            // and say something went wrong (WHAT, not WHY).
            state = .failed(code: nil, message: String(localized: "auth_error_generic"))
            return false
        }
        user = nil
        scope(to: "")
        state = .signedOut
        return true
    }

    /// Both sign-out routes' shared line (Cubic round 6 / P2): the round in flight belongs to the
    /// identity being dropped, and left in the slot the NEXT identity's `refresh()` JOINS it
    /// instead of asking for its own record — B never fetches, A's answer is dropped as stale, and
    /// the Me tab and Settings' Account section park on the Retry card. `dropSession()` has done
    /// this since round 2 / P1; the auth stream's own `.signedOut` arm had not, so a
    /// Firebase-initiated sign-out (a refused forced mint force-signs the user out) left the hole
    /// open on the path that reaches it without going through `signOut()`.
    private func cancelInFlight() {
        inFlight?.cancel()
        inFlight = nil
    }

    /// Everything a session drop owes BEFORE the state changes, in one place: the provider SDKs
    /// forget their own sessions, the round in flight is cancelled (Stage 9 round 2 / P1 — it
    /// belongs to the identity being dropped), and sync is unbound (Task 24 — a push retry queued
    /// while the user was still signed in otherwise fires after the drop and pushes the previous
    /// account's dirty rows under whatever bearer is current; `SyncModule.kt:46-53`). Reached by
    /// `dropSession()` — sign-out, the blocked verdict, the age-ineligible teardown, the deletion —
    /// and by the auth stream's own `.signedOut` arm.
    private func tearDown() {
        for provider in providers { provider.signOutProvider() }
        cancelInFlight()
        unbindSync()
    }

    /// OFF the critical path, always: `bind` is a merge + pull + push, and Android fires it in its
    /// own coroutine precisely so the splash's route decision never waits on a network round trip
    /// (`SplashFragment.kt:129-141`). Unstructured for the same reason `refreshIfSignedIn`'s
    /// foreground caller is -- a `.task`-scoped caller would cancel it on navigation.
    /// **The invariant** (Task 24 review / M1). The uid bound here is `AccountMe.uid`; the uid the
    /// per-user stores are scoped by is `AuthUser.uid` (`:159`). Against the real backend they are
    /// always the same value — `AccountController.java:111,175` derives the account document from
    /// `principal.getUid()` — and everything downstream depends on that: if they ever diverged,
    /// `tagAnonRows` would retag guest rows to an id no store reads and `dirtyRows(uid:)` would stay
    /// permanently empty, i.e. sync silently dead with nothing to see.
    ///
    /// Stated here rather than asserted: `theBoundUidIsTheAccountsNotTheFirebaseUsers`
    /// (`SyncTriggerTests.swift:119`) constructs the divergence deliberately, to pin WHICH of the
    /// two fields is read, so an `assert(uid == user?.uid)` would trap that test in Debug — the one
    /// build the gate runs.
    ///
    /// Task 24 review / M3: the guard consults the same three terminal conditions `syncableUid`
    /// does. Unreachable today (a 200 `/me` cannot coexist with a terminal verdict for the same
    /// account, and `boundUid` already blocks a rebind) — but this was the only trigger site that
    /// did not, which is the asymmetry a later reader trips over.
    private func bindSyncIfNeeded(to uid: String) {
        guard let sync, deletion == nil, !isAgeIneligible, !uid.isEmpty, boundUid != uid else { return }
        boundUid = uid
        Task { await sync.bind(uid: uid) }
    }

    /// Returns the unbind it started so the ONE caller that must wait for it can (`performDeletion`:
    /// `SyncManager.unbind()` queues on the exclusion behind a pull already in flight, and the wipe
    /// must not run until that pull has let go — a page landing after the wipe would re-insert the
    /// rows the server just erased). Every other caller lets it run on its own.
    @discardableResult
    private func unbindSync() -> Task<Void, Never>? {
        guard let sync, boundUid != nil else { return nil }
        boundUid = nil
        return Task { await sync.unbind() }
    }

    /// The age-ineligible teardown, in ONE place (Stage 8 / S7). `AgeIneligibleScreen.acknowledge()`
    /// and `ProfileViewModel.confirmAgeIneligibleSignOut()` both spelled `try? await
    /// auth.deleteUser()` then `session.signOut()`; Stage 5 / C4.1 is that one server verdict has
    /// one residue, and copying the pair into the second site was that duplication in a new place.
    /// Both callers keep their own navigation — that is the half that genuinely differs.
    ///
    /// `try?`: the tokens are already revoked server-side, so a failure here changes nothing the
    /// user can act on (`AgeIneligibleViewModel.kt:36-40` logs and proceeds).
    ///
    /// Stage 9 / P2a: `.signedOut` is posted UNCONDITIONALLY, mirroring `handleDeletion`'s
    /// `.deleted` and for the same reason. `auth.deleteUser()` fires the Firebase listener across
    /// its own suspension, so `start()`'s stream arm can reach `.signedOut` first — and
    /// `dropSession()` then returns false, which is how the profile path came to announce NOTHING
    /// to the per-account holders. A terminal announcement that depends on who won that race is a
    /// coin toss.
    func terminateAgeIneligible() async {
        // BEFORE the awaits: the screen has to be up while the delete runs, or the drop below
        // renders a bare guest shell for as long as Firebase takes to answer.
        isAgeIneligible = true
        // Round 2 / item 5: captured BEFORE the awaits — the delete clears `user`. Round 3 / item 3:
        // `?? lastKnownUid`, because Firebase can force-sign the child out, and `start()` can drain
        // that `.signedOut`, before the 422 is processed — `user` is nil by then and
        // `lastKnownUid` is what survives that sign-out by design.
        let leaving = user?.uid ?? lastKnownUid
        try? await auth.deleteUser()
        dropSession()
        // The account is deleted through Firebase, so its uid does not stay behind as the device's
        // holder. Only while the record still names it, for `redeemed(_:)`'s reason.
        if let leaving, marker.lastSignedInUid == leaving { marker.lastSignedInUid = nil }
        status.post(.signedOut)
    }

    /// The terminal screen's OK, and the whole of it (R7-P1 #3): the delete, the sign-out and the
    /// announcement all ran when the verdict arrived, so acknowledging it only navigates.
    func acknowledgeAgeIneligible() { isAgeIneligible = false }

    /// .blocked -> signOut; .deleted -> the device wipe; .signedOut -> signOut.
    /// Task 33 / CF-A-44: a verdict is honoured only by the account it was minted FOR.
    ///
    /// `handleDeletion()` below stamps `marker.pendingUid` from whoever is signed in right now and
    /// wipes that account's scope — so a `.deleted` recorded for A and delivered after B completed a
    /// sign-in destroyed B's library and wrote B's uid into A's marker. The RECORD end has carried
    /// the uid since Cubic round 6 / P2b; this is the delivery end, where it stopped.
    ///
    /// A nil `uid` is UNATTRIBUTED, not "any account" — it is honoured, exactly as before this
    /// attribution existed. The session's own announcements and the splash advisory are the
    /// intended nil sources; a 403 envelope answered to an UNSIGNED request is a third one that is
    /// not (CF-A-47), so nil is a "no worse than before" arm rather than a proof of safety.
    ///
    /// - Returns: whether the verdict was acted on. `RootView` uses it to suppress the terminal
    ///   ALERT too — telling B their account was deleted while deliberately not deleting anything
    ///   would be worse than the silence.
    @discardableResult
    func handle(_ event: AccountStatusEvent, for uid: String? = nil) -> Bool {
        guard uid == nil || uid == user?.uid || uid == lastKnownUid else { return false }
        switch event {
        case .blocked, .signedOut:
            // A block is REVERSIBLE, so the local library survives it exactly as an ordinary
            // sign-out does.
            signOut()
        case .deleted:
            // The verdict's own uid, not `user`'s: on the bare-401 path the account is already
            // signed out by now, so re-deriving the marker from `user` would write no marker at
            // all and an interrupted wipe could never be resumed.
            handleDeletion(for: uid)
        }
        return true
    }

    /// The account is gone. BOTH paths land here — the admin-side 403 `ACCOUNT_DELETED` envelope
    /// (`deletingFirebaseUser: false`; the account is already gone server-side and this user can no
    /// longer re-authenticate, so no Firebase delete is attempted) and the user's own successful
    /// `DELETE /api/account/me` (`true`).
    ///
    /// **DETACHED and uncancelled (CF-G-5).** Android runs this cleanup in `viewModelScope`, so a
    /// user who leaves the screen while the 204 is landing keeps every local row of an account that
    /// no longer exists. Nothing about this work belongs to a screen's lifetime.
    ///
    /// **Latched, so it runs exactly once.** Three arrivals are possible for one deletion: the
    /// transport's post, `fetch()`'s own `.deletedAccount` catch, and the `.deleted` this posts —
    /// each of which reaches `handle(_:)`. The latch is cleared on the next sign-in (`start()`), so
    /// a second account deleted in the same process still wipes.
    ///
    /// **Wipe BEFORE the sign-out**, and before Firebase: `dropSession()` re-scopes every per-user
    /// store, which re-READS it, and a re-read that lands before the rows are gone leaves deleted
    /// objects on screen; and a Firebase failure must not be able to skip a device the server has
    /// already erased.
    @discardableResult
    func handleDeletion(deletingFirebaseUser: Bool = false, for verdictUid: String? = nil) -> Task<Void, Never> {
        // CF-A-53 / C1: `handle` guards its verdicts, but this is reachable directly — the delete
        // screen's 204 lands here — and a fresh latch is taken for WHOEVER is current. A deletion
        // that names an account this session neither holds nor just held is not this session's
        // to run: no latch, no device wipe, no Firebase delete. The named account's debt is paid
        // by uid, which cannot touch whoever holds the device. nil is unattributed, as in `handle`.
        if let verdictUid, verdictUid != user?.uid, verdictUid != lastKnownUid {
            if wipeRows(verdictUid) == nil { redeemed(verdictUid) }
            return Task {}
        }
        if let deletion {
            // Fix round 1 / I2: the latch must not SWALLOW a later `true`. The 403 envelope can
            // arrive before the user's own 204 (a concurrent `/me` against an account the server
            // deletes mid-DELETE), and that arrival latches with `false` — so the credential this
            // path could still delete would outlive the account.
            //
            // Stage 3 / I1: it is a FLAG, never a chained task. The chain this replaces awaited
            // `deletion.value`, which only completes after `performDeletion` has run
            // `dropSession()` — so `FirebaseAuthClient.deleteUser()` went through `requireUser()`
            // against a nil `currentUser`, threw `.unknown`, and was swallowed by its own `try?`.
            // The credential the chain existed to delete was never deleted. `performDeletion`
            // re-reads this flag immediately before its own delete instead, which is BEFORE the
            // sign-out.
            deletingFirebase = deletingFirebase || deletingFirebaseUser
            return deletion
        }
        deletingFirebase = deletingFirebaseUser
        // Stage 5 / C1.2: durable BEFORE the detached task exists. `deleteAccountPermanently`
        // revokes and deletes the Firebase user, so on the next launch `/me` answers a bare 401 and
        // nothing can reach `handleDeletion()` again — an interrupted cleanup would be owed to this
        // device forever. `start()` redeems the marker.
        //
        // Stage 7 fix 2 / M2: NEVER an empty uid. `?? ""` stored a marker that names nobody, and
        // the getter reports `""` as pending — so a device with no identity to record would wipe
        // itself on the next launch whoever had signed in by then. With no uid there is no marker;
        // the wipe below still runs, it just cannot be resumed, which is the honest answer.
        // Stage 7 re-review 2 / m1: `!uid.isEmpty` too — the getter reports `""` as pending, so an
        // empty uid is the same marker that names nobody by a different route.
        // `verdictUid` FIRST (Task 33, review C1): the account the verdict names outlives the
        // session that held it, and on the bare-401 path both `user` and `state.me` are already nil
        // by the time this runs.
        var owed: String?
        if let uid = verdictUid ?? user?.uid ?? state.me?.uid, !uid.isEmpty {
            marker.pendingUid = uid
            owed = uid
        }
        // No `@MainActor in` on the closure: `performDeletion` carries the isolation and the hop.
        let task = Task.detached { [owed] in
            await self.performDeletion(owed: owed)
        }
        deletion = task
        return task
    }

    /// `owed` is the uid this deletion wrote into the marker, or nil when it wrote none — and then
    /// there is nothing of its own to clear.
    ///
    /// **Who holds the device is re-asked after every `await` here (CF-A-53)** — which is NOT
    /// every step: the check before `wipe()` is the last one before its destructive half, and
    /// `LocalAccountWiper.wipe()` awaits (the offline teardown) before its synchronous deletes
    /// with no check of its own. An account arriving inside that window is still wiped; what this
    /// guarantees is that nothing AFTER the wipe is done to them.
    ///
    /// Nothing stops another account SIGNING IN while this is parked — behind a slow pull at
    /// `unbindSync`, inside the wipe, inside Firebase. The user can dismiss the terminal alert (a
    /// sign-out) and the next account's `.signedIn` clears the latch, which is what re-opens its
    /// sync BIND and pull. Resumed blind, this wiped that account's device, re-scoped its stores
    /// to `""`, signed it out and, on the self-delete latch, deleted ITS Firebase credential.
    ///
    /// `resumePendingDeletion` asks for POSITIVE evidence (the pending account IS the holder)
    /// because a launch has nothing else to go on. This asks for NEGATIVE evidence — "has a
    /// DIFFERENT account taken over since the latch" — because a legitimate deletion often has no
    /// positive evidence to show: on the bare-401 path Firebase force-signs the account out before
    /// the verdict arrives, so `user` is nil and Firebase holds nobody throughout (on the
    /// self-delete path `user == owed` until the drop at the very end). The evidence is three
    /// reads, any of which naming somebody other than `owed` is a takeover: who Firebase holds
    /// right now, `user`, and `lastKnownUid` (they came AND went). With no `owed` there is nobody
    /// to compare against and nothing to delete by uid, and `user` was nil at the latch or `owed`
    /// would exist — so anyone signed in now arrived since, and then nothing here runs at all.
    private func performDeletion(owed: String?) async {
        func takenOver() async -> Bool {
            guard let owed else { return user != nil }
            // Firebase too, and LAST so the session's own fields are read after the hop: an
            // account Firebase already holds that neither `start()` nor the seed has observed is
            // invisible to `user`/`lastKnownUid`, and `deleteUser()` deletes whoever Firebase holds.
            let held = await auth.currentUser()?.uid
            return [held, user?.uid, lastKnownUid].contains { $0 != nil && $0 != owed }
        }
        // Stage 4 S9: quiesce sync BEFORE the wipe, or a pull already parked in the network
        // re-creates the rows the wipe is about to erase.
        await unbindSync()?.value
        // Taken over BEFORE the wipe: the device is theirs, so the debt is paid by uid — which
        // cannot touch them — and the marker is kept if that delete reports an error. No
        // `.deleted` either: it is unattributed, `handle` honours it, and posted under the new
        // account it would start a deletion FOR them (CF-A-47's shape).
        guard await !takenOver() else {
            if let owed, wipeRows(owed) == nil { redeemed(owed) }
            return
        }
        let wipeError = await wipe()
        // Stage 5 / C2.2: a wipe that hit a full or corrupt store keeps the marker, so the next
        // launch tries again rather than leaving the rows on disk under an "account deleted" alert.
        if wipeError == nil, let owed { redeemed(owed) }
        // `try?`: the server has already deleted the account, so there is nothing to roll back and
        // nowhere to route a failure to. A Firebase user whose `delete()` was refused
        // (`requiresRecentLogin`) is signed out below anyway, and its next `/me` answers the 403
        // envelope — the same terminal path, without a re-auth prompt for an account that no longer
        // exists. Stage 3 / I1: read HERE, so a `true` that arrived while the wipe was running
        // still deletes the credential, and always before `dropSession()`. CF-A-53: and never
        // once somebody else holds the device — `deleteUser()` deletes whoever Firebase holds.
        if deletingFirebase, await !takenOver() { try? await auth.deleteUser() }
        // Taken over DURING the wipe or the Firebase delete: what is done is done, but the session
        // is theirs — no sign-out, no `.deleted` — and the stores `LocalAccountWiper` re-scoped to
        // `""` go back to the account that is actually on screen.
        guard await !takenOver() else {
            if let arrived = user?.uid { scope(to: arrived) }
            return
        }
        dropSession()
        // Round 2 / I1: ATTRIBUTED. `post` hops through a main-actor task, so somebody else can be
        // current by the time `RootView` consumes this — and unattributed, `handle` honoured it
        // and took a fresh latch for THEM. `lastKnownUid` survives the drop above, so the account
        // this is for still raises its own terminal alert.
        status.post(.deleted, for: owed)
    }

    /// The last identity this session held, kept ACROSS the sign-out that ends it (Task 33, review
    /// C1). `user` cannot answer "whose verdict is this" on the path that produces most verdicts:
    /// Firebase force-signs the account out INSIDE the refused mint that mints the verdict
    /// (`FirebaseAuthClient.idToken`'s trace), so `.signedOut` reaches `start()` a whole network
    /// round trip before the verdict reaches `handle`, and `user` — and `state.me` with it — is
    /// already nil. Checking `user` alone therefore REFUSED exactly the deletions the wipe exists
    /// for, which is a worse bug than the one the attribution was added to fix.
    ///
    /// Overwritten by the next `.signedIn`, never cleared by `.signedOut`: "the account that just
    /// left" is precisely who a late verdict can legitimately be about, while an account that has
    /// been REPLACED is exactly who it must not be about.
    private var lastKnownUid: String?

    /// The deletion latch. Non-nil from the first `handleDeletion()` until the next sign-in.
    private var deletion: Task<Void, Never>?
    /// Whether the Firebase credential is already being deleted, so a second `true` (or a `true`
    /// that arrives after a `false` latched) never asks twice. Assigned on every fresh latch, so
    /// the next account in this process starts from its own answer.
    private var deletingFirebase = false

    #if DEBUG
    /// The screenshot rig's launch barrier (`FitrahTubeApp.awaitFakeAccountIfSignedIn`): yields
    /// until `/me` has landed, so the `-fitrah-seed-*` hooks write through per-user stores that
    /// `start()` has already re-scoped. Returns the yields spent, which is the only observable
    /// difference between stopping at the account and burning the bound.
    /// A `while`, not `for … where`: `where` SKIPS an iteration rather than ending the loop, so the
    /// shape this replaces spent the whole bound on every fixture launch. Waiting for `.loading` to
    /// end is not the same test and would be wrong — the initial state is `.signedOut`, so it would
    /// fall through before `start()` had run at all.
    @discardableResult
    func awaitAccount(bound: Int) async -> Int {
        var yields = 0
        while state.me == nil, yields < bound {
            yields += 1
            await Task.yield()
        }
        return yields
    }
    #endif

    /// Unchanged uid -> untouched store: every `currentUserId` write re-runs that store's fetch,
    /// and launch would otherwise fire three pointless SwiftData queries to set `""` to `""`.
    private func scope(to uid: String) {
        for store in stores where store.currentUserId != uid { store.currentUserId = uid }
    }
}
