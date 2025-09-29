package com.albunyaan.tube.admin;

import com.albunyaan.tube.admin.dto.CreateModeratorRequest;
import com.albunyaan.tube.admin.dto.ModeratorResponse;
import com.albunyaan.tube.admin.dto.UpdateModeratorStatusRequest;
import com.albunyaan.tube.user.UserService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/admins/moderators", produces = MediaType.APPLICATION_JSON_VALUE)
public class ModeratorManagementController {

    private final UserService userService;

    public ModeratorManagementController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<ModeratorResponse>> listModerators() {
        var moderators = userService
            .listModerators()
            .stream()
            .map(ModeratorResponse::fromUser)
            .toList();
        return ResponseEntity.ok(moderators);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ModeratorResponse> createModerator(@Valid @RequestBody CreateModeratorRequest request) {
        var moderator = userService.createModerator(request.email(), request.password(), request.displayName());
        return ResponseEntity
            .created(URI.create("/api/v1/admins/moderators/" + moderator.getId()))
            .body(ModeratorResponse.fromUser(moderator));
    }

    @PatchMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ModeratorResponse> updateStatus(
        @PathVariable("id") UUID id,
        @Valid @RequestBody UpdateModeratorStatusRequest request
    ) {
        var updated = userService.updateModeratorStatus(id, request.status());
        return ResponseEntity.ok(ModeratorResponse.fromUser(updated));
    }
}
