package com.albunyaan.tube.controller;

import com.albunyaan.tube.service.LiveCategoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Spec §4.2: the live state behind the Content Sorting chips and the suspect banner. Read-only, ADMIN only. */
@RestController
@RequestMapping("/api/admin/live-channels")
public class LiveChannelsAdminController {

    private final LiveCategoryService liveCategories;

    public LiveChannelsAdminController(LiveCategoryService liveCategories) {
        this.liveCategories = liveCategories;
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> status() throws ExecutionException, InterruptedException, TimeoutException {
        return liveCategories.status();
    }
}
