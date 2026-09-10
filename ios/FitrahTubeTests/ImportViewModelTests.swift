import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

/// Task 29: the Import SCREEN's decisions — the five states, the Sharī'ah caution gate and ruling
/// F9's revoke. **No Firebase, no Google SDK, no network**: the authorizer is `FakeYouTubeAuthorizer`
/// and both HTTP legs (googleapis for the paginators, the FitrahTube backend for `ImportClient`) are
/// `ScriptedTransport`.
///
/// The engine itself is pinned by `ImportEngineTests`; this suite only drives it.
@MainActor
@Suite(.perTest)
struct ImportViewModelTests {

    private static let base = URL(string: "https://api.test/")!

    /// The approved fixture ids, and nothing else (dispatcher fixture rule).
    private enum Fixture {
        static let channel = "UCmMcOjsVehVlEOteyrhjI2Q"
        static let playlist = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
        static let video = "xc7keR2piUM"
        static let token = "ya29.fake-access-token"

        static func page(_ items: [String], _ nextPageToken: String? = nil) -> String {
            let next = nextPageToken.map { ",\"nextPageToken\":\"\($0)\"" } ?? ""
            return "{\"items\":[\(items.joined(separator: ","))]\(next)}"
        }

        static func subscriptions(_ ids: [String]) -> String {
            page(ids.map {
                "{\"id\":\"sub-1\",\"snippet\":{\"title\":\"Alafasy\",\"resourceId\":{\"channelId\":\"\($0)\"}}}"
            })
        }

        static func playlists(_ ids: [String]) -> String {
            page(ids.map { "{\"id\":\"\($0)\",\"snippet\":{\"title\":\"Tafsir series\"}}" })
        }

        static func videos(_ ids: [String]) -> String {
            page(ids.map {
                "{\"id\":\"\($0)\",\"snippet\":{\"title\":\"Lecture\",\"channelId\":\"\(channel)\"}}"
            })
        }

        static func resolve(_ rows: [(String, String, String)]) -> String {
            let items = rows.map { id, type, disposition in
                "{\"youtubeId\":\"\(id)\",\"type\":\"\(type)\",\"disposition\":\"\(disposition)\"}"
            }
            return "{\"results\":[\(items.joined(separator: ","))]}"
        }
    }

    // MARK: - Rig

    /// The three legs a run touches, in the order `YouTubeImportSource` walks them
    /// (`CandidateType.allCases`: channel, playlist, video) followed by the backend resolve.
    private struct Rig {
        var model: ImportViewModel
        var authorizer: FakeYouTubeAuthorizer
        var youtube: ScriptedTransport
        var backend: ScriptedTransport
        var container: ModelContainer
    }

