package com.albunyaan.tube.moderation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class ModerationMetricsBinder implements MeterBinder {

    private final ModerationProposalRepository moderationProposalRepository;

    public ModerationMetricsBinder(ModerationProposalRepository moderationProposalRepository) {
        this.moderationProposalRepository = moderationProposalRepository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge(
            "moderation.pending.count",
            moderationProposalRepository,
            repository -> repository.countByStatus(ModerationProposalStatus.PENDING)
        );
    }
}
