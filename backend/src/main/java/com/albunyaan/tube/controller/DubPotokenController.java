package com.albunyaan.tube.controller;

import com.albunyaan.tube.service.DubPotokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hands the Android app a videoId-bound GVS poToken so web-client dub audio streams past the 1&nbsp;MB
 * "sps=2" cap that an on-device WebView token can't beat (proven on-device). Minting (via the BotGuard
 * sidecar) and ~6&nbsp;h caching live in {@link DubPotokenService}; this is a thin, public, read-only
 * endpoint matching the {@code /api/v1/*} public surface.
 *
 * <pre>GET /api/v1/dub-potoken?videoId=DW-00ckCAPI  ->  200 {"poToken":"..."}</pre>
 * Returns 404 (no body) when minting fails so the app degrades to the VR original audio.
 */
@RestController
public class DubPotokenController {

    private final DubPotokenService dubPotokenService;

    public DubPotokenController(DubPotokenService dubPotokenService) {
        this.dubPotokenService = dubPotokenService;
    }

    @GetMapping("/api/v1/dub-potoken")
    public ResponseEntity<Map<String, String>> getPotoken(@RequestParam("videoId") String videoId) {
        String pot = dubPotokenService.getPotoken(videoId);
        if (pot == null || pot.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("poToken", pot));
    }
}
