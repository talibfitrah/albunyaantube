package com.albunyaan.tube.util;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Seeds Firestore with REAL YouTube data from youtube-data.json
 *
 * Run with: ./gradlew bootRun --args='--spring.profiles.active=real-seed'
 */
@Component
@Profile("real-seed")
public class RealYouTubeDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RealYouTubeDataSeeder.class);
    private static final String SEED_USER = "real-seed-script@albunyaan.tube";

    private final CategoryRepository categoryRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;

    // Category mappings for content organization
    private static final Map<String, String> TAG_TO_CATEGORY = Map.ofEntries(
            Map.entry("Global", "kids"),  // Default to kids category for all "Global" tagged content
            Map.entry("Quran", "quran"),
            Map.entry("Hadith", "hadith"),
            Map.entry("Nasheed", "nasheed"),
            Map.entry("Kids", "kids"),
            Map.entry("Arabic", "arabic"),
            Map.entry("Education", "kids")
    );

    public RealYouTubeDataSeeder(
            CategoryRepository categoryRepository,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 Starting Real YouTube Data Seeding...");

        // 1. Ensure categories exist
        ensureCategories();

        // 2. Parse YouTube data
        YouTubeDataParser.ParsedYouTubeData data = YouTubeDataParser.parseFromResource();

        // 3. Seed channels
        int channelsSeeded = seedChannels(data.channels);
        log.info("✅ Seeded {} channels", channelsSeeded);

        // 4. Seed playlists
        int playlistsSeeded = seedPlaylists(data.playlists);
        log.info("✅ Seeded {} playlists", playlistsSeeded);

        // 5. Seed videos
        int videosSeeded = seedVideos(data.videos);
        log.info("✅ Seeded {} videos", videosSeeded);

        log.info("🎉 Real YouTube Data Seeding Complete!");
        log.info("📊 Total: {} channels, {} playlists, {} videos",
                channelsSeeded, playlistsSeeded, videosSeeded);
    }

    private void ensureCategories() throws ExecutionException, InterruptedException {
        List<Category> existingCategories = categoryRepository.findAll();
        if (!existingCategories.isEmpty()) {
            log.info("✓ Categories already exist ({}), skipping", existingCategories.size());
            return;
        }

        log.info("Creating base categories...");

        // Create essential categories
        List<Category> categories = Arrays.asList(
                createCategory("quran", "Qur'an & Recitation", null, 1, "📖"),
                createCategory("hadith", "Hadith & Sunnah", null, 2, "📿"),
                createCategory("nasheed", "Nasheeds (Voice Only)", null, 3, "🎤"),
                createCategory("kids", "Kids Corner", null, 4, "🧒"),
                createCategory("arabic", "Arabic Language", null, 5, "🔤"),
                createCategory("education", "Islamic Education", null, 6, "📚"),
                createCategory("history", "Islamic History", null, 7, "📜"),
                createCategory("general", "General Content", null, 8, "🌟")
        );

        for (Category category : categories) {
            categoryRepository.save(category);
        }

        log.info("✅ Created {} base categories", categories.size());
    }

    private Category createCategory(String id, String name, String parentId, int displayOrder, String emoji) {
        Category category = new Category();
        category.setId(id);
        category.setName(emoji + " " + name); // Include emoji in name
        category.setParentCategoryId(parentId);
        category.setDisplayOrder(displayOrder);
        category.setTopLevel(parentId == null);
        category.setCreatedBy(SEED_USER);
        category.setUpdatedBy(SEED_USER);
        category.setCreatedAt(Timestamp.now());
        category.setUpdatedAt(Timestamp.now());
        return category;
    }

    private int seedChannels(List<YouTubeDataParser.YouTubeChannel> channels) throws ExecutionException, InterruptedException {
        int count = 0;
        for (YouTubeDataParser.YouTubeChannel ytChannel : channels) {
            String channelId = "channel_" + ytChannel.youtubeId;

            // Check if already exists
            if (channelRepository.findById(channelId).isPresent()) {
                log.debug("Channel {} already exists, skipping", channelId);
                continue;
            }

            Channel channel = new Channel();
            channel.setId(channelId);
            channel.setYoutubeId(ytChannel.youtubeId);
            channel.setName(ytChannel.name);
            channel.setDescription("YouTube channel: " + ytChannel.name);

            // Assign category based on tag
            String categoryId = TAG_TO_CATEGORY.getOrDefault(ytChannel.tag, "kids");
            channel.setCategoryIds(Collections.singletonList(categoryId));

            // Set as approved
            channel.setStatus("APPROVED");
            channel.setApproved(true);
            channel.setPending(false);

            // Metadata
            channel.setSubmittedBy(SEED_USER);
            channel.setApprovedBy(SEED_USER);
            channel.setCreatedAt(Timestamp.now());
            channel.setUpdatedAt(Timestamp.now());

            // Thumbnail (YouTube standard)
            channel.setThumbnailUrl("https://yt3.ggpht.com/ytc/" + ytChannel.youtubeId);

            // CRITICAL: Set default values for fields used in queries
            channel.setSubscribers(100000L); // Default subscriber count
            channel.setVideoCount(50); // Default video count

            channelRepository.save(channel);
            count++;

            log.debug("Seeded channel: {} - {}", channelId, ytChannel.name);
        }
        return count;
    }

    private int seedPlaylists(List<YouTubeDataParser.YouTubePlaylist> playlists) throws ExecutionException, InterruptedException {
        int count = 0;
        for (YouTubeDataParser.YouTubePlaylist ytPlaylist : playlists) {
            String playlistId = "playlist_" + ytPlaylist.playlistId;

            // Check if already exists
            if (playlistRepository.findById(playlistId).isPresent()) {
                log.debug("Playlist {} already exists, skipping", playlistId);
                continue;
            }

            Playlist playlist = new Playlist();
            playlist.setId(playlistId);
            playlist.setYoutubeId(ytPlaylist.playlistId);
            playlist.setTitle(ytPlaylist.title);
            playlist.setDescription("YouTube playlist: " + ytPlaylist.title);

            // Assign category based on tag
            String categoryId = TAG_TO_CATEGORY.getOrDefault(ytPlaylist.tag, "kids");
            playlist.setCategoryIds(Collections.singletonList(categoryId));

            // Set as approved
            playlist.setStatus("APPROVED");

            // Metadata
            playlist.setSubmittedBy(SEED_USER);
            playlist.setApprovedBy(SEED_USER);
            playlist.setCreatedAt(Timestamp.now());
            playlist.setUpdatedAt(Timestamp.now());

            // Thumbnail (YouTube standard)
            playlist.setThumbnailUrl("https://i.ytimg.com/vi/" + ytPlaylist.playlistId + "/mqdefault.jpg");

            // Estimate item count (we don't have this data, so use a default)
            playlist.setItemCount(10);

            playlistRepository.save(playlist);
            count++;

            log.debug("Seeded playlist: {} - {}", playlistId, ytPlaylist.title);
        }
        return count;
    }

    private int seedVideos(List<YouTubeDataParser.YouTubeVideo> videos) throws ExecutionException, InterruptedException {
        int count = 0;
        int daysAgo = 0;
        Random random = new Random();

        for (YouTubeDataParser.YouTubeVideo ytVideo : videos) {
            String videoId = "video_" + ytVideo.videoId;

            // Check if already exists
            if (videoRepository.findById(videoId).isPresent()) {
                log.debug("Video {} already exists, skipping", videoId);
                continue;
            }

            Video video = new Video();
            video.setId(videoId);
            video.setYoutubeId(ytVideo.videoId);
            video.setTitle(ytVideo.title);
            video.setDescription(generateDescription(ytVideo.title));

            // Assign category based on tag
            String categoryId = TAG_TO_CATEGORY.getOrDefault(ytVideo.tag, "kids");
            video.setCategoryIds(Collections.singletonList(categoryId));

            // Set as approved
            video.setStatus("APPROVED");

            // Metadata
            video.setSubmittedBy(SEED_USER);
            video.setApprovedBy(SEED_USER);

            // Set upload date (distribute across last 365 days)
            Instant uploadInstant = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
            video.setUploadedAt(Timestamp.ofTimeSecondsAndNanos(uploadInstant.getEpochSecond(), 0));
            video.setCreatedAt(Timestamp.now());
            video.setUpdatedAt(Timestamp.now());

            // Thumbnail (YouTube standard)
            video.setThumbnailUrl("https://i.ytimg.com/vi/" + ytVideo.videoId + "/mqdefault.jpg");

            // Randomize some metadata
            video.setDurationSeconds(random.nextInt(1800) + 120); // 2-32 minutes
            video.setViewCount((long) (random.nextInt(100000) + 1000)); // 1K-100K views

            videoRepository.save(video);
            count++;

            // Rotate upload dates
            daysAgo = (daysAgo + 3) % 365;

            if (count % 50 == 0) {
                log.info("Seeded {} videos so far...", count);
            }

            log.debug("Seeded video: {} - {}", videoId, ytVideo.title);
        }
        return count;
    }

    private String generateDescription(String title) {
        // Generate a simple description from the title
        if (title.contains("قرآن") || title.contains("Quran") || title.contains("سورة")) {
            return "Quranic recitation and learning content. " + title;
        } else if (title.contains("نشيد") || title.contains("Nasheed")) {
            return "Islamic nasheed (vocal-only). " + title;
        } else if (title.contains("تعليم") || title.contains("Learn") || title.contains("Arabic")) {
            return "Educational content for learning. " + title;
        } else if (title.contains("طفال") || title.contains("Kids") || title.contains("Children")) {
            return "Islamic educational content for children. " + title;
        } else {
            return "Islamic educational content. " + title;
        }
    }
}

