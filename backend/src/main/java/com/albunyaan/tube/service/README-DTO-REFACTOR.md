# DTO Refactor Plan for YouTubeService

## Goal
Move controllers off raw NewPipe types (ChannelInfo, StreamInfo, etc.) onto stable DTOs, using gateway/orchestrators as the sole NewPipe boundary.

## Current State

### NewPipe-Returning Methods in YouTubeService
These methods return raw NewPipe types and should be deprecated:

```java
// Channel operations
ChannelInfo getChannelDetails(String channelId)
List<StreamInfoItem> getChannelVideos(String channelId, String pageToken)
List<StreamInfoItem> getChannelVideos(String channelId, String pageToken, String searchQuery)
List<PlaylistInfoItem> getChannelPlaylists(String channelId, String pageToken)

// Playlist operations
PlaylistInfo getPlaylistDetails(String playlistId)
List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken)
List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken, String searchQuery)

// Video operations
StreamInfo getVideoDetails(String videoId)

// Validation operations
ChannelInfo validateAndFetchChannel(String youtubeId)
PlaylistInfo validateAndFetchPlaylist(String youtubeId)
StreamInfo validateAndFetchVideo(String youtubeId)

// Batch operations
Map<String, ChannelInfo> batchValidateChannels(List<String> youtubeIds)
Map<String, PlaylistInfo> batchValidatePlaylists(List<String> youtubeIds)
Map<String, StreamInfo> batchValidateVideos(List<String> youtubeIds)
```

### Existing DTOs
Already implemented in `com.albunyaan.tube.dto`:
- `ChannelDetailsDto`
- `PlaylistDetailsDto`
- `StreamDetailsDto`
- `StreamItemDto`
- `PlaylistItemDto`

### Existing Mappers
`ChannelOrchestrator` has mapping methods:
- `mapToChannelDetailsDto(ChannelInfo)`
- `mapToPlaylistDetailsDto(PlaylistInfo)`
- `mapToStreamDetailsDto(StreamInfo)`
- `mapToStreamItemDto(StreamInfoItem)`
- `mapToPlaylistItemDto(PlaylistInfoItem)`

## Phased Implementation

### Phase 1: Add DTO-First Methods to ChannelOrchestrator

Add methods that return DTOs directly (combining fetch + map):

```java
// Channel DTO operations
public ChannelDetailsDto getChannelDetailsDto(String channelId) throws IOException {
    ChannelInfo channel = getChannelDetails(channelId);
    return mapToChannelDetailsDto(channel);
}

public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken) throws IOException {
    return getChannelVideos(channelId, pageToken).stream()
        .map(this::mapToStreamItemDto)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
}

public List<PlaylistItemDto> getChannelPlaylistsDto(String channelId, String pageToken) throws IOException {
    return getChannelPlaylists(channelId, pageToken).stream()
        .map(this::mapToPlaylistItemDto)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
}

// Playlist DTO operations
public PlaylistDetailsDto getPlaylistDetailsDto(String playlistId) throws IOException {
    PlaylistInfo playlist = getPlaylistDetails(playlistId);
    return mapToPlaylistDetailsDto(playlist);
}

public List<StreamItemDto> getPlaylistVideosDto(String playlistId, String pageToken) throws IOException {
    return getPlaylistVideos(playlistId, pageToken).stream()
        .map(this::mapToStreamItemDto)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
}

// Video DTO operations
public StreamDetailsDto getVideoDetailsDto(String videoId) throws IOException {
    StreamInfo video = getVideoDetails(videoId);
    return mapToStreamDetailsDto(video);
}

// Validation DTO operations (return null on failure instead of NewPipe type)
public ChannelDetailsDto validateAndFetchChannelDto(String youtubeId) {
    ChannelInfo info = validateAndFetchChannel(youtubeId);
    return info != null ? mapToChannelDetailsDto(info) : null;
}

public PlaylistDetailsDto validateAndFetchPlaylistDto(String youtubeId) {
    PlaylistInfo info = validateAndFetchPlaylist(youtubeId);
    return info != null ? mapToPlaylistDetailsDto(info) : null;
}

public StreamDetailsDto validateAndFetchVideoDto(String youtubeId) {
    StreamInfo info = validateAndFetchVideo(youtubeId);
    return info != null ? mapToStreamDetailsDto(info) : null;
}

// Batch DTO operations
public Map<String, ChannelDetailsDto> batchValidateChannelsDto(List<String> youtubeIds) {
    return batchValidateChannels(youtubeIds).entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> mapToChannelDetailsDto(e.getValue())
        ));
}

public Map<String, PlaylistDetailsDto> batchValidatePlaylistsDto(List<String> youtubeIds) {
    return batchValidatePlaylists(youtubeIds).entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> mapToPlaylistDetailsDto(e.getValue())
        ));
}

public Map<String, StreamDetailsDto> batchValidateVideosDto(List<String> youtubeIds) {
    return batchValidateVideos(youtubeIds).entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> mapToStreamDetailsDto(e.getValue())
        ));
}
```

### Phase 2: Add DTO Methods to YouTubeService

