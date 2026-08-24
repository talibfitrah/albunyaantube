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
