package com.albunyaan.tube.admin;

import com.albunyaan.tube.admin.dto.ChannelExclusionUpdateRequest;
import com.albunyaan.tube.admin.dto.ChannelUpsertRequest;
import com.albunyaan.tube.admin.dto.PlaylistExclusionUpdateRequest;
import com.albunyaan.tube.admin.dto.PlaylistUpsertRequest;
import com.albunyaan.tube.admin.dto.VideoUpsertRequest;
import com.albunyaan.tube.registry.RegistryAdminService;
import com.albunyaan.tube.user.User;
import jakarta.validation.Valid;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/admins/registry", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class RegistryAdminCommandController {

    private final RegistryAdminService registryAdminService;

    public RegistryAdminCommandController(RegistryAdminService registryAdminService) {
        this.registryAdminService = registryAdminService;
    }

    @PostMapping(path = "/channels", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> upsertChannel(
        @AuthenticationPrincipal User actor,
        @Valid @RequestBody ChannelUpsertRequest request
    ) {
        registryAdminService.registerOrUpdateChannel(actor, request.ytId(), toSlugSet(request.categorySlugs()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/playlists", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> upsertPlaylist(
        @AuthenticationPrincipal User actor,
        @Valid @RequestBody PlaylistUpsertRequest request
    ) {
        registryAdminService.registerOrUpdatePlaylist(
            actor,
            request.ytId(),
            request.channelYtId(),
            toSlugSet(request.categorySlugs())
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/videos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> upsertVideo(
        @AuthenticationPrincipal User actor,
        @Valid @RequestBody VideoUpsertRequest request
    ) {
        registryAdminService.registerOrUpdateVideo(
            actor,
            request.ytId(),
            request.channelYtId(),
            request.playlistYtId(),
            toSlugSet(request.categorySlugs())
        );
        return ResponseEntity.noContent().build();
    }

    @PatchMapping(path = "/channels/{id}/exclusions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> updateChannelExclusions(
        @AuthenticationPrincipal User actor,
        @PathVariable("id") UUID id,
        @Valid @RequestBody ChannelExclusionUpdateRequest request
    ) {
        registryAdminService.updateChannelExclusions(
            actor,
            id,
            toSlugSet(request.excludedPlaylistIds()),
            toSlugSet(request.excludedVideoIds())
        );
        return ResponseEntity.noContent().build();
    }

    @PatchMapping(path = "/playlists/{id}/exclusions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> updatePlaylistExclusions(
        @AuthenticationPrincipal User actor,
        @PathVariable("id") UUID id,
        @Valid @RequestBody PlaylistExclusionUpdateRequest request
    ) {
        registryAdminService.updatePlaylistExclusions(actor, id, toSlugSet(request.excludedVideoIds()));
        return ResponseEntity.noContent().build();
    }

    private Set<String> toSlugSet(List<String> source) {
        if (source == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(source);
    }
}
