package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.dto.CategoryDto;
import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.HomeCategoryDto;
import com.albunyaan.tube.dto.PublicDetails;
import com.albunyaan.tube.search.SearchIndex;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.PublicContentService;
import com.albunyaan.tube.service.VisibilityPolicy;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Public API controller for Android app content browsing.
 * No authentication required - serves only approved/included content.
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "${app.security.cors.allowed-origins}")
public class PublicContentController {

    private static final Logger log = LoggerFactory.getLogger(PublicContentController.class);

    private final PublicContentService contentService;
    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;

    public PublicContentController(PublicContentService contentService, FirebaseAuth firebaseAuth,
                                   UserRepository userRepository) {
        this.contentService = contentService;
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
    }

    /**
     * The caller's uid from a valid Firebase Bearer, else null. FirebaseAuthFilter skips /api/v1/*, so the two
     * routes that serve a PERSONAL item to its grantees read the token here. A missing, malformed, expired or
     * forged token is simply anonymous: never a 401, so it cannot tell the caller anything a missing id would not.
     * ponytail: a Firebase signing-key outage here also reads as anonymous, so a grantee then gets the 404 and lists
     * the item without its exclusions until Firebase is back (every signed-in route is down then anyway). Making it a
     * 503 would break PUBLIC reads for every signed-in user instead.
     */
    private String viewerUid(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        try {
            return firebaseAuth.verifyIdToken(authorization.substring("Bearer ".length()), false).getUid();
        } catch (FirebaseAuthException | IllegalArgumentException e) { // a bad, expired or empty token: anonymous
            return null;
        } catch (RuntimeException e) { // a broken Firebase setup: still anonymous, but every grantee would lose exclusions
            log.warn("Bearer on a public detail route could not be verified: {}", e.toString());
            return null;
        }
    }

    /** A token Firebase refused, as against Firebase failing to answer. */
    private static final java.util.Set<AuthErrorCode> TOKEN_REJECTIONS = java.util.EnumSet.of(
            AuthErrorCode.EXPIRED_ID_TOKEN, AuthErrorCode.INVALID_ID_TOKEN, AuthErrorCode.REVOKED_ID_TOKEN,
            AuthErrorCode.USER_DISABLED, AuthErrorCode.USER_NOT_FOUND);

    /**
     * For a PERSONAL item (so the caller is a grantee): the checks /api/account/sync runs, where grantees get their
     * PERSONAL items. Tokens revoked or account disabled (Firebase), or blocked or deleted in Firestore (the status
     * source of truth FirebaseAuthFilter reads) -> the missing-id 404. A check that cannot run -> 503, as the filter
     * answers, so the app retries rather than listing the item without its exclusions. Only a grantee gets here, so
     * neither round trip nor the 503 is an oracle or a cost to anyone else.
     */
    private ResponseEntity<?> serveToActiveGrantee(String visibility, String authorization, String type, String id,
                                                    Object body) {
        if (!VisibilityPolicy.isPublic(visibility)) {
            boolean refused;
            try {
                String uid = firebaseAuth.verifyIdToken(authorization.substring("Bearer ".length()), true).getUid();
                // No Firestore doc yet (first sign-in) passes, as in FirebaseAuthFilter.
                refused = userRepository.findByUidUncached(uid).map(u -> u.isBlocked() || u.isDeleted()).orElse(false);
            } catch (FirebaseAuthException e) {
                if (!TOKEN_REJECTIONS.contains(e.getAuthErrorCode())) return granteeCheckUnavailable(e);
                refused = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return granteeCheckUnavailable(e);
            } catch (ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
                return granteeCheckUnavailable(e); // RuntimeException: e.g. a user doc that will not map
            }
            if (refused) throw new ResourceNotFoundException(type, id);
        }
        // no-store: the apps read the exclusions here on every open, so no cache may hold one back, and a grantee's
        // answer must never be served to anyone else.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** FirebaseAuthFilter's own 503 for a status check that failed. */
    private static ResponseEntity<?> granteeCheckUnavailable(Exception cause) {
        log.warn("Account status check failed for a PERSONAL grantee: {}", cause.toString());
        return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("code", "SERVICE_UNAVAILABLE", "message", "Account status check failed; try again."));
    }

