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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * BACKEND-005: Firestore Data Seeder
 *
 * Seeds Firestore with rich sample data for testing and QA environments.
 * Only runs with --seed profile: ./gradlew bootRun --args='--spring.profiles.active=seed'
 */
@Component
@Profile("seed")
public class FirestoreDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FirestoreDataSeeder.class);
    private static final String SEED_USER = "seed-script@albunyaan.tube";

    private final CategoryRepository categoryRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;

    private static final List<CategorySeed> CATEGORY_SEEDS = List.of(
            new CategorySeed("quran", "Qur'an & Recitation", null, 1, "📖",
                    Map.of("en", "Qur'an & Recitation", "ar", "تلاوة القرآن")),
            new CategorySeed("quran-beginner", "Qur'an for Beginners", "quran", 2, "🌱",
                    Map.of("en", "Qur'an for Beginners")),
            new CategorySeed("tajweed", "Tajweed & Pronunciation", "quran", 3, "🎵",
                    Map.of("en", "Tajweed & Pronunciation")),
            new CategorySeed("memorization", "Qur'an Memorization (Hifdh)", "quran", 4, "🧠",
                    Map.of("en", "Qur'an Memorization (Hifdh)")),
            new CategorySeed("hadith", "Hadith & Prophetic Narrations", null, 5, "📿",
                    Map.of("en", "Hadith & Prophetic Narrations")),
            new CategorySeed("hadith-40", "Forty Hadith Collections", "hadith", 6, "4️⃣0️⃣",
                    Map.of("en", "Forty Hadith Collections")),
            new CategorySeed("seerah", "Seerah & Prophetic Biography", null, 7, "🕌",
                    Map.of("en", "Seerah")),
            new CategorySeed("tafsir", "Tafsir & Qur'an Explanation", null, 8, "💡",
                    Map.of("en", "Tafsir & Qur'an Explanation")),
            new CategorySeed("tafsir-bites", "Quick Tafsir Insights", "tafsir", 9, "✨",
                    Map.of("en", "Quick Tafsir Insights")),
            new CategorySeed("aqeedah", "Aqeedah & Creed", null, 10, "🔔",
                    Map.of("en", "Aqeedah")),
            new CategorySeed("fiqh", "Fiqh & Practical Rulings", null, 11, "⚖️",
                    Map.of("en", "Fiqh")),
            new CategorySeed("kids", "Kids Corner", null, 12, "🧒",
                    Map.of("en", "Kids Corner")),
            new CategorySeed("youth", "Youth Programs", null, 13, "👥",
                    Map.of("en", "Youth")),
            new CategorySeed("arabic", "Arabic Language", null, 14, "🔤",
                    Map.of("en", "Arabic")),
            new CategorySeed("nasheed", "Nasheeds (Voice Only)", null, 15, "🎤",
                    Map.of("en", "Nasheeds")),
            new CategorySeed("lifestyle", "Everyday Muslim Life", null, 16, "🌙",
                    Map.of("en", "Lifestyle")),
            new CategorySeed("history", "Islamic History", null, 17, "📜",
                    Map.of("en", "History")),
            new CategorySeed("revert-support", "New Muslim Support", null, 18, "🤝",
                    Map.of("en", "New Muslim Support")),
            new CategorySeed("wellness", "Wellness & Mindfulness", null, 19, "🧘",
                    Map.of("en", "Wellness"))
    );

    private static final List<ChannelSeed> CHANNEL_SEEDS = List.of(
            new ChannelSeed("channel_quran_path", channelYoutubeId(1), "Quran Path",
                    "Daily mujawwad recitations from renowned reciters.",
                    List.of("quran", "tajweed"), "APPROVED", 450_000L, 182,
                    placeholder("Quran+Path"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Surah Ar-Rahman Full", "Morning Recitations", "Nightly Qiyam", "Tajweed Tips")),
            new ChannelSeed("channel_quran_reflections", channelYoutubeId(2), "Quran Reflections",
                    "Short reflections and thematic Qur'an series.",
                    List.of("quran", "tafsir-bites"), "APPROVED", 220_000L, 140,
                    placeholder("Quran+Reflections"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Daily Reflections", "Qur'an Gems", "Pearls from Juz 30")),
            new ChannelSeed("channel_hifdh_circle", channelYoutubeId(3), "Hifdh Circle",
                    "Step-by-step memorization guidance for all ages.",
                    List.of("memorization", "quran-beginner"), "APPROVED", 310_500L, 210,
                    placeholder("Hifdh+Circle"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    new ExclusionSeed(List.of("vid-short-instrumental"), List.of(), List.of("short-behind-scenes"), List.of(), List.of()),
                    List.of("Follow-Along Hifdh", "Revision Session", "Daily Hifdh Routine")),
            new ChannelSeed("channel_kids_quran_club", channelYoutubeId(4), "Kids Quran Club",
                    "Interactive Qur'an lessons for children.",
                    List.of("kids", "quran-beginner"), "APPROVED", 190_300L, 95,
                    placeholder("Kids+Quran"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Kids Recitation Practice", "Alphabet Songs", "Stories Behind Surahs")),
            new ChannelSeed("channel_tajweed_mastery", channelYoutubeId(5), "Tajweed Mastery",
                    "Advanced tajweed workshops and live corrections.",
                    List.of("tajweed"), "APPROVED", 125_000L, 160,
                    placeholder("Tajweed+Mastery"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Makharij Workshop", "Common Mistakes Fix", "Rules of Noon Saakin")),
            new ChannelSeed("channel_tafsir_mornings", channelYoutubeId(6), "Tafsir Mornings",
                    "Morning tafsir sessions covering thematic passages.",
                    List.of("tafsir", "quran"), "APPROVED", 175_000L, 132,
                    placeholder("Tafsir+Mornings"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Surah Yasin Insights", "Short Tafsir Sessions", "Context of Revelation")),
            new ChannelSeed("channel_hadith_daily", channelYoutubeId(7), "Hadith Daily",
                    "Daily reminders with practical commentary on hadith.",
                    List.of("hadith"), "APPROVED", 205_000L, 188,
                    placeholder("Hadith+Daily"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Daily Hadith Reminder", "Hadith Commentary", "Applying Hadith Today")),
            new ChannelSeed("channel_hadith_40", channelYoutubeId(8), "Forty Hadith Explained",
                    "Detailed walk-through of Imam Nawawi's collection.",
                    List.of("hadith-40", "aqeedah"), "APPROVED", 132_000L, 76,
                    placeholder("Forty+Hadith"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Forty Hadith Explained", "Imam Nawawi Lessons", "Hadith in Action")),
            new ChannelSeed("channel_seerah_stories", channelYoutubeId(9), "Seerah Stories",
                    "Narrative storytelling covering the Prophetic biography.",
                    List.of("seerah", "history"), "APPROVED", 260_000L, 145,
                    placeholder("Seerah+Stories"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Life of the Prophet", "Companions Spotlight", "Makkah Period")),
            new ChannelSeed("channel_history_timelines", channelYoutubeId(10), "History Timelines",
                    "Animated Islamic history timelines and lessons.",
                    List.of("history"), "APPROVED", 98_000L, 104,
                    placeholder("History+Timelines"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Golden Age Highlights", "Civilization Series", "Lessons from Andalusia")),
            new ChannelSeed("channel_aqeedah_academy", channelYoutubeId(11), "Aqeedah Academy",
                    "Structured creed program for all levels.",
                    List.of("aqeedah"), "APPROVED", 143_000L, 90,
                    placeholder("Aqeedah+Academy"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Foundations of Faith", "Names & Attributes", "Belief in Angels")),
            new ChannelSeed("channel_fiqh_focus", channelYoutubeId(12), "Fiqh Focus",
                    "Fiqh lessons with contemporary applications.",
                    List.of("fiqh"), "APPROVED", 167_500L, 122,
                    placeholder("Fiqh+Focus"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Fiqh of Purification", "Prayer Essentials", "Modern Transactions")),
            new ChannelSeed("channel_friday_khutbah", channelYoutubeId(13), "Friday Khutbah Archive",
                    "Curated khutbah summaries and action plans.",
                    List.of("fiqh", "lifestyle"), "APPROVED", 212_400L, 156,
                    placeholder("Khutbah+Archive"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Khutbah Highlights", "Community Reminders", "Action Points")),
            new ChannelSeed("channel_arabic_bites", channelYoutubeId(14), "Arabic Bites",
                    "Five-minute Arabic lessons for busy learners.",
                    List.of("arabic", "youth"), "APPROVED", 188_900L, 140,
                    placeholder("Arabic+Bites"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Arabic in 5 Minutes", "Vocabulary Boost", "Grammar Hacks")),
            new ChannelSeed("channel_arabic_steps", channelYoutubeId(15), "Arabic Steps",
                    "Arabic fundamentals for new Muslims.",
                    List.of("arabic", "revert-support"), "PENDING", 64_000L, 48,
                    placeholder("Arabic+Steps"), "moderator@albunyaan.tube", null,
                    ExclusionSeed.none(),
                    List.of("Alphabet Basics", "Reading Practice", "Pronunciation Drills")),
            new ChannelSeed("channel_youth_forum", channelYoutubeId(16), "Youth Forum",
                    "Discussions and reminders tailored for teens.",
                    List.of("youth"), "APPROVED", 156_700L, 112,
                    placeholder("Youth+Forum"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Campus Reflections", "Peer Advice", "Balancing Studies")),
            new ChannelSeed("channel_productive_muslim", channelYoutubeId(17), "Productive Muslim",
                    "Productivity frameworks grounded in Islamic principles.",
                    List.of("lifestyle", "wellness"), "APPROVED", 301_200L, 210,
                    placeholder("Productive+Muslim"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Morning Routines", "Focus & Productivity", "Mindful Evenings")),
            new ChannelSeed("channel_muslim_wellness", channelYoutubeId(18), "Muslim Wellness",
                    "Mindfulness, breathing, and spiritual wellbeing.",
                    List.of("wellness"), "APPROVED", 149_300L, 88,
                    placeholder("Muslim+Wellness"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Guided Breathing", "Stress Relief", "Spiritual Check-ins")),
            new ChannelSeed("channel_family_circle", channelYoutubeId(19), "Family Circle",
                    "Family halaqah outlines and parenting discussions.",
                    List.of("lifestyle", "kids"), "PENDING", 72_400L, 66,
                    placeholder("Family+Circle"), "moderator@albunyaan.tube", null,
                    ExclusionSeed.none(),
                    List.of("Family Halaqah", "Parenting Tips", "Story Night")),
            new ChannelSeed("channel_revert_support", channelYoutubeId(20), "Revert Support",
                    "Guidance and mentorship for new Muslims.",
                    List.of("revert-support", "aqeedah"), "PENDING", 58_900L, 42,
                    placeholder("Revert+Support"), "moderator@albunyaan.tube", null,
                    ExclusionSeed.none(),
                    List.of("First Steps", "Understanding Salah", "Building Support Systems")),
            new ChannelSeed("channel_dawah_digital", channelYoutubeId(21), "Dawah Digital",
                    "Digital-first dawah strategies and inspirational stories.",
                    List.of("revert-support", "youth"), "APPROVED", 118_000L, 134,
                    placeholder("Dawah+Digital"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Online Dawah Tactics", "Story Highlights", "Case Studies")),
            new ChannelSeed("channel_nasheed_hub", channelYoutubeId(22), "Nasheed Hub",
                    "Voice-only nasheeds curated for all ages.",
                    List.of("nasheed", "youth"), "APPROVED", 204_700L, 76,
                    placeholder("Nasheed+Hub"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    new ExclusionSeed(List.of(), List.of(), List.of("shorts-beat-heavy"), List.of("playlist-music-heavy"), List.of()),
                    List.of("Voice Only Hits", "Motivational Nasheeds", "Acapella Classics")),
            new ChannelSeed("channel_masjid_live", channelYoutubeId(23), "Masjid Live",
                    "Khutbahs, classes, and community updates from the masjid.",
                    List.of("fiqh", "quran"), "APPROVED", 239_500L, 240,
                    placeholder("Masjid+Live"), "admin@albunyaan.tube", "admin@albunyaan.tube",
                    ExclusionSeed.none(),
                    List.of("Khutbah Replay", "Community Update", "Weekly Tafsir")),
            new ChannelSeed("channel_new_muslim_journey", channelYoutubeId(24), "New Muslim Journey",
                    "Stories and guidance tailored for new Muslims.",
                    List.of("revert-support", "lifestyle"), "PENDING", 47_600L, 28,
                    placeholder("New+Muslim+Journey"), "moderator@albunyaan.tube", null,
                    ExclusionSeed.none(),
                    List.of("Conversion Stories", "Staying Consistent", "Community Connections")),
            new ChannelSeed("channel_counseling_corner", channelYoutubeId(25), "Counseling Corner",
                    "Wellness check-ins and faith-based counseling snippets.",
                    List.of("wellness", "revert-support"), "PENDING", 39_200L, 24,
                    placeholder("Counseling+Corner"), "moderator@albunyaan.tube", null,
                    ExclusionSeed.none(),
                    List.of("Wellness Check", "Grounding Exercises", "Seek Support"))
    );

    private static final List<PlaylistSeed> PLAYLIST_SEEDS = List.of(
            new PlaylistSeed("playlist_quran_juz_amma", playlistYoutubeId(1), "Juz Amma - Slow Recitation",
                    "Follow-along recitation for beginners.", List.of("quran", "quran-beginner"),
                    "channel_quran_path", "APPROVED", 37, placeholder("Juz+Amma"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_quran_morning", playlistYoutubeId(2), "Morning Recitation Flow",
                    "Gentle recitations ideal for the morning commute.", List.of("quran"),
                    "channel_quran_reflections", "APPROVED", 25, placeholder("Morning+Recitation"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_hifdh_revision", playlistYoutubeId(3), "Weekly Hifdh Revision",
                    "Structured revision plan for hifdh students.", List.of("memorization"),
                    "channel_hifdh_circle", "APPROVED", 18, placeholder("Hifdh+Revision"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of("vid00000009")),
            new PlaylistSeed("playlist_kids_storytime", playlistYoutubeId(4), "Kids Storytime Surahs",
                    "Animated stories with related short surahs.", List.of("kids", "quran-beginner"),
                    "channel_kids_quran_club", "APPROVED", 22, placeholder("Kids+Storytime"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_tajweed_rules", playlistYoutubeId(5), "Complete Tajweed Workshop",
                    "Rules of tajweed with practical exercises.", List.of("tajweed"),
                    "channel_tajweed_mastery", "APPROVED", 30, placeholder("Tajweed+Workshop"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_tafsir_series", playlistYoutubeId(6), "Surah Yasin Tafsir Series",
                    "Deep dive into Surah Yasin with historical context.", List.of("tafsir"),
                    "channel_tafsir_mornings", "APPROVED", 15, placeholder("Tafsir+Series"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_daily_hadith", playlistYoutubeId(7), "Daily Hadith Reminder",
                    "Short daily hadith reminders with action items.", List.of("hadith"),
                    "channel_hadith_daily", "APPROVED", 45, placeholder("Daily+Hadith"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_40_hadith", playlistYoutubeId(8), "Forty Hadith Explained",
                    "Complete series of Imam Nawawi's forty hadith.", List.of("hadith-40", "aqeedah"),
                    "channel_hadith_40", "APPROVED", 40, placeholder("Forty+Hadith+Series"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_seerah_journey", playlistYoutubeId(9), "Seerah Journey",
                    "Chronological journey through the Prophetic biography.", List.of("seerah", "history"),
                    "channel_seerah_stories", "APPROVED", 28, placeholder("Seerah+Journey"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_history_masterclass", playlistYoutubeId(10), "History Masterclass",
                    "Islamic civilization milestones explained.", List.of("history"),
                    "channel_history_timelines", "APPROVED", 20, placeholder("History+Masterclass"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_aqeedah_foundations", playlistYoutubeId(11), "Aqeedah Foundations",
                    "Core creed lessons with quizzes.", List.of("aqeedah"),
                    "channel_aqeedah_academy", "APPROVED", 24, placeholder("Aqeedah+Foundations"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_fiqh_primer", playlistYoutubeId(12), "Fiqh Primer",
                    "Essentials of worship for beginners.", List.of("fiqh"),
                    "channel_fiqh_focus", "APPROVED", 26, placeholder("Fiqh+Primer"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_khutbah_series", playlistYoutubeId(13), "Khutbah Series",
                    "Seasonal khutbahs with action summaries.", List.of("fiqh", "lifestyle"),
                    "channel_friday_khutbah", "APPROVED", 18, placeholder("Khutbah+Series"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_arabic_starter", playlistYoutubeId(14), "Arabic Starter Pack",
                    "Foundational Arabic lessons for new learners.", List.of("arabic", "revert-support"),
                    "channel_arabic_steps", "PENDING", 12, placeholder("Arabic+Starter"),
                    "moderator@albunyaan.tube", null, List.of()),
            new PlaylistSeed("playlist_youth_sessions", playlistYoutubeId(15), "Youth Sessions",
                    "Interactive workshops for teens.", List.of("youth"),
                    "channel_youth_forum", "APPROVED", 16, placeholder("Youth+Sessions"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_productivity_plan", playlistYoutubeId(16), "Productivity Plan",
                    "30-day productivity challenge.", List.of("lifestyle", "wellness"),
                    "channel_productive_muslim", "APPROVED", 30, placeholder("Productivity+Plan"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_wellness_reset", playlistYoutubeId(17), "Wellness Reset",
                    "Guided mindfulness reset for busy Muslims.", List.of("wellness"),
                    "channel_muslim_wellness", "APPROVED", 14, placeholder("Wellness+Reset"),
                    "admin@albunyaan.tube", "admin@albunyaan.tube", List.of()),
            new PlaylistSeed("playlist_family_halaqah", playlistYoutubeId(18), "Family Halaqah Plan",
                    "Weekly family halaqah outline for parents.", List.of("lifestyle", "kids"),
                    "channel_family_circle", "PENDING", 10, placeholder("Family+Halaqah"),
                    "moderator@albunyaan.tube", null, List.of()),
            new PlaylistSeed("playlist_new_muslim_path", playlistYoutubeId(19), "New Muslim Path",
                    "Four-week roadmap for new Muslims.", List.of("revert-support", "aqeedah"),
                    "channel_new_muslim_journey", "PENDING", 8, placeholder("New+Muslim+Path"),
                    "moderator@albunyaan.tube", null, List.of())
    );

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
            cleanupLegacySeedData();

            Map<String, Category> categories = seedCategories();
            Map<String, Channel> channels = seedChannels(categories);
            seedPlaylists(categories, channels);
            seedVideos(categories, channels);
            log.info("✅ Firestore data seeding completed successfully!");
        } catch (Exception e) {
            log.error("❌ Error seeding Firestore data", e);
            throw e;
        }
    }

    private Map<String, Category> seedCategories() throws ExecutionException, InterruptedException {
        log.info("📂 Seeding categories ({} planned)...", CATEGORY_SEEDS.size());
        Map<String, Category> categories = new HashMap<>();

        for (CategorySeed seed : CATEGORY_SEEDS) {
            Category category = categoryRepository.findById(seed.id())
                    .orElseGet(Category::new);
            boolean isNew = category.getId() == null;

            category.setId(seed.id());
            category.setName(seed.name());
            category.setSlug(seed.id());
            category.setParentCategoryId(seed.parentId());
            category.setDisplayOrder(seed.displayOrder());
            category.setIcon(seed.icon());
            category.setLocalizedNames(new HashMap<>(seed.localizedNames()));
            if (isNew) {
                category.setCreatedBy(SEED_USER);
            }
            category.setUpdatedBy(SEED_USER);

            Category saved = categoryRepository.save(category);
            categories.put(seed.id(), saved);
        }

        log.info("✅ Seeded {} categories", categories.size());
        return categories;
    }

    private void cleanupLegacySeedData() throws ExecutionException, InterruptedException {
        removeLegacyCategories();
    }

    private void removeLegacyCategories() throws ExecutionException, InterruptedException {
        List<Category> existingCategories = categoryRepository.findAll();
        Set<String> targetIds = CATEGORY_SEEDS.stream()
                .map(CategorySeed::id)
                .collect(Collectors.toSet());
        int removed = 0;

        for (Category category : existingCategories) {
            if (targetIds.contains(category.getId())) {
                continue;
            }

            String createdBy = category.getCreatedBy();
            if (createdBy == null || SEED_USER.equals(createdBy) || "system".equalsIgnoreCase(createdBy)) {
                log.info("🧹 Removing legacy seed category: {} ({})", category.getName(), category.getId());
                categoryRepository.deleteById(category.getId());
                removed++;
            }
        }

        if (removed > 0) {
            log.info("🧼 Removed {} legacy categories created by previous seed runs", removed);
        }
    }

    private Map<String, Channel> seedChannels(Map<String, Category> categories)
            throws ExecutionException, InterruptedException {
        log.info("📺 Seeding channels ({} planned)...", CHANNEL_SEEDS.size());
        Map<String, Channel> channels = new HashMap<>();

        for (ChannelSeed seed : CHANNEL_SEEDS) {
            Channel channel = channelRepository.findById(seed.id())
                    .orElseGet(Channel::new);

            channel.setId(seed.id());
            channel.setYoutubeId(seed.youtubeId());
            channel.setName(seed.name());
            channel.setDescription(seed.description());
            channel.setThumbnailUrl(seed.thumbnailUrl());
            channel.setSubscribers(seed.subscribers());
            channel.setVideoCount(seed.videoCount());

            List<String> categoryIds = seed.categoryIds().stream()
                    .map(id -> {
                        Category category = categories.get(id);
                        if (category == null) {
                            throw new IllegalStateException("Category seed missing: " + id);
                        }
                        return category.getId();
                    })
                    .collect(Collectors.toList());
            channel.setCategoryIds(categoryIds);
            if (!categoryIds.isEmpty()) {
                channel.setCategory(categories.get(seed.categoryIds().get(0)));
            }

            channel.setStatus(seed.status());
            channel.setSubmittedBy(seed.submittedBy());
            if ("APPROVED".equalsIgnoreCase(seed.status())) {
                channel.setApprovedBy(seed.approvedBy());
            } else {
                channel.setApprovedBy(null);
            }

            Channel.ExcludedItems excludedItems = new Channel.ExcludedItems();
            excludedItems.setVideos(new ArrayList<>(seed.exclusions().videos()));
            excludedItems.setLiveStreams(new ArrayList<>(seed.exclusions().liveStreams()));
            excludedItems.setShorts(new ArrayList<>(seed.exclusions().shorts()));
            excludedItems.setPlaylists(new ArrayList<>(seed.exclusions().playlists()));
            excludedItems.setPosts(new ArrayList<>(seed.exclusions().posts()));
            channel.setExcludedItems(excludedItems);

            Channel saved = channelRepository.save(channel);
            channels.put(seed.id(), saved);
        }

        long approvedCount = channels.values().stream()
                .filter(ch -> "APPROVED".equalsIgnoreCase(ch.getStatus()))
                .count();
        long pendingCount = channels.size() - approvedCount;
        log.info("✅ Seeded {} channels ({} approved / {} pending)", channels.size(), approvedCount, pendingCount);
        return channels;
    }

    private void seedPlaylists(Map<String, Category> categories, Map<String, Channel> channels)
            throws ExecutionException, InterruptedException {
        log.info("📋 Seeding playlists ({} planned)...", PLAYLIST_SEEDS.size());

        int approved = 0;
        for (PlaylistSeed seed : PLAYLIST_SEEDS) {
            Playlist playlist = playlistRepository.findById(seed.id())
                    .orElseGet(Playlist::new);

            playlist.setId(seed.id());
            playlist.setYoutubeId(seed.youtubeId());
            playlist.setTitle(seed.title());
            playlist.setDescription(seed.description());
            playlist.setThumbnailUrl(seed.thumbnailUrl());
            playlist.setItemCount(seed.itemCount());

            List<String> categoryIds = seed.categoryIds().stream()
                    .map(id -> {
                        Category category = categories.get(id);
                        if (category == null) {
                            throw new IllegalStateException("Category seed missing: " + id);
                        }
                        return category.getId();
                    })
                    .collect(Collectors.toList());
            playlist.setCategoryIds(categoryIds);

            playlist.setStatus(seed.status());
            playlist.setSubmittedBy(seed.submittedBy());
            if ("APPROVED".equalsIgnoreCase(seed.status())) {
                playlist.setApprovedBy(seed.approvedBy());
                approved++;
            } else {
                playlist.setApprovedBy(null);
            }

            playlist.setExcludedVideoIds(new ArrayList<>(seed.excludedVideoIds()));

            if (!channels.containsKey(seed.channelId())) {
                log.warn("⚠️ Playlist seed '{}' references unknown channel '{}'", seed.id(), seed.channelId());
            }

            playlistRepository.save(playlist);
        }

        log.info("✅ Seeded {} playlists ({} approved / {} pending)",
                PLAYLIST_SEEDS.size(), approved, PLAYLIST_SEEDS.size() - approved);
    }

    private void seedVideos(Map<String, Category> categories, Map<String, Channel> channels)
            throws ExecutionException, InterruptedException {
        log.info("🎥 Seeding videos from {} channels...", CHANNEL_SEEDS.size());

        Map<String, ChannelSeed> channelSeedsById = CHANNEL_SEEDS.stream()
                .collect(Collectors.toMap(ChannelSeed::id, seed -> seed));

        int videoCounter = 1;
        int totalVideos = 0;

        for (Channel channel : channels.values()) {
            ChannelSeed seed = channelSeedsById.get(channel.getId());
            if (seed == null) {
                continue;
            }

            List<String> topics = seed.videoTopics();
            if (topics.isEmpty()) {
                topics = List.of("Highlights", "Deep Dive", "Quick Lesson");
            }

            int index = 0;
            for (String topic : topics) {
                index++;

                String videoDocId = "video_" + channel.getId() + "_" + index;
                Video video = videoRepository.findById(videoDocId)
                        .orElseGet(Video::new);

                video.setId(videoDocId);
                video.setYoutubeId(String.format("vid%08d", videoCounter++));
                video.setTitle(channel.getName() + " - " + topic);
                video.setDescription(topic + " from " + channel.getName());
                video.setThumbnailUrl(placeholder(topic));
                video.setDurationSeconds(240 + index * 60);
                video.setViewCount(10_000L * (index + 1) + channel.getSubscribers() / 1000);

                Instant uploadedAt = Instant.now()
                        .minus(index * 3L, ChronoUnit.DAYS)
                        .minus(channels.size() - totalVideos % channels.size(), ChronoUnit.HOURS);
                video.setUploadedAt(Timestamp.ofTimeSecondsAndNanos(uploadedAt.getEpochSecond(), 0));

                video.setChannelId(channel.getId());
                video.setChannelTitle(channel.getName());
                video.setCategoryIds(new ArrayList<>(channel.getCategoryIds()));

                video.setSubmittedBy(seed.submittedBy());
                if ("APPROVED".equalsIgnoreCase(seed.status()) && index < topics.size()) {
                    video.setStatus("APPROVED");
                    video.setApprovedBy(seed.approvedBy());
                } else {
                    video.setStatus("PENDING");
                    video.setApprovedBy(null);
                }

                videoRepository.save(video);
                totalVideos++;
            }
        }

        log.info("✅ Seeded {} videos", totalVideos);
    }

    private static String channelYoutubeId(int index) {
        return String.format("UC%022d", index);
    }

    private static String playlistYoutubeId(int index) {
        return String.format("PL%08dSEED", index);
    }

    private static String placeholder(String text) {
        return "https://via.placeholder.com/320x180?text=" + text.replace(" ", "+");
    }

    private record CategorySeed(
            String id,
            String name,
            String parentId,
            int displayOrder,
            String icon,
            Map<String, String> localizedNames
    ) {
    }

    private record ChannelSeed(
            String id,
            String youtubeId,
            String name,
            String description,
            List<String> categoryIds,
            String status,
            long subscribers,
            int videoCount,
            String thumbnailUrl,
            String submittedBy,
            String approvedBy,
            ExclusionSeed exclusions,
            List<String> videoTopics
    ) {
    }

    private record PlaylistSeed(
            String id,
            String youtubeId,
            String title,
            String description,
            List<String> categoryIds,
            String channelId,
            String status,
            int itemCount,
            String thumbnailUrl,
            String submittedBy,
            String approvedBy,
            List<String> excludedVideoIds
    ) {
    }

    private record ExclusionSeed(
            List<String> videos,
            List<String> liveStreams,
            List<String> shorts,
            List<String> playlists,
            List<String> posts
    ) {
        static ExclusionSeed none() {
            return new ExclusionSeed(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
