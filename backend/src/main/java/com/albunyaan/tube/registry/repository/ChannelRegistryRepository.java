package com.albunyaan.tube.registry.repository;

import com.albunyaan.tube.registry.model.ChannelRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ChannelRegistryRepository
    extends JpaRepository<ChannelRegistry, UUID>, JpaSpecificationExecutor<ChannelRegistry> {

    Optional<ChannelRegistry> findByYtChannelId(String ytChannelId);
}