    /**
     * Get home feed with paginated category sections.
     * Each category section contains content items in admin-defined sort order.
     *
     * @param cursor Pagination cursor from previous page
     * @param categoryLimit Categories per page (default 5, max 10)
     * @param contentLimit Items per category (default 10, max 20)
     * @return Paginated home feed with category sections
     */
    @GetMapping("/home")
    public ResponseEntity<?> getHomeFeed(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "5") int categoryLimit,
            @RequestParam(required = false, defaultValue = "10") int contentLimit,
            @RequestParam(required = false) String category
    ) {
        int validCategoryLimit = Math.min(Math.max(categoryLimit, 1), 10);
        int validContentLimit = Math.min(Math.max(contentLimit, 1), 20);
        // Normalize: trim whitespace and treat empty/blank as null to avoid cache key pollution
        String normalizedCategory = (category != null && !category.isBlank()) ? category.trim() : null;

        try {
            CursorPageDto<HomeCategoryDto> feed = contentService.getHomeFeed(cursor, validCategoryLimit, validContentLimit, normalizedCategory);
            // cachePublic() is safe here because this endpoint is unauthenticated and returns
            // identical content for all users. If auth/personalisation is ever added, switch
            // to cachePrivate() to prevent shared proxy caches from leaking user-specific data.
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                    .body(feed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while fetching home feed", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Failed to load home feed"));
        } catch (Exception e) {
            log.error("Error fetching home feed (cursor={}, catLimit={}, contentLimit={})",
                    cursor, validCategoryLimit, validContentLimit, e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", "Failed to load home feed"));
        }
    }

    /**
     * Fetch paginated content for the Android app.
     *
     * @param type Content type: HOME (mixed), CHANNELS, PLAYLISTS, VIDEOS
     * @param cursor Pagination cursor from previous page
     * @param limit Page size (default 20, max 50)
     * @param category Filter by category slug
     * @param length Filter by video length: SHORT, MEDIUM, LONG
     * @param date Filter by published date: LAST_24_HOURS, LAST_7_DAYS, LAST_30_DAYS
     * @param sort Sort order: DEFAULT, MOST_POPULAR, NEWEST
     * @return Paginated content with next cursor
     */
    @GetMapping("/content")
    public ResponseEntity<CursorPageDto<ContentItemDto>> getContent(
            @RequestParam(required = false, defaultValue = "HOME") String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String length,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException, IOException {
        // Validate and cap limit
        int validLimit = Math.min(Math.max(limit, 1), 50);
        String trimmedQ = (q != null) ? q.trim() : null;
        String normalizedQ = (trimmedQ != null && !trimmedQ.isEmpty())
                ? trimmedQ.substring(0, Math.min(trimmedQ.length(), 128)) : null;

        if (normalizedQ != null) {
            PublicContentService.SearchPage hit = contentService.searchContent(
                    type, cursor, validLimit, category, length, date, normalizedQ);
            if (hit != null) {
                return ResponseEntity.ok().header(SearchIndex.MATCH_HEADER, hit.match()).body(hit.page());
            }
        }

        CursorPageDto<ContentItemDto> page = contentService.getContent(
                type, cursor, validLimit, category, length, date, sort
        );

        return ResponseEntity.ok(page);
    }

    /**
     * Get categories for filtering.
     * BACKEND-PERF-01: Cached for 1 hour
     *
     * @return List of all active categories
     */
    @GetMapping("/categories")
    @Cacheable(value = CacheConfig.CACHE_CATEGORY_TREE, key = "'public-categories'")
    public ResponseEntity<List<CategoryDto>> getCategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return ResponseEntity.ok(contentService.getCategories());
    }

    /**
     * Get channel details by ID. A PERSONAL channel answers 200 only to an active grantee's Bearer; anyone else gets
     * the missing-id 404. The token is verified before the lookup whatever the id, so both 404s take the same path;
     * only a grantee goes on to the revocation check.
     *
     * @param channelId YouTube channel ID
     * @return Channel details with playlists
     */
    @GetMapping("/channels/{channelId}")
    public ResponseEntity<?> getChannelDetails(@PathVariable String channelId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel channel = contentService.getChannelDetails(channelId, viewerUid(authorization));
        return serveToActiveGrantee(channel.getVisibility(), authorization, "Channel", channelId, PublicDetails.of(channel));
    }

    /**
     * Get playlist details by ID. PERSONAL: as {@link #getChannelDetails}.
     *
     * @param playlistId YouTube playlist ID
     * @return Playlist details with videos
     */
    @GetMapping("/playlists/{playlistId}")
    public ResponseEntity<?> getPlaylistDetails(@PathVariable String playlistId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Playlist playlist = contentService.getPlaylistDetails(playlistId, viewerUid(authorization));
        return serveToActiveGrantee(playlist.getVisibility(), authorization, "Playlist", playlistId,
                PublicDetails.of(playlist));
    }

    /**
     * Get video details by ID.
     *
     * @param videoId YouTube video ID
     * @return Public video details, including the effective offlineAllowed
     */
    @GetMapping("/videos/{videoId}")
    public ResponseEntity<?> getVideoDetails(@PathVariable String videoId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return ResponseEntity.ok(PublicDetails.of(contentService.getVideoDetails(videoId)));
    }

    /**
     * Search across all content types.
     *
     * @param q Search query
     * @param type Filter by content type (optional)
     * @param limit Results limit (default 20)
     * @return Search results
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false, defaultValue = "20") int limit
    ) throws IOException {
        int validLimit = Math.min(Math.max(limit, 1), 50);
        SearchIndex.Hits hits = contentService.search(q, type, validLimit);
        return ResponseEntity.ok().header(SearchIndex.MATCH_HEADER, hits.match()).body(hits.items());
    }

    /** Task 24b: at most this many distinct ids per /titles request. */
    static final int MAX_TITLE_IDS = 50;
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /**
     * Original titles for the apps' channel and playlist rows (inner-search Task 24b, owner issue 1). The apps list
     * those pages on the device with hl=en, so YouTube machine-translates Arabic and Dutch titles; one call per page
     * swaps in the titles the server holds. No YouTube request; ids the server does not know are left out.
     */
    @GetMapping("/titles")
    public ResponseEntity<?> titles(@RequestParam List<String> ids) throws IOException {
        List<String> unique = List.copyOf(new LinkedHashSet<>(ids));
        if (unique.isEmpty() || unique.size() > MAX_TITLE_IDS || !unique.stream().allMatch(id -> VIDEO_ID.matcher(id).matches())) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids: 1 to " + MAX_TITLE_IDS + " YouTube video ids"));
        }
        SearchIndex.Titles titles = contentService.titles(unique);
        // Public and identical for every caller, as the home feed: shared caches may keep it for an hour, or a minute when
        // partial (a later ask may find more originals; Cloudflare must not pin translated titles for an hour).
        return ResponseEntity.ok().cacheControl(titles.complete() ? CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic()
                : CacheControl.maxAge(1, TimeUnit.MINUTES).cachePublic()).body(titles.map());
    }
}
