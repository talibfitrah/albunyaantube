package com.albunyaan.tube.util;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Fixes approval status for all existing seeded content.
 *
 * Run with: ./gradlew bootRun --args='--spring.profiles.active=fix-approval'
 */
@Component
@Profile("fix-approval")
public class ApprovalStatusFixer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ApprovalStatusFixer.class);

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;

    public ApprovalStatusFixer(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("🔧 Starting Approval Status Fix...");

        int channelsFixed = fixChannels();
        log.info("✅ Fixed {} channels", channelsFixed);

        int playlistsFixed = fixPlaylists();
        log.info("✅ Fixed {} playlists", playlistsFixed);

        int videosFixed = fixVideos();
        log.info("✅ Fixed {} videos", videosFixed);

        log.info("🎉 Approval Status Fix Complete!");
        log.info("📊 Total: {} channels, {} playlists, {} videos",
                channelsFixed, playlistsFixed, videosFixed);
    }

    private int fixChannels() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Channel> channels = channelRepository.findAll();
        int count = 0;

        log.info("Found {} total channels", channels.size());

        for (Channel channel : channels) {
            boolean updated = false;

            // Fix status
            if (!"APPROVED".equals(channel.getStatus())) {
                log.debug("Fixing channel {} status: {} -> APPROVED",
                        channel.getId(), channel.getStatus());
                channel.setStatus("APPROVED");
                updated = true;
            }

            // Fix approved flag
            if (!Boolean.TRUE.equals(channel.getApproved())) {
                log.debug("Setting channel {} approved=true", channel.getId());
                channel.setApproved(true);
                updated = true;
            }

            // Fix pending flag
            if (Boolean.TRUE.equals(channel.getPending())) {
                log.debug("Setting channel {} pending=false", channel.getId());
                channel.setPending(false);
                updated = true;
            }

            // Fix missing subscribers field (required for orderBy queries)
            if (channel.getSubscribers() == null) {
                log.debug("Setting channel {} subscribers=0 (unknown)", channel.getId());
                channel.setSubscribers(0L);
                updated = true;
            }

            // Fix missing videoCount field
            if (channel.getVideoCount() == null) {
                log.debug("Setting channel {} videoCount=0 (unknown)", channel.getId());
                channel.setVideoCount(0);
                updated = true;
            }

            if (updated) {
                channelRepository.save(channel);
                count++;
                log.info("✓ Fixed channel: {} - {}", channel.getId(), channel.getName());
            } else {
                log.debug("Channel {} already correct", channel.getId());
            }
        }

        return count;
    }

    private int fixPlaylists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Playlist> playlists = playlistRepository.findAll();
        int count = 0;

        log.info("Found {} total playlists", playlists.size());

        for (Playlist playlist : playlists) {
            boolean updated = false;

            // Fix status
            if (!"APPROVED".equals(playlist.getStatus())) {
                log.debug("Fixing playlist {} status: {} -> APPROVED",
                        playlist.getId(), playlist.getStatus());
                playlist.setStatus("APPROVED");
                updated = true;
            }

            // Fix missing itemCount field
            if (playlist.getItemCount() == null) {
                log.debug("Setting playlist {} itemCount=0 (unknown)", playlist.getId());
                playlist.setItemCount(0);
                updated = true;
            }

            if (updated) {
                playlistRepository.save(playlist);
                count++;
                log.info("✓ Fixed playlist: {} - {}", playlist.getId(), playlist.getTitle());
            } else {
                log.debug("Playlist {} already correct", playlist.getId());
            }
        }

        return count;
    }

    private int fixVideos() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Video> videos = videoRepository.findAll();
        int count = 0;

        log.info("Found {} total videos", videos.size());

        for (Video video : videos) {
            boolean updated = false;

            // Fix status
            if (!"APPROVED".equals(video.getStatus())) {
                log.debug("Fixing video {} status: {} -> APPROVED",
                        video.getId(), video.getStatus());
                video.setStatus("APPROVED");
                updated = true;
            }

            if (updated) {
                videoRepository.save(video);
                count++;

                if (count % 50 == 0) {
                    log.info("Fixed {} videos so far...", count);
                }
            }
        }

        return count;
    }
}