Update `YouTubeService` to expose DTO methods that delegate to orchestrators:

```java
// Channel DTO methods
public ChannelDetailsDto getChannelDetailsDto(String channelId) throws IOException {
    return channelOrchestrator.getChannelDetailsDto(channelId);
}

public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken) throws IOException {
    return channelOrchestrator.getChannelVideosDto(channelId, pageToken);
}

// ... similar for all operations
```

### Phase 3: Migrate Service Callers ✅ (Completed)

All production callers that previously referenced NewPipe types now exclusively consume DTOs:

1. **VideoValidationService** – uses `batchValidateVideosDto(...)` for validation runs (presence/absence semantics preserved).
2. **SimpleImportService** – maps `ChannelDetailsDto`, `PlaylistDetailsDto`, and `StreamDetailsDto` onto Firestore models.
3. **YouTubeSearchController** – serves DTOs directly from `YouTubeService` without manual mapping.

Guardrail status: Imports of `org.schabi.newpipe.*` must remain confined to `YouTubeGateway`, `ChannelOrchestrator`, or clearly documented legacy seams. The following production files are **known exceptions** that still rely on NewPipe internals and therefore need follow-up refactors:

1. `backend/src/main/java/com/albunyaan/tube/service/YouTubeService.java` (lines 10-14) – exposes legacy validation/search helpers that still surface `org.schabi.newpipe.extractor.channel.ChannelInfo`, `org.schabi.newpipe.extractor.playlist.PlaylistInfo`, `org.schabi.newpipe.extractor.playlist.PlaylistInfoItem`, `org.schabi.newpipe.extractor.stream.StreamInfo`, and `org.schabi.newpipe.extractor.stream.StreamInfoItem` for downstream callers.
2. `backend/src/main/java/com/albunyaan/tube/dto/EnrichedSearchResult.java` (lines 4-7) – DTO factory helpers use `org.schabi.newpipe.extractor.Image`, `org.schabi.newpipe.extractor.channel.ChannelInfoItem`, `org.schabi.newpipe.extractor.playlist.PlaylistInfoItem`, and `org.schabi.newpipe.extractor.stream.StreamInfoItem` directly when constructing enriched search payloads.
3. `backend/src/main/java/com/albunyaan/tube/config/NewPipeConfiguration.java` (lines 3-12) – bootstraps the shared NewPipe extractor beans, so it necessarily imports extractor primitives.
4. `backend/src/main/java/com/albunyaan/tube/service/SearchOrchestrator.java` (lines 5-12) – still manipulates `InfoItem`/`SearchExtractor` objects while translating them into DTOs.

**Next steps (owner: Search Platform team):**
- Phase 4 will refactor `YouTubeService` and `SearchOrchestrator` so their public interfaces expose DTOs exclusively while isolating NewPipe dependencies inside gateway classes.
- `EnrichedSearchResult` factories will be moved behind a mapper in `ChannelOrchestrator`/`YouTubeGateway`, or rewritten to consume DTOs from those layers.
- `NewPipeConfiguration` remains an allowed exception because it centralizes extractor initialization; no action needed beyond documenting the allowance.

### Phase 4: Deprecate NewPipe-Returning Methods

Mark legacy methods as deprecated in both `ChannelOrchestrator` and `YouTubeService`:

```java
/**
 * @deprecated Use {@link #getChannelDetailsDto(String)} instead
 */
@Deprecated
public ChannelInfo getChannelDetails(String channelId) throws IOException { ... }
```

### Phase 5: Cleanup

1. Remove deprecated methods after confirming no callers
2. Consider moving mapping logic to a dedicated `DtoMapper` class
3. Formalize/static-check the guardrail so only `YouTubeGateway`, `ChannelOrchestrator`, and service-layer tests reference NewPipe types

## Controller Status

Controllers currently do NOT directly use NewPipe types:
- `YouTubeSearchController` - Uses `YouTubeService` (needs review)

This is good! The main work is in service-to-service boundaries.

## Files to Modify

1. `ChannelOrchestrator.java` - Add DTO methods (Phase 1)
2. `YouTubeService.java` - Add DTO methods, deprecate old (Phase 2, 4)
3. `VideoValidationService.java` - Migrate to DTOs (Phase 3)
4. `SimpleImportService.java` - Migrate to DTOs (Phase 3)
5. Tests - Update to use DTO methods

## Benefits

- **Type Safety**: Controllers work with stable DTOs, not mutable NewPipe types
- **Decoupling**: NewPipe upgrades don't ripple to controllers
- **Testability**: Easier to mock DTO responses in tests
- **API Stability**: HTTP responses are serialized DTOs, not NewPipe objects

## Timeline

- Phase 1-2: Immediate (low risk, additive)
- Phase 3: After DTO methods verified working
- Phase 4: After Phase 3 complete, with deprecation warnings
- Phase 5: Next major release, after deprecation period

## Sign-off Criteria

- [ ] DTO methods added to ChannelOrchestrator
- [ ] DTO methods added to YouTubeService
- [ ] At least one service migrated to DTO methods with tests
- [ ] Legacy methods marked @Deprecated
- [ ] No NewPipe types in controller packages