    private func rig(youtube: [HTTPResponse], backend: [HTTPResponse] = [],
                     authorizer: FakeYouTubeAuthorizer = FakeYouTubeAuthorizer(token: Fixture.token),
                     backendPark: (@Sendable (Int) async -> Void)? = nil) throws -> Rig {
        let container = try ModelContainer(
            for: FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let favorites = SwiftDataFavoritesStore(modelContainer: container, onDirty: { _ in })
        let subscriptions = SwiftDataSubscriptionsStore(modelContainer: container, onDirty: { _ in })
        let playlists = SwiftDataSavedPlaylistsStore(modelContainer: container, onDirty: { _ in })
        for store in [favorites as any UserScoped, subscriptions, playlists] { store.currentUserId = "uid-1" }

        let youtubeTransport = ScriptedTransport(youtube)
        let backendTransport = ScriptedTransport(backend, park: backendPark)
        let pipeline = ImportPipeline(
            client: ImportClient(transport: backendTransport, baseURL: Self.base,
                                 deviceId: DeviceId(value: "dev-123")),
            favorites: favorites, subscriptions: subscriptions, playlists: playlists,
            now: { Date(timeIntervalSince1970: 1_756_800_000) })
        return Rig(model: ImportViewModel(authorizer: authorizer,
                                          source: YouTubeImportSource(transport: youtubeTransport),
                                          pipeline: pipeline),
                   authorizer: authorizer, youtube: youtubeTransport, backend: backendTransport,
                   container: container)
    }

    /// One of each type, all three paginators answering a single page.
    private func fullLibrary() -> [HTTPResponse] {
        [.json(200, Fixture.subscriptions([Fixture.channel])),
         .json(200, Fixture.playlists([Fixture.playlist])),
         .json(200, Fixture.videos([Fixture.video]))]
    }

    private func reviewed(_ rig: Rig) async throws -> (candidates: [ImportCandidate], selected: Set<String>) {
        rig.model.start()
        await rig.model.job?.value
        guard case .review(let candidates, let selected, _) = rig.model.state else {
            Issue.record("expected .review, got \(rig.model.state)")
            throw CancellationError()
        }
        return (candidates, selected)
    }

    // MARK: - Step 1: the states

    /// `ImportViewModel.kt:209-213`: everything arrives selected, so the common case ("import it
    /// all") is one tap and the user de-selects rather than hunts.
    @Test func everyFetchedCandidateStartsSelected() async throws {
        let rig = try rig(youtube: fullLibrary())
        let (candidates, selected) = try await reviewed(rig)
        #expect(candidates.count == 3)
        #expect(selected == Set([Fixture.channel, Fixture.playlist, Fixture.video]))
        #expect(rig.authorizer.authorizeCount == 1)
    }

    /// `ImportUiState.kt:22-26`. Zero candidates is an ERROR, never an empty `.review` — and the
    /// retry is offered only when a type actually failed, because that is the only case a retry
    /// can recover. A user with an empty YouTube library would otherwise be handed a button that
    /// re-runs three empty paginators forever.
    @Test func zeroCandidatesIsAnErrorAndOnlyAFailedTypeMakesItRetryable() async throws {
        let allEmpty = try rig(youtube: [.json(200, Fixture.page([])), .json(200, Fixture.page([])),
                                         .json(200, Fixture.page([]))])
        allEmpty.model.start()
        await allEmpty.model.job?.value
        #expect(allEmpty.model.state == .error(messageKey: "empty_state_no_content", retryable: false))

        // A 403 on playlists — the account exposes none — with the other two empty.
        let oneFailed = try rig(youtube: [.json(200, Fixture.page([])), .json(403, "{}"),
                                          .json(200, Fixture.page([]))])
        oneFailed.model.start()
        await oneFailed.model.job?.value
        #expect(oneFailed.model.state == .error(messageKey: "empty_state_no_content", retryable: true))
    }

    /// A partial failure with SURVIVORS is not an error at all: the types that answered are
    /// reviewable, and the banner names the one that did not.
    @Test func aFailedTypeWithSurvivorsReachesReviewCarryingThePartialFailure() async throws {
        let rig = try rig(youtube: [.json(200, Fixture.subscriptions([Fixture.channel])),
                                    .json(403, "{}"),
                                    .json(200, Fixture.videos([Fixture.video]))])
        rig.model.start()
        await rig.model.job?.value
        guard case .review(let candidates, let selected, let failures) = rig.model.state else {
            Issue.record("expected .review, got \(rig.model.state)"); return
        }
        #expect(candidates.map(\.youtubeId) == [Fixture.channel, Fixture.video])
        #expect(selected.count == 2)
        #expect(failures == [.playlist])
    }

    /// Addendum (2): `ImportPipeline` is not re-entrant, and the Review → Importing transition
    /// crosses a suspension, so the state check alone does not serialise two taps in one frame
    /// (`ImportViewModel.kt:120-123`). The backend must see ONE resolve, not two.
    @Test func aSecondConfirmWhileAnImportIsRunningIsANoOp() async throws {
        let gate = Gate()
        let rig = try rig(youtube: fullLibrary(),
                          backend: [.json(200, Fixture.resolve([(Fixture.channel, "CHANNEL", "APPROVED"),
                                                                (Fixture.playlist, "PLAYLIST", "APPROVED"),
                                                                (Fixture.video, "VIDEO", "APPROVED")]))],
                          backendPark: { _ in await gate.block() })
        _ = try await reviewed(rig)

        rig.model.confirmImport()
        await gate.waitUntilBlocked()
        // The second tap lands while the first resolve is genuinely in flight.
        rig.model.confirmImport()
        await gate.release()
        await rig.model.job?.value

        #expect(rig.backend.sent.count == 1)
        guard case .done(let summary) = rig.model.state else {
            Issue.record("expected .done, got \(rig.model.state)"); return
        }
        #expect(summary.added == 3)
    }

    /// `:141-144`. The first `.importing` emission is a FRESH zero, not the pipeline's last value:
    /// seeding from the previous run would flash that run's DONE frame for one frame of a
    /// re-import, i.e. "3 of 3" before the second run has written anything.
    @Test func theFirstImportingEmissionIsAFreshZeroNotThePreviousRunsDoneFrame() async throws {
        let rig = try rig(youtube: fullLibrary(),
                          backend: [.json(200, Fixture.resolve([(Fixture.channel, "CHANNEL", "APPROVED"),
                                                                (Fixture.playlist, "PLAYLIST", "APPROVED"),
                                                                (Fixture.video, "VIDEO", "APPROVED")]))])
        _ = try await reviewed(rig)
        rig.model.confirmImport()
        // Synchronously, before the pipeline's first `progress` callback can run.
        #expect(rig.model.state == .importing(.resolving, processed: 0, total: 0))
        await rig.model.job?.value
        #expect(rig.model.state == .done(ImportSummary(added: 3, sentForReview: 0, skipped: 0,
                                                       alreadyPresent: 0, processed: 3, total: 3,
                                                       rateLimited: false)))
    }

    /// The property Android's sticky `NeedsConsent` protects (`:76-86`), in the shape iOS has:
    /// `authorize()` raises Google's consent sheet itself, so a second `start()` while the first is
    /// in flight must not ask the SDK a second time. `authorizeCount` is the sheet count.
    @Test func aSecondStartWhileAuthorizingCannotRaiseASecondConsentSheet() async throws {
        let gate = Gate()
        let rig = try rig(youtube: fullLibrary(),
                          authorizer: FakeYouTubeAuthorizer(token: Fixture.token, gate: gate))
        rig.model.start()
        await gate.waitUntilBlocked()
        #expect(rig.model.state == .authorizing)
        rig.model.start()
        rig.model.retry()
        await gate.release()
        await rig.model.job?.value

        #expect(rig.authorizer.authorizeCount == 1)
        guard case .review = rig.model.state else {
            Issue.record("expected .review, got \(rig.model.state)"); return
        }
    }

    /// F7 (`:154-157`), in the shape a non-throwing `async` method has: a CANCELLED run writes
    /// nothing at all. Turning cooperative cancellation into `.error` would banner a failure at the
    /// exact moment the user asked for the work to stop — here, by revoking mid-fetch.
    @Test func aCancelledRunNeverWritesAnErrorState() async throws {
        let gate = Gate()
        let rig = try rig(youtube: fullLibrary(),
                          authorizer: FakeYouTubeAuthorizer(token: Fixture.token, gate: gate))
        rig.model.start()
        await gate.waitUntilBlocked()
        let running = rig.model.job
        rig.model.revoke()
        await gate.release()
        await running?.value

        #expect(rig.model.state == .idle)
        #expect(rig.authorizer.forgetCount == 1)
    }

    /// `:161-163`. `retry()` IS `start()` — the token is in memory only and the three paginators
    /// are cheap, so there is no resumable midpoint to be clever about.
    @Test func retryReRunsTheWholeFlowFromAuthorization() async throws {
        let rig = try rig(youtube: [.json(200, Fixture.page([])), .json(200, Fixture.page([])),
                                    .json(200, Fixture.page([]))] + fullLibrary())
        rig.model.start()
        await rig.model.job?.value
        #expect(rig.model.state == .error(messageKey: "empty_state_no_content", retryable: false))

        rig.model.retry()
        await rig.model.job?.value
        #expect(rig.authorizer.authorizeCount == 2)
        guard case .review(_, let selected, _) = rig.model.state else {
            Issue.record("expected .review, got \(rig.model.state)"); return
        }
        #expect(selected.count == 3)
    }

    /// The user dismissed Google's sheet. Silent — back to idle, never a banner and never an error
    /// offering to retry what they just declined (`YouTubeAuthorizerError.cancelled`).
    @Test func aDismissedConsentSheetGoesQuietlyBackToIdle() async throws {
        let rig = try rig(youtube: [], authorizer: FakeYouTubeAuthorizer(error: .cancelled))
        rig.model.start()
        await rig.model.job?.value
        #expect(rig.model.state == .idle)
    }

    /// WHAT, never why: `.unavailable` and `.failed` are the same sentence to a user, and neither
    /// leaks a fact about their Google account.
    @Test func aRefusedAuthorizationIsOneRetryableErrorWhateverTheSdkSaid() async throws {
        for failure in [YouTubeAuthorizerError.failed, .unavailable] {
            let rig = try rig(youtube: [], authorizer: FakeYouTubeAuthorizer(error: failure))
            rig.model.start()
            await rig.model.job?.value
            #expect(rig.model.state == .error(messageKey: "auth_error_generic", retryable: true),
                    "\(failure)")
        }
    }

    // MARK: - Selection

    @Test func togglingFlipsOneCandidateAndLeavesTheRestAlone() async throws {
        let rig = try rig(youtube: fullLibrary())
        _ = try await reviewed(rig)
        rig.model.toggle(Fixture.video)
        guard case .review(_, let afterOff, _) = rig.model.state else {
            Issue.record("expected .review"); return
        }
        #expect(afterOff == Set([Fixture.channel, Fixture.playlist]))
        rig.model.toggle(Fixture.video)
        guard case .review(_, let afterOn, _) = rig.model.state else {
            Issue.record("expected .review"); return
        }
        #expect(afterOn.count == 3)
    }

    /// `:104-114`. A group header clears or fills exactly its own type.
    @Test func aGroupHeaderSelectsAndClearsOnlyItsOwnType() async throws {
        let rig = try rig(youtube: fullLibrary())
        _ = try await reviewed(rig)
        rig.model.setGroupSelected(.channel, false)
        guard case .review(_, let cleared, _) = rig.model.state else {
            Issue.record("expected .review"); return
        }
        #expect(cleared == Set([Fixture.playlist, Fixture.video]))
        rig.model.setGroupSelected(.channel, true)
        guard case .review(_, let refilled, _) = rig.model.state else {
            Issue.record("expected .review"); return
        }
        #expect(refilled.count == 3)
    }

    /// Neither selection call means anything outside `.review`, and neither may resurrect it.
    @Test func selectionIsANoOpOutsideReview() throws {
        let rig = try rig(youtube: [])
        rig.model.toggle(Fixture.video)
        rig.model.setGroupSelected(.video, true)
        #expect(rig.model.state == .idle)
    }

    /// Only the selected rows are sent. The two cleared ones are never resolved, never written.
    @Test func onlyTheSelectedCandidatesReachTheBackend() async throws {
        let rig = try rig(youtube: fullLibrary(),
                          backend: [.json(200, Fixture.resolve([(Fixture.video, "VIDEO", "APPROVED")]))])
        _ = try await reviewed(rig)
        rig.model.setGroupSelected(.channel, false)
        rig.model.setGroupSelected(.playlist, false)
        rig.model.importTapped()
        rig.model.acceptCaution()
        await rig.model.job?.value

        // Decoded, not substring-matched: a VIDEO candidate carries its uploader's `channelId`, so
        // a raw `contains(Fixture.channel)` on the body is true for a request that sent no channel
        // at all — the trap `ImportPipeline`'s "NEVER `candidate.channelId`" comment names, one
        // layer up.
        let body = try #require(rig.backend.sent.first?.body)
        struct Sent: Decodable { struct Item: Decodable { let youtubeId: String }; let items: [Item] }
        let sent = try JSONDecoder().decode(Sent.self, from: body)
        #expect(sent.items.map(\.youtubeId) == [Fixture.video])
    }

    // MARK: - Step 2: the Sharī'ah caution gate

    /// `ImportFromYouTubeFragment.kt:199-217`. The Import button raises the gate and starts
    /// NOTHING; dismissing it leaves the state at `.review` with the selection intact and no
    /// request sent. The gate lives on the ViewModel precisely so this is not a `@State` flag a
    /// later screen edit can route around.
    @Test func theImportButtonRaisesTheCautionGateAndStartsNothing() async throws {
        let rig = try rig(youtube: fullLibrary(), backend: [])
        let (_, selected) = try await reviewed(rig)

        rig.model.importTapped()
        #expect(rig.model.isCautionPresented)
        guard case .review(_, let stillSelected, _) = rig.model.state else {
            Issue.record("expected .review, got \(rig.model.state)"); return
        }
        #expect(stillSelected == selected)
        #expect(rig.backend.sent.isEmpty)

        rig.model.dismissCaution()
        #expect(rig.model.isCautionPresented == false)
        guard case .review = rig.model.state else {
            Issue.record("dismissing left \(rig.model.state)"); return
        }
        #expect(rig.backend.sent.isEmpty)
    }

    /// Continue is the ONE path through. `acceptCaution()` outside an open gate does nothing, so a
    /// stray call cannot stand in for the dialog.
    @Test func onlyContinueOnTheOpenGateStartsTheImport() async throws {
        let rig = try rig(youtube: fullLibrary(),
                          backend: [.json(200, Fixture.resolve([(Fixture.channel, "CHANNEL", "APPROVED"),
                                                                (Fixture.playlist, "PLAYLIST", "APPROVED"),
                                                                (Fixture.video, "VIDEO", "APPROVED")]))])
        _ = try await reviewed(rig)

        rig.model.acceptCaution()
        #expect(rig.backend.sent.isEmpty)
        guard case .review = rig.model.state else {
            Issue.record("a gate-less accept left \(rig.model.state)"); return
        }

        rig.model.importTapped()
        rig.model.acceptCaution()
        #expect(rig.model.isCautionPresented == false)
        await rig.model.job?.value
        #expect(rig.backend.sent.count == 1)
        guard case .done = rig.model.state else {
            Issue.record("expected .done, got \(rig.model.state)"); return
        }
    }

    /// The gate's copy is substantive religious guidance, not chrome: it must resolve in all three
    /// locales and must never be the English sentence in Arabic.
    @Test func theCautionCopyResolvesInEnglishArabicAndDutch() throws {
        let keys = ["import_caution_title", "import_caution_message", "import_caution_continue"]
        let bundles = try ["en", "ar", "nl"].map { locale in
            (locale, try #require(Bundle.main.path(forResource: locale, ofType: "lproj")
                .flatMap(Bundle.init(path:))))
        }
        for (locale, bundle) in bundles {
            for key in keys {
                #expect(bundle.localizedString(forKey: key, value: nil, table: nil) != key,
                        "\(key) unresolved in \(locale)")
            }
        }
        let english = bundles[0].1, arabic = bundles[1].1
        for key in keys {
            #expect(arabic.localizedString(forKey: key, value: nil, table: nil)
                != english.localizedString(forKey: key, value: nil, table: nil),
                    "\(key) carries the English value in Arabic")
        }
        // The message is the one string on this screen that must not be trimmed to a caption.
        #expect(english.localizedString(forKey: "import_caution_message", value: nil, table: nil)
            .count > 120)
    }

    // MARK: - Ruling F9: revoke

    /// F9's whole point. `revoke()` forgets the token this device holds and NOTHING else — never
    /// `disconnect()`, which revokes every scope the user ever granted (sign-in included) and signs
    /// them out of the app. The seam offers no such call, and the two files that could reach the
    /// SDK name it nowhere.
    @Test func revokeForgetsTheTokenResetsTheScreenAndNeverDisconnects() async throws {
        let rig = try rig(youtube: fullLibrary())
        _ = try await reviewed(rig)
        #expect(rig.authorizer.heldToken == Fixture.token)

        rig.model.revoke()
        #expect(rig.authorizer.forgetCount == 1)
        #expect(rig.authorizer.heldToken == nil)
        #expect(rig.model.state == .idle)
        #expect(rig.model.didRevoke)
        #expect(rig.model.isCautionPresented == false)

        // The absence, asserted where it can actually be broken: no file in the import feature —
        // the authorizer, the ViewModel or the screen — may name `disconnect`.
        let feature = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "FitrahTube/Features/Import")
        let sources = try FileManager.default
            .contentsOfDirectory(at: feature, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "swift" }
        #expect(sources.count >= 7)
        for url in sources {
            // COMMENTS are stripped first — three of these files name `disconnect()` precisely to
            // record that it is never called, and an assertion those comments break would be
            // deleted by the next person rather than the call being kept out.
            let code = try String(contentsOf: url, encoding: .utf8)
                .split(separator: "\n", omittingEmptySubsequences: false)
                .map { $0.split(separator: "//", maxSplits: 1, omittingEmptySubsequences: false)[0] }
                .joined(separator: "\n")
            #expect(!code.contains("disconnect("), "\(url.lastPathComponent) calls disconnect()")
            #expect(!code.contains("signOut("), "\(url.lastPathComponent) signs the Google user out")
        }
    }

    /// The confirmation links to GOOGLE's account-permissions page — never YouTube (owner
    /// directive 2026-08-27, and ruling F9's reason for the link at all: revoking the grant is the
    /// user's own to do, and this is where it is done).
    @Test func theRevokeLinkIsGooglesPermissionsPageAndNoScreenLinksToYouTube() throws {
        #expect(ImportFromYouTubeScreen.permissionsURL
            == URL(string: "https://myaccount.google.com/permissions"))
        let host = try #require(ImportFromYouTubeScreen.permissionsURL.host())
        #expect(host == "myaccount.google.com")
        #expect(!host.contains("youtube"))

        let screen = URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "FitrahTube/Features/Import/ImportFromYouTubeScreen.swift")
        let text = try String(contentsOf: screen, encoding: .utf8)
        #expect(!text.contains("youtube.com"))
        // Acceptance: the gate cannot be bypassed, so the screen has no path to `confirmImport`.
        #expect(!text.contains("confirmImport"))
    }

    // MARK: - The done state (Task 28 re-review's ruling)

    /// `processed < total`, read off the summary alone. Review I1: the "this was partial" signal
    /// used to exist only in the transient DONE progress emission, so a screen that keeps the
    /// summary would tell a user whose run was cut off "N added" and nothing else.
    @Test func aRateLimitedRunIsPartialAndACompleteRunIsNot() async throws {
        let rig = try rig(youtube: fullLibrary(),
                          backend: [.json(429, "{\"retryAfterSeconds\":60}")])
        _ = try await reviewed(rig)
        rig.model.importTapped()
        rig.model.acceptCaution()
        await rig.model.job?.value

        guard case .done(let cut) = rig.model.state else {
            Issue.record("expected .done, got \(rig.model.state)"); return
        }
        #expect(cut.rateLimited)
        #expect(cut.processed == 0)
        #expect(cut.total == 3)
        #expect(cut.isPartial)

        let complete = ImportSummary(added: 3, sentForReview: 0, skipped: 0, alreadyPresent: 0,
                                     processed: 3, total: 3, rateLimited: false)
        #expect(complete.isPartial == false)
        // An all-duplicates run wrote nothing and was still COMPLETE: `total` is the FRESH count,
        // so zero of zero is not partial.
        #expect(ImportSummary(added: 0, sentForReview: 0, skipped: 4, alreadyPresent: 4,
                              processed: 0, total: 0, rateLimited: false).isPartial == false)
    }

    /// Every key the five arms render resolves in all three locales — including the four iOS-new
    /// ones authored for ruling F9 and the partial line.
    @Test func everyImportScreenKeyResolvesInEnglishArabicAndDutch() throws {
        let keys = ["import_youtube_title", "import_youtube_loading_authorizing",
                    "import_youtube_loading_fetching", "import_youtube_group_channels",
                    "import_youtube_group_playlists", "import_youtube_group_videos",
                    "import_youtube_group_channels_short", "import_youtube_group_playlists_short",
                    "import_youtube_group_videos_short", "import_youtube_partial_failure",
                    "import_youtube_select_all_content_description",
                    "import_youtube_importing_resolving", "import_youtube_importing_writing",
                    "import_youtube_importing_done", "import_youtube_done_summary",
                    "import_youtube_done_partial", "import_youtube_done_rate_limited",
                    "import_youtube_button_done", "import_youtube_button_retry",
                    "import_revoke_action", "import_revoke_done", "import_revoke_manage_link",
                    "empty_state_no_content", "auth_error_generic", "cancel"]
        let bundles = try ["en", "ar", "nl"].map { locale in
            (locale, try #require(Bundle.main.path(forResource: locale, ofType: "lproj")
                .flatMap(Bundle.init(path:))))
        }
        for (locale, bundle) in bundles {
            for key in keys {
                #expect(bundle.localizedString(forKey: key, value: nil, table: nil) != key,
                        "\(key) unresolved in \(locale)")
            }
        }
        // The Import button's label is a PLURAL (`values/strings.xml:422-425`), which only resolves
        // through `String(format:)` — a bare `localizedString(forKey:)` hands back the `%#@…@`
        // token, and the button would render that token verbatim. No digit assertion outside
        // English: Arabic's `two` form carries no numeral at all ("استيراد عنصرين"), so a
        // contains-"2" check would fail on correct copy.
        #expect(Format.localizedFormat("import_youtube_button_import",
                                       locale: Locale(identifier: "en"), Int64(5)) == "Import 5 items")
        for identifier in ["en", "ar", "nl"] {
            for count in [Int64(0), 1, 2, 5, 11] {
                let rendered = Format.localizedFormat("import_youtube_button_import",
                                                      locale: Locale(identifier: identifier), count)
                #expect(!rendered.contains("%"), "\(identifier)/\(count): \(rendered)")
                #expect(!rendered.isEmpty, "\(identifier)/\(count)")
            }
        }
        // Never "Download", in any locale, on any of these (Global Constraints).
        for (_, bundle) in bundles {
            for key in keys {
                let value = bundle.localizedString(forKey: key, value: nil, table: nil).lowercased()
                #expect(!value.contains("download"))
                #expect(!value.contains("تنزيل"))
                #expect(!value.contains("gedownload"))
            }
        }
    }
}
