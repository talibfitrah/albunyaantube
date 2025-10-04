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
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * BACKEND-005: Firestore Data Seeder
 *
 * Seeds Firestore with sample data for testing.
 * Only runs with --seed profile: ./gradlew bootRun --args='--spring.profiles.active=seed'
 */
@Component
@Profile("seed")
public class FirestoreDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FirestoreDataSeeder.class);

    private final CategoryRepository categoryRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;

    private String quranCategoryId;
    private String hadithCategoryId;
    private String lecturesCategoryId;

    public FirestoreDataSeeder(
            CategoryRepository categoryRepository,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository) {
        this.categoryRepository = categoryRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("🌱 Starting Firestore data seeding...");

        try {
            seedCategories();
            seedChannels();
            seedPlaylists();
            seedVideos();

            log.info("✅ Firestore data seeding completed successfully!");
        } catch (Exception e) {
            log.error("❌ Error seeding Firestore data", e);
            throw e;
        }
    }

    private void seedCategories() throws ExecutionException, InterruptedException {
        log.info("📂 Seeding categories...");

        // Create categories directly without querying (saves on index requirements)
        Category quran = new Category();
        quran.setName("Quran");
        quran.setSlug("quran");
        quran.setDisplayOrder(1);
        quran.setParentCategoryId(null);
        quran.setCreatedBy("system");
        quran.setUpdatedBy("system");
        Category savedQuran = categoryRepository.save(quran);
        quranCategoryId = savedQuran.getId();
        log.info("✅ Created category: Quran (ID: {})", quranCategoryId);

        Category hadith = new Category();
        hadith.setName("Hadith");
        hadith.setSlug("hadith");
        hadith.setDisplayOrder(2);
        hadith.setParentCategoryId(null);
        hadith.setCreatedBy("system");
        hadith.setUpdatedBy("system");
        Category savedHadith = categoryRepository.save(hadith);
        hadithCategoryId = savedHadith.getId();
        log.info("✅ Created category: Hadith (ID: {})", hadithCategoryId);

        Category lectures = new Category();
        lectures.setName("Lectures");
        lectures.setSlug("lectures");
        lectures.setDisplayOrder(3);
        lectures.setParentCategoryId(null);
        lectures.setCreatedBy("system");
        lectures.setUpdatedBy("system");
        Category savedLectures = categoryRepository.save(lectures);
        lecturesCategoryId = savedLectures.getId();
        log.info("✅ Created category: Lectures (ID: {})", lecturesCategoryId);

        log.info("✅ Seeded 3 categories total");
    }

    private void seedChannels() throws ExecutionException, InterruptedException {
        log.info("📺 Seeding channels...");

        // Sample Quran Channel
        Channel quranChannel = new Channel();
        quranChannel.setYoutubeId("UCvKfr6mE5jDXeW7iOvUFvyA");
        quranChannel.setName("Quran Recitation - Sample");
        quranChannel.setDescription("Beautiful Quran recitations");
        quranChannel.setThumbnailUrl("https://via.placeholder.com/200x200?text=Quran");
        quranChannel.setSubscribers(100000L);
        quranChannel.setVideoCount(50);
        quranChannel.setCategoryIds(Arrays.asList(quranCategoryId));
        quranChannel.setStatus("APPROVED");
        quranChannel.setSubmittedBy("admin@albunyaan.tube");
        quranChannel.setApprovedBy("admin@albunyaan.tube");
        Channel savedQuranChannel = channelRepository.save(quranChannel);
        log.info("✅ Created channel: {} (ID: {})", quranChannel.getName(), savedQuranChannel.getId());

        // Sample Hadith Channel
        Channel hadithChannel = new Channel();
        hadithChannel.setYoutubeId("UChadithSample123");
        hadithChannel.setName("Hadith Studies - Sample");
        hadithChannel.setDescription("Learn authentic Hadith");
        hadithChannel.setThumbnailUrl("https://via.placeholder.com/200x200?text=Hadith");
        hadithChannel.setSubscribers(75000L);
        hadithChannel.setVideoCount(30);
        hadithChannel.setCategoryIds(Arrays.asList(hadithCategoryId));
        hadithChannel.setStatus("APPROVED");
        hadithChannel.setSubmittedBy("admin@albunyaan.tube");
        hadithChannel.setApprovedBy("admin@albunyaan.tube");
        Channel savedHadithChannel = channelRepository.save(hadithChannel);
        log.info("✅ Created channel: {} (ID: {})", hadithChannel.getName(), savedHadithChannel.getId());

        log.info("✅ Seeded 2 channels total");
    }

    private void seedPlaylists() throws ExecutionException, InterruptedException {
        log.info("📋 Seeding playlists...");

        Playlist quranPlaylist = new Playlist();
        quranPlaylist.setYoutubeId("PLSamplePlaylist123");
        quranPlaylist.setTitle("Complete Quran - Sample");
        quranPlaylist.setDescription("Full Quran recitation playlist");
        quranPlaylist.setThumbnailUrl("https://via.placeholder.com/200x200?text=Playlist");
        quranPlaylist.setItemCount(30);
        quranPlaylist.setCategoryIds(Arrays.asList(quranCategoryId));
        quranPlaylist.setStatus("APPROVED");
        quranPlaylist.setSubmittedBy("admin@albunyaan.tube");
        quranPlaylist.setApprovedBy("admin@albunyaan.tube");
        Playlist savedQuranPlaylist = playlistRepository.save(quranPlaylist);
        log.info("✅ Created playlist: {} (ID: {})", quranPlaylist.getTitle(), savedQuranPlaylist.getId());

        Playlist hadithPlaylist = new Playlist();
        hadithPlaylist.setYoutubeId("PLHadithSeries456");
        hadithPlaylist.setTitle("Sahih Bukhari - Sample");
        hadithPlaylist.setDescription("Authentic Hadith collection");
        hadithPlaylist.setThumbnailUrl("https://via.placeholder.com/200x200?text=Hadith+Playlist");
        hadithPlaylist.setItemCount(20);
        hadithPlaylist.setCategoryIds(Arrays.asList(hadithCategoryId));
        hadithPlaylist.setStatus("APPROVED");
        hadithPlaylist.setSubmittedBy("admin@albunyaan.tube");
        hadithPlaylist.setApprovedBy("admin@albunyaan.tube");
        Playlist savedHadithPlaylist = playlistRepository.save(hadithPlaylist);
        log.info("✅ Created playlist: {} (ID: {})", hadithPlaylist.getTitle(), savedHadithPlaylist.getId());

        log.info("✅ Seeded 2 playlists total");
    }

    private void seedVideos() throws ExecutionException, InterruptedException {
        log.info("🎥 Seeding videos...");

        Video quranVideo = new Video();
        quranVideo.setYoutubeId("dQw4w9WgXcQ");
        quranVideo.setTitle("Surah Al-Fatiha - Sample");
        quranVideo.setDescription("Beautiful recitation of Surah Al-Fatiha");
        quranVideo.setThumbnailUrl("https://via.placeholder.com/320x180?text=Video");
        quranVideo.setDurationSeconds(300);
        quranVideo.setViewCount(50000L);
        quranVideo.setUploadedAt(Timestamp.now());
        quranVideo.setCategoryIds(Arrays.asList(quranCategoryId));
        quranVideo.setStatus("APPROVED");
        quranVideo.setSubmittedBy("admin@albunyaan.tube");
        quranVideo.setApprovedBy("admin@albunyaan.tube");
        Video savedQuranVideo = videoRepository.save(quranVideo);
        log.info("✅ Created video: {} (ID: {})", quranVideo.getTitle(), savedQuranVideo.getId());

        Video hadithVideo = new Video();
        hadithVideo.setYoutubeId("hadithVideo123");
        hadithVideo.setTitle("40 Hadith - Lesson 1");
        hadithVideo.setDescription("Learn the 40 essential Hadith");
        hadithVideo.setThumbnailUrl("https://via.placeholder.com/320x180?text=Hadith+Video");
        hadithVideo.setDurationSeconds(900);
        hadithVideo.setViewCount(25000L);
        hadithVideo.setUploadedAt(Timestamp.now());
        hadithVideo.setCategoryIds(Arrays.asList(hadithCategoryId));
        hadithVideo.setStatus("APPROVED");
        hadithVideo.setSubmittedBy("admin@albunyaan.tube");
        hadithVideo.setApprovedBy("admin@albunyaan.tube");
        Video savedHadithVideo = videoRepository.save(hadithVideo);
        log.info("✅ Created video: {} (ID: {})", hadithVideo.getTitle(), savedHadithVideo.getId());

        Video lectureVideo = new Video();
        lectureVideo.setYoutubeId("lectureVideo789");
        lectureVideo.setTitle("Islamic History - The Golden Age");
        lectureVideo.setDescription("Exploring the Islamic Golden Age");
        lectureVideo.setThumbnailUrl("https://via.placeholder.com/320x180?text=Lecture");
        lectureVideo.setDurationSeconds(1800);
        lectureVideo.setViewCount(35000L);
        lectureVideo.setUploadedAt(Timestamp.now());
        lectureVideo.setCategoryIds(Arrays.asList(lecturesCategoryId));
        lectureVideo.setStatus("APPROVED");
        lectureVideo.setSubmittedBy("admin@albunyaan.tube");
        lectureVideo.setApprovedBy("admin@albunyaan.tube");
        Video savedLectureVideo = videoRepository.save(lectureVideo);
        log.info("✅ Created video: {} (ID: {})", lectureVideo.getTitle(), savedLectureVideo.getId());

        log.info("✅ Seeded 3 videos total");
    }
}
