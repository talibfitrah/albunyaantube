package com.albunyaan.tube.registry.repository;

import com.albunyaan.tube.registry.model.PlaylistRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PlaylistRegistryRepository
    extends JpaRepository<PlaylistRegistry, UUID>, JpaSpecificationExecutor<PlaylistRegistry> {

    Optional<PlaylistRegistry> findByYtPlaylistId(String ytPlaylistId);
}
