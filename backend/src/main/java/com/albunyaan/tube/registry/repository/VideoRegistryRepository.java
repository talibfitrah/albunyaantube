package com.albunyaan.tube.registry.repository;

import com.albunyaan.tube.registry.model.VideoRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VideoRegistryRepository
    extends JpaRepository<VideoRegistry, UUID>, JpaSpecificationExecutor<VideoRegistry> {

    Optional<VideoRegistry> findByYtVideoId(String ytVideoId);
}
