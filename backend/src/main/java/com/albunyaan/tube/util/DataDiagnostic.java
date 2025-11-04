package com.albunyaan.tube.util;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Diagnostic tool to see what's actually in Firestore
 *
 * Run with: ./gradlew bootRun --args='--spring.profiles.active=diagnostic'
 */
@Component
@Profile("diagnostic")
public class DataDiagnostic implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataDiagnostic.class);

    private final ChannelRepository channelRepository;
    private final VideoRepository videoRepository;

    public DataDiagnostic(
            ChannelRepository channelRepository,
            VideoRepository videoRepository
    ) {
        this.channelRepository = channelRepository;
        this.videoRepository = videoRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("🔍 Starting Firestore Data Diagnostic...");

        // Check all channels
        List<Channel> allChannels = channelRepository.findAll();
        log.info("\n📊 Total Channels in Firestore: {}", allChannels.size());

        for (Channel channel : allChannels) {
            log.info("  Channel: {} | Status: {} | Subscribers: {} | VideoCount: {} | Approved: {} | Pending: {}",
                    channel.getName(),
                    channel.getStatus(),
                    channel.getSubscribers(),
                    channel.getVideoCount(),
                    channel.getApproved(),
                    channel.getPending()
            );
        }

        // Try the same query the API uses
        log.info("\n📊 Channels with status=APPROVED (API query):");
        List<Channel> approvedChannels = channelRepository.findByStatus("APPROVED");
        log.info("  Found {} approved channels", approvedChannels.size());

        // Try orderBy query
        log.info("\n📊 Channels ordered by subscribers:");
        try {
            List<Channel> orderedChannels = channelRepository.findAllByOrderBySubscribersDesc();
            log.info("  Found {} channels ordered by subscribers", orderedChannels.size());
            for (Channel ch : orderedChannels) {
                log.info("    - {} (subscribers: {})", ch.getName(), ch.getSubscribers());
            }
        } catch (Exception e) {
            log.error("  ❌ Query failed: {}", e.getMessage());
        }

        // Check videos
        List<Video> allVideos = videoRepository.findAll();
        log.info("\n📊 Total Videos in Firestore: {}", allVideos.size());

        int approvedCount = 0;
        for (Video video : allVideos) {
            if ("APPROVED".equals(video.getStatus())) {
                approvedCount++;
            }
        }
        log.info("  Videos with APPROVED status: {}", approvedCount);

        log.info("\n✅ Diagnostic Complete!");
    }
}
