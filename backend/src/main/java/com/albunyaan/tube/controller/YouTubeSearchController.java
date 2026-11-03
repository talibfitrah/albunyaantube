package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.YouTubeSearchResponse;
import com.albunyaan.tube.service.YouTubeSearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/youtube")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
@Validated
public class YouTubeSearchController {

    private final YouTubeSearchService youtubeSearchService;

    public YouTubeSearchController(YouTubeSearchService youtubeSearchService) {
        this.youtubeSearchService = youtubeSearchService;
    }

    @GetMapping("/search")
    public YouTubeSearchResponse search(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(required = false, defaultValue = "ALL") YouTubeContentType type,
            @RequestParam(required = false) @Size(max = 2048) String pageToken) {
        return youtubeSearchService.search(q, type, pageToken);
    }
}
