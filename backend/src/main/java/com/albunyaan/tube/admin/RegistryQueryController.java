package com.albunyaan.tube.admin;

import com.albunyaan.tube.registry.RegistryQueryService;
import com.albunyaan.tube.registry.dto.ChannelSummaryDto;
import com.albunyaan.tube.registry.dto.CursorPage;
import com.albunyaan.tube.registry.dto.PlaylistSummaryDto;
import com.albunyaan.tube.registry.dto.VideoSummaryDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/admins/registry", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class RegistryQueryController {

    private final RegistryQueryService registryQueryService;

    public RegistryQueryController(RegistryQueryService registryQueryService) {
        this.registryQueryService = registryQueryService;
    }

    @GetMapping(path = "/channels")
    public ResponseEntity<CursorPage<ChannelSummaryDto>> listChannels(
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(name = "categoryId", required = false) String categoryId
    ) {
        var page = registryQueryService.listChannels(cursor, limit, categoryId);
        return ResponseEntity.ok(page);
    }

    @GetMapping(path = "/playlists")
    public ResponseEntity<CursorPage<PlaylistSummaryDto>> listPlaylists(
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(name = "categoryId", required = false) String categoryId
    ) {
        var page = registryQueryService.listPlaylists(cursor, limit, categoryId);
        return ResponseEntity.ok(page);
    }

    @GetMapping(path = "/videos")
    public ResponseEntity<CursorPage<VideoSummaryDto>> listVideos(
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(name = "categoryId", required = false) String categoryId,
        @RequestParam(name = "q", required = false) String query,
        @RequestParam(name = "length", required = false) String length,
        @RequestParam(name = "date", required = false) String date,
        @RequestParam(name = "sort", required = false) String sort
    ) {
        var page = registryQueryService.listVideos(cursor, limit, categoryId, query, length, date, sort);
        return ResponseEntity.ok(page);
    }
}

