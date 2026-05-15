package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.sync.FavoriteSyncDto;
import com.albunyaan.tube.dto.sync.PlaylistSyncDto;
import com.albunyaan.tube.dto.sync.PutFavoriteRequest;
import com.albunyaan.tube.dto.sync.PutPlaylistRequest;
import com.albunyaan.tube.dto.sync.PutSubscriptionRequest;
import com.albunyaan.tube.dto.sync.SubscriptionSyncDto;
import com.albunyaan.tube.dto.sync.SyncCursors;
import com.albunyaan.tube.dto.sync.SyncResponseDto;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.sync.SyncService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/account")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    // ── Pull ────────────────────────────────────────────────────────────────

    @GetMapping("/sync")
    public ResponseEntity<SyncResponseDto> getSync(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @RequestParam(name = "subs", required = false, defaultValue = "0") long subs,
            @RequestParam(name = "playlists", required = false, defaultValue = "0") long playlists,
            @RequestParam(name = "favorites", required = false, defaultValue = "0") long favorites)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        SyncCursors cursors = new SyncCursors(subs, playlists, favorites);
        return ResponseEntity.ok(sync.pull(principal.getUid(), cursors));
    }

    // ── Subscriptions ───────────────────────────────────────────────────────

    @PutMapping("/subscriptions/{id}")
    public ResponseEntity<SubscriptionSyncDto> putSubscription(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id,
            @Valid @RequestBody PutSubscriptionRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(sync.upsertSubscription(principal.getUid(), id, req));
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<SubscriptionSyncDto> deleteSubscription(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(sync.tombstoneSubscription(principal.getUid(), id));
    }

    // ── Playlists ───────────────────────────────────────────────────────────

    @PutMapping("/playlists/{id}")
    public ResponseEntity<PlaylistSyncDto> putPlaylist(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id,
            @Valid @RequestBody PutPlaylistRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(sync.upsertPlaylist(principal.getUid(), id, req));
    }

    @DeleteMapping("/playlists/{id}")
    public ResponseEntity<PlaylistSyncDto> deletePlaylist(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(sync.tombstonePlaylist(principal.getUid(), id));
    }

    // ── Favorites ───────────────────────────────────────────────────────────

    @PutMapping("/favorites/{id}")
    public ResponseEntity<FavoriteSyncDto> putFavorite(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id,
            @Valid @RequestBody PutFavoriteRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(sync.upsertFavorite(principal.getUid(), id, req));
    }

    @DeleteMapping("/favorites/{id}")
    public ResponseEntity<FavoriteSyncDto> deleteFavorite(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @PathVariable String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(sync.tombstoneFavorite(principal.getUid(), id));
    }
}
