package com.albunyaan.tube.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IndexController {

    /**
     * Retired (ruling R18, 2026-10-17): client-posted streams were never checked against their channel or
     * playlist, so anyone could put any video into search under an approved source. The route stays and
     * answers 202 so installed apps, which fire and forget, see no change; the body is not read.
     */
    @PostMapping("/index/streams")
    public ResponseEntity<Void> indexStreams() {
        return ResponseEntity.accepted().build();
    }
}
