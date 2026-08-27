# Phase 2 carry-forwards from Plan A (InnerTubeKit) — for Plans B and C

InnerTubeKit shipped 2026-08-24 (final whole-branch review → SHIP; 86 tests + a live HLS resolve; commits 7440d186..af2cd2b5). Open items its consumers must handle:

## For Plan B (player)
- **CF-B1** `ExtractionError.cooldown(until: Date)` is now a terminal error. The player must map it to a "try again in X" state (via `InnerTube.cooldownRemaining()`), NOT a generic failure. Do not retry into a cooldown.
- **CF-B2** ManifestCache TTL now clamps to `resolved.expiresAt` (D3 closed the URL-expiry half of old T8-3). Remaining T13-2 option: make `InnerTube.init` async and read `await remoteConfig.current().manifestCacheSeconds` (persisted last-known-good is already loaded synchronously) instead of the bundled default — do this when the player wires the container so the cache honours a live config TTL.
- **CF-B3** The app must implement `AvailabilityGate` (InnerTubeKit protocol) backed by FitrahAPI HEAD `api/v1/videos/{id}` (404 fail-open / 410 unavailable) and pass it to the composition root. This is Plan B1's first task.
- **CF-B4** The consumer must call `RemoteConfigStore.refresh()` on launch / willEnterForeground (≥15 min spacing) or `current()` stays the bundled default forever.
- **CF-B5** `StreamResolver.resolve(purpose:)` exists but doesn't alter resolver behaviour — lane coordination (player vs prefetch) is caller-side via `ExtractionRateLimiter` (the consumer checks it before triggering a resolve). Player passes `.player`; prefetch passes `.prefetch`.
- **CF-B6** No `durationSeconds` on `Resolved` (parser has `videoDetails.lengthSeconds` but it wasn't threaded). If the player wants tighter cache TTL or a duration display from the resolve, add it then.

## For Plan C (channel/playlist detail, share/report/links)
- **CF-C1** `BrowseClient.channelTab(.shorts/.playlists)` returns an EMPTY page — the Shorts and Playlists `lockupViewModel` variants are unmodelled (see channel-detail.md addendum). Plan C must model them before those tabs render. `channelVideos`/`playlistItems` DO work.
- **CF-C2** `BrowseClient` bot-check trips do NOT escalate the shared `SessionStore` cooldown (only the resolver's player path does). If browse should share the cooldown, wire `recordBotCheck()` into BrowseClient's botCheck path in Plan C.
- **CF-C3** Degraded mode (bot-checked browse → approved playlists + `AtomFeedFetcher` + indexed search) is spec'd but not wired — BrowseClient throws `BrowseError.botCheck`; the consumer implements the fallback. `AtomFeedFetcher` is ready (its conditional-GET is dormant — YouTube's feed sends no validators, T12-2).

## From Plan B1 (player core) — final review 2026-08-27

B1 shipped 2026-08-27 (final whole-branch review, 04ab7fe8..efa1a7ac → SHIP AFTER FIXES; fix round committed as 667db523). Open items its successors must handle:

- **CF-B1-1** AVAudioSession.setCategory(.playback) — FIXED in B1 fix round (667db523): `PlayerHostView.makeUIViewController` sets `.playback`/`.moviePlayback` before assigning the player. B2 still owns `setActive`, `UIBackgroundModes`, interruption and route-change handling. → Plan B2
- **CF-B1-2** ExtractionRateLimiter never consulted (CF-B5 unimplemented): wire `check(videoId:kind:)` into `PlayerViewModel.resolve` — `.player` for open/retry, `.autoRecovery` for `handleRecoveryEvent` re-resolves — map `.blocked`/`.delayed` onto `.cooldown(until:)`. Until then manual Retry is an unthrottled `youtubei/v1/player` POST per tap. → Plan B2
- **CF-B1-3** Pre-emptive re-resolve on `willEnterForeground` past `resolvedAt+expires−margin` (plan §6.2 step 5) not implemented; needs B2's scene-phase plumbing. → Plan B2
- **CF-B1-4** CaptionsProvider `timedtext` fetch User-Agent — FIXED in B1 fix round (667db523): `Resolved.userAgent` is threaded through `CaptionOverlay` to `cues(for:userAgent:)`. B3 still owns keeping the caption fetch on the SAME client context as the embed rung it introduces. → Plan B3
- **CF-B1-5** `QualityOption.label` "Auto"/"Data Saver" — FIXED in B1 fix round (667db523): both are catalog strings (`player_quality_auto`, `player_quality_data_saver`). B4's Shorts kebab reuses this type and inherits them; the resolution labels stay literal by design. → Plan B4
- **CF-B1-6** `PlayerArgs` has 9 of spec §120's 12 fields; B5 adds `playlistId`/`startIndex`/`shuffled`/`targetVideoId` with the PlaylistDetail play/shuffle caller. → Plan B5
- **CF-B1-7** `.embed`/`.openInYouTube` outcomes render `.error(player_error_generic)`; B3 replaces `PlayerViewModel.map(_:)` branch (`PlayerViewModel.swift:176-180`) with the embed rung + Open-in-YouTube confirmation sheet. → Plan B3
- **CF-B1-8** `.recoveryExhausted` and every non-playable state dismantle `PlayerHostView` so session-only resume position is lost on manual Retry; if B5 makes that visible, hoist `currentTime` into the VM. → Plan B5
- **CF-B1-9** Report button shows a "coming soon" banner (`PlayerToolbar.swift`); Plan C wires the real VIDEO report flow (parent PLAYLIST/CHANNEL + subtype). → Plan C
- **CF-B1-10** Metadata "Show more" always renders (Task-8 M2 dropped); add a `ViewThatFits` truncation probe. → Plan C or any plan touching `PlayerMetadataView`
- **CF-B1-11** CF-B2 still open: `InnerTube.init` is sync and pins `ManifestCache` to `RemoteConfig.bundledDefault.manifestCacheSeconds` (`InnerTube.swift:41-43`); make init async or give `ManifestCache` a live TTL source. → Plan B2 (InnerTubeKit)
- **CF-B1-12** `ios-remote-config.json` still not published at repo root; `refresh()` 404s; bundled `resolverOrder` is permanent — publish before the Phase 2 gate. → Plan C / release prep
- **CF-B1-13** Test debt: `FitrahTubeApp.refreshRemoteConfigIfDue` (CF-B4's only enforcement) untested — extract `refreshRemoteConfigIfDue(now:last:spacing:)` as a free function. The AudioLanguage half is FIXED in B1 fix round (667db523): `AudioLanguageTests` now pins default-at-nonzero-index (T5-2). → Plan B2

### Minor, unowned

- **M2** description `.natural` alignment.
- **M3** overlay buttons possibly <44 pt (measure, add `minWidth`/`minHeight` 44).
- **M4** `PlayerViewModel` stores `catalog`/`favorites`/`settings` unused (B2 reads settings — delete if it still doesn't).
- **M7** a throwing mid-play re-resolve collapses to full-screen `.error` and loses position.
