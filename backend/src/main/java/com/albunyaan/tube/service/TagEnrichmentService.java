package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for enriching content with comprehensive, multilingual search tags.
 *
 * Generates keywords from multiple sources:
 * 1. YouTube tags (fetched via NewPipeExtractor when real YouTube IDs exist)
 * 2. Category-based tags (curated multilingual dictionary: en, ar, nl)
 * 3. Title/description keyword extraction
 * 4. Cross-language translations via Islamic terms dictionary
 *
 * All tags are normalized, deduplicated, and capped at 50 per item.
 */
@Service
public class TagEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(TagEnrichmentService.class);

    private static final int MAX_KEYWORDS = 50;
    private static final int MAX_KEYWORD_LENGTH = 100;

    /** Minimum length for extracted keywords (filters noise) */
    private static final int MIN_KEYWORD_LENGTH = 2;

    /** Pattern to detect fake/seed YouTube IDs that can't be fetched */
    private static final Pattern FAKE_YOUTUBE_ID = Pattern.compile(
            "^(UC0{10,}|PL0+\\d*SEED|vid\\d{8})$"
    );

    private final VideoRepository videoRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final YouTubeGateway youTubeGateway;
    private final PublicContentCacheService publicContentCacheService;

    public TagEnrichmentService(
            VideoRepository videoRepository,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            YouTubeGateway youTubeGateway,
            PublicContentCacheService publicContentCacheService
    ) {
        this.videoRepository = videoRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.youTubeGateway = youTubeGateway;
        this.publicContentCacheService = publicContentCacheService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public enrichment entry points
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enrich all content types with tags.
     *
     * @param force         if true, re-enrich items that already have keywords
     * @param fetchYouTube  if true, attempt to fetch tags from YouTube for real IDs
     * @return combined enrichment result
     */
    public EnrichmentResult enrichAllContent(boolean force, boolean fetchYouTube) {
        log.info("Starting full tag enrichment (force={}, fetchYouTube={})", force, fetchYouTube);

        EnrichmentResult channels = enrichChannels(force, fetchYouTube);
        EnrichmentResult playlists = enrichPlaylists(force, fetchYouTube);
        EnrichmentResult videos = enrichVideos(force, fetchYouTube);

        EnrichmentResult combined = new EnrichmentResult();
        combined.total = channels.total + playlists.total + videos.total;
        combined.enriched = channels.enriched + playlists.enriched + videos.enriched;
        combined.skipped = channels.skipped + playlists.skipped + videos.skipped;
        combined.errors = channels.errors + playlists.errors + videos.errors;
        combined.errorMessages.addAll(channels.errorMessages);
        combined.errorMessages.addAll(playlists.errorMessages);
        combined.errorMessages.addAll(videos.errorMessages);

        log.info("Tag enrichment complete: {} total, {} enriched, {} skipped, {} errors",
                combined.total, combined.enriched, combined.skipped, combined.errors);

        publicContentCacheService.evictPublicContentCaches();
        return combined;
    }

    public EnrichmentResult enrichChannels(boolean force, boolean fetchYouTube) {
        EnrichmentResult result = new EnrichmentResult();
        try {
            List<Channel> channels = channelRepository.findAll();
            result.total = channels.size();
            log.info("Enriching {} channels...", channels.size());

            for (Channel channel : channels) {
                try {
                    if (!force && channel.getKeywords() != null && !channel.getKeywords().isEmpty()) {
                        result.skipped++;
                        continue;
                    }

                    List<String> youtubeTags = Collections.emptyList();
                    if (fetchYouTube && !isFakeYouTubeId(channel.getYoutubeId())) {
                        youtubeTags = fetchChannelTags(channel.getYoutubeId());
                    }

                    List<String> tags = generateTags(
                            channel.getName(),
                            channel.getDescription(),
                            channel.getCategoryIds(),
                            youtubeTags
                    );

                    channel.setKeywords(tags);
                    channel.touch();
                    channelRepository.save(channel);
                    result.enriched++;

                    log.debug("Enriched channel '{}' with {} tags", channel.getName(), tags.size());
                } catch (Exception e) {
                    result.errors++;
                    result.errorMessages.add("Channel " + channel.getId() + ": " + e.getMessage());
                    log.warn("Failed to enrich channel '{}': {}", channel.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load channels for enrichment: {}", e.getMessage());
            result.errorMessages.add("Failed to load channels: " + e.getMessage());
        }
        return result;
    }

    public EnrichmentResult enrichPlaylists(boolean force, boolean fetchYouTube) {
        EnrichmentResult result = new EnrichmentResult();
        try {
            List<Playlist> playlists = playlistRepository.findAll();
            result.total = playlists.size();
            log.info("Enriching {} playlists...", playlists.size());

            for (Playlist playlist : playlists) {
                try {
                    if (!force && playlist.getKeywords() != null && !playlist.getKeywords().isEmpty()) {
                        result.skipped++;
                        continue;
                    }

                    // PlaylistInfo doesn't have getTags() - only use metadata-based generation
                    List<String> tags = generateTags(
                            playlist.getTitle(),
                            playlist.getDescription(),
                            playlist.getCategoryIds(),
                            Collections.emptyList()
                    );

                    playlist.setKeywords(tags);
                    playlist.touch();
                    playlistRepository.save(playlist);
                    result.enriched++;

                    log.debug("Enriched playlist '{}' with {} tags", playlist.getTitle(), tags.size());
                } catch (Exception e) {
                    result.errors++;
                    result.errorMessages.add("Playlist " + playlist.getId() + ": " + e.getMessage());
                    log.warn("Failed to enrich playlist '{}': {}", playlist.getTitle(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load playlists for enrichment: {}", e.getMessage());
            result.errorMessages.add("Failed to load playlists: " + e.getMessage());
        }
        return result;
    }

    public EnrichmentResult enrichVideos(boolean force, boolean fetchYouTube) {
        EnrichmentResult result = new EnrichmentResult();
        try {
            List<Video> videos = videoRepository.findAll();
            result.total = videos.size();
            log.info("Enriching {} videos...", videos.size());

            for (Video video : videos) {
                try {
                    if (!force && video.getKeywords() != null && !video.getKeywords().isEmpty()) {
                        result.skipped++;
                        continue;
                    }

                    List<String> youtubeTags = Collections.emptyList();
                    if (fetchYouTube && !isFakeYouTubeId(video.getYoutubeId())) {
                        youtubeTags = fetchVideoTags(video.getYoutubeId());
                    }

                    List<String> tags = generateTags(
                            video.getTitle(),
                            video.getDescription(),
                            video.getCategoryIds(),
                            youtubeTags
                    );

                    // Add channel name as a tag if available
                    if (video.getChannelTitle() != null && !video.getChannelTitle().isBlank()) {
                        tags = new ArrayList<>(tags);
                        if (!tags.contains(video.getChannelTitle())) {
                            tags.add(video.getChannelTitle());
                        }
                    }

                    tags = normalize(tags);
                    video.setKeywords(tags);
                    video.touch();
                    videoRepository.save(video);
                    result.enriched++;

                    log.debug("Enriched video '{}' with {} tags", video.getTitle(), tags.size());
                } catch (Exception e) {
                    result.errors++;
                    result.errorMessages.add("Video " + video.getId() + ": " + e.getMessage());
                    log.warn("Failed to enrich video '{}': {}", video.getTitle(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load videos for enrichment: {}", e.getMessage());
            result.errorMessages.add("Failed to load videos: " + e.getMessage());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tag generation pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate comprehensive multilingual tags from all available sources.
     */
    List<String> generateTags(String title, String description,
                              List<String> categoryIds, List<String> youtubeTags) {
        Set<String> tags = new LinkedHashSet<>();

        // 1. YouTube tags first (highest quality, from the source)
        if (youtubeTags != null) {
            tags.addAll(youtubeTags);
        }

        // 2. Keywords extracted from title (most specific to this content)
        if (title != null && !title.isBlank()) {
            tags.addAll(extractKeywords(title));
        }

        // 3. Keywords extracted from description
        if (description != null && !description.isBlank()) {
            tags.addAll(extractKeywords(description));
        }

        // 4. Category-based tags (curated multilingual — broader)
        if (categoryIds != null) {
            for (String categoryId : categoryIds) {
                List<String> categoryTags = CATEGORY_TAGS.get(categoryId);
                if (categoryTags != null) {
                    tags.addAll(categoryTags);
                }
            }
        }

        // 5. Cross-language translations for all collected tags
        tags = addTranslations(tags);

        return normalize(new ArrayList<>(tags));
    }

    /**
     * Generate tags for a specific content item (used by seeder and other callers).
     * Convenience method that doesn't require YouTube fetching.
     */
    public static List<String> generateTagsStatic(String title, String description,
                                                   List<String> categoryIds) {
        Set<String> tags = new LinkedHashSet<>();

        // Keywords from title first (most specific to this content item)
        if (title != null && !title.isBlank()) {
            tags.addAll(extractKeywordsStatic(title));
        }

        // Keywords from description
        if (description != null && !description.isBlank()) {
            tags.addAll(extractKeywordsStatic(description));
        }

        // Category-based tags (broader, multilingual)
        if (categoryIds != null) {
            for (String categoryId : categoryIds) {
                List<String> categoryTags = CATEGORY_TAGS.get(categoryId);
                if (categoryTags != null) {
                    tags.addAll(categoryTags);
                }
            }
        }

        // Translations
        tags = addTranslationsStatic(tags);

        return normalizeStatic(new ArrayList<>(tags));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YouTube tag fetching
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> fetchVideoTags(String youtubeId) {
        try {
            StreamInfo info = youTubeGateway.fetchStreamInfo(youtubeId);
            if (info != null && info.getTags() != null) {
                log.debug("Fetched {} YouTube tags for video {}", info.getTags().size(), youtubeId);
                return info.getTags();
            }
        } catch (Exception e) {
            log.warn("Could not fetch YouTube tags for video {}: {}", youtubeId, e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<String> fetchChannelTags(String youtubeId) {
        try {
            ChannelInfo info = youTubeGateway.fetchChannelInfo(youtubeId);
            if (info != null && info.getTags() != null) {
                log.debug("Fetched {} YouTube tags for channel {}", info.getTags().size(), youtubeId);
                return info.getTags();
            }
        } catch (Exception e) {
            log.warn("Could not fetch YouTube tags for channel {}: {}", youtubeId, e.getMessage());
        }
        return Collections.emptyList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyword extraction
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> extractKeywords(String text) {
        return extractKeywordsStatic(text);
    }

    static List<String> extractKeywordsStatic(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        List<String> keywords = new ArrayList<>();

        // Split on common delimiters: spaces, commas, pipes, dashes (but preserve Arabic)
        String[] tokens = text.split("[\\s,|()\\[\\]{}:;.!?\"']+");

        for (String token : tokens) {
            String cleaned = token.trim();
            if (cleaned.length() < MIN_KEYWORD_LENGTH) continue;
            if (STOP_WORDS.contains(cleaned.toLowerCase(Locale.ROOT))) continue;
            if (cleaned.matches("^\\d+$")) continue; // pure numbers
            if (cleaned.length() > MAX_KEYWORD_LENGTH) continue;

            keywords.add(cleaned);
        }

        // Also extract multi-word phrases from title (keep " - " separated parts)
        if (text.contains(" - ")) {
            for (String part : text.split(" - ")) {
                String trimmed = part.trim();
                if (trimmed.length() >= MIN_KEYWORD_LENGTH && trimmed.length() <= MAX_KEYWORD_LENGTH) {
                    keywords.add(trimmed);
                }
            }
        }

        return keywords;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Translation
    // ─────────────────────────────────────────────────────────────────────────

    private Set<String> addTranslations(Set<String> tags) {
        return addTranslationsStatic(tags);
    }

    static Set<String> addTranslationsStatic(Set<String> tags) {
        Set<String> enriched = new LinkedHashSet<>(tags);
        for (String tag : tags) {
            String tagLower = tag.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> entry : TRANSLATION_DICT.entrySet()) {
                List<String> translations = entry.getValue();
                boolean matched = false;
                for (String t : translations) {
                    if (t.equalsIgnoreCase(tagLower) || tagLower.contains(t.toLowerCase(Locale.ROOT))) {
                        matched = true;
                        break;
                    }
                }
                if (matched) {
                    enriched.addAll(translations);
                }
            }
        }
        return enriched;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Normalization
    // ─────────────────────────────────────────────────────────────────────────

    private List<String> normalize(List<String> tags) {
        return normalizeStatic(tags);
    }

    static List<String> normalizeStatic(List<String> tags) {
        if (tags == null || tags.isEmpty()) return Collections.emptyList();

        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenLower = new HashSet<>();

        for (String tag : tags) {
            if (tag == null) continue;
            String trimmed = tag.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > MAX_KEYWORD_LENGTH) {
                trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
            }

            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (seenLower.add(lower)) {
                seen.add(trimmed);
            }

            if (seen.size() >= MAX_KEYWORDS) break;
        }

        return new ArrayList<>(seen);
    }

    private boolean isFakeYouTubeId(String youtubeId) {
        return youtubeId == null || FAKE_YOUTUBE_ID.matcher(youtubeId).matches();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result DTO
    // ─────────────────────────────────────────────────────────────────────────

    public static class EnrichmentResult {
        public int total;
        public int enriched;
        public int skipped;
        public int errors;
        public List<String> errorMessages = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total", total);
            map.put("enriched", enriched);
            map.put("skipped", skipped);
            map.put("errors", errors);
            if (!errorMessages.isEmpty()) {
                map.put("errorMessages", errorMessages);
            }
            return map;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stop words (English, Arabic, Dutch)
    // ─────────────────────────────────────────────────────────────────────────

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            // English
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "it", "as", "be", "was", "are",
            "been", "has", "had", "have", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "can", "this", "that", "these",
            "those", "he", "she", "we", "they", "you", "me", "him", "her", "us",
            "them", "my", "your", "his", "its", "our", "their", "what", "which",
            "who", "whom", "how", "when", "where", "why", "if", "not", "no",
            "so", "up", "out", "all", "about", "more", "some", "than", "very",
            "just", "also", "into", "over", "such", "only", "own", "same",
            // Dutch (some overlap with English: in, is, op, van, wat)
            "de", "het", "een", "en", "van", "dat", "die", "op",
            "te", "er", "niet", "zijn", "voor", "met", "als", "maar", "om",
            "ook", "aan", "nog", "bij", "uit", "dan", "wat", "naar", "wel",
            "hun", "kan", "dit", "hij", "zij", "wij", "ik", "je", "ze",
            // Arabic common particles
            "\u0641\u064a", "\u0645\u0646", "\u0639\u0644\u0649", "\u0625\u0644\u0649",
            "\u0647\u0630\u0627", "\u0647\u0630\u0647", "\u0630\u0644\u0643",
            "\u0623\u0648", "\u062b\u0645", "\u0644\u0643\u0646"
    ));

    // ─────────────────────────────────────────────────────────────────────────
    // CATEGORY_TAGS: curated multilingual tags per category
    // Each category maps to tags in English, Arabic, and Dutch
    // ─────────────────────────────────────────────────────────────────────────

    static final Map<String, List<String>> CATEGORY_TAGS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();

        m.put("quran", List.of(
                // English
                "Quran", "Quran recitation", "holy Quran", "Quran reading", "tilawah",
                "reciter", "surah", "ayah", "juz", "mushaf",
                // Arabic
                "\u0627\u0644\u0642\u0631\u0622\u0646", "\u0627\u0644\u0642\u0631\u0622\u0646 \u0627\u0644\u0643\u0631\u064a\u0645",
                "\u062a\u0644\u0627\u0648\u0629", "\u062a\u0644\u0627\u0648\u0629 \u0627\u0644\u0642\u0631\u0622\u0646",
                "\u0642\u0631\u0627\u0621\u0629", "\u0633\u0648\u0631\u0629", "\u0622\u064a\u0629", "\u062c\u0632\u0621",
                "\u0645\u0635\u062d\u0641", "\u0642\u0627\u0631\u0626",
                // Dutch
                "Koran", "Koran recitatie", "heilige Koran", "Koran lezing",
                "Koranvers", "soera"
        ));

        m.put("quran-beginner", List.of(
                // English
                "beginner Quran", "learn Quran", "Quran basics", "how to read Quran",
                "Quran for beginners", "learn to recite", "Noorani Qaida", "basic recitation",
                // Arabic
                "\u062a\u0639\u0644\u0645 \u0627\u0644\u0642\u0631\u0622\u0646", "\u0627\u0644\u0642\u0631\u0622\u0646 \u0644\u0644\u0645\u0628\u062a\u062f\u0626\u064a\u0646",
                "\u0623\u0633\u0627\u0633\u064a\u0627\u062a \u0627\u0644\u0642\u0631\u0622\u0646",
                "\u0627\u0644\u0642\u0627\u0639\u062f\u0629 \u0627\u0644\u0646\u0648\u0631\u0627\u0646\u064a\u0629",
                // Dutch
                "Koran voor beginners", "leer Koran", "Koran basis",
                "Koran leren lezen", "basisrecitatie"
        ));

        m.put("tajweed", List.of(
                // English
                "tajweed", "Quran pronunciation", "makharij", "articulation points",
                "recitation rules", "noon sakin", "meem sakin", "madd", "ghunna",
                "idgham", "ikhfa", "iqlab", "izhar", "qalqalah",
                // Arabic
                "\u062a\u062c\u0648\u064a\u062f", "\u0645\u062e\u0627\u0631\u062c \u0627\u0644\u062d\u0631\u0648\u0641",
                "\u0623\u062d\u0643\u0627\u0645 \u0627\u0644\u062a\u0644\u0627\u0648\u0629",
                "\u0627\u0644\u0646\u0648\u0646 \u0627\u0644\u0633\u0627\u0643\u0646\u0629",
                "\u0627\u0644\u0645\u062f", "\u063a\u0646\u0629",
                "\u0625\u062f\u063a\u0627\u0645", "\u0625\u062e\u0641\u0627\u0621", "\u0625\u0642\u0644\u0627\u0628", "\u0625\u0638\u0647\u0627\u0631",
                "\u0642\u0644\u0642\u0644\u0629",
                // Dutch
                "tajweed", "Koran uitspraak", "uitspraakregels", "recitatie regels"
        ));

        m.put("memorization", List.of(
                // English
                "hifdh", "hifz", "Quran memorization", "memorize Quran", "revision",
                "muraja'ah", "hifdh tips", "memorization technique", "hifdh routine",
                // Arabic
                "\u062d\u0641\u0638", "\u062d\u0641\u0638 \u0627\u0644\u0642\u0631\u0622\u0646",
                "\u0645\u0631\u0627\u062c\u0639\u0629", "\u062a\u062d\u0641\u064a\u0638",
                "\u062d\u0627\u0641\u0638", "\u0637\u0631\u064a\u0642\u0629 \u0627\u0644\u062d\u0641\u0638",
                // Dutch
                "Koran memorisatie", "Koran uit het hoofd leren", "herhaling",
                "memorisatie techniek"
        ));

        m.put("hadith", List.of(
                // English
                "hadith", "prophetic traditions", "sunnah", "prophet Muhammad",
                "hadith collection", "narration", "isnad", "chain of narration",
                "Bukhari", "Muslim", "sahih", "hadith commentary",
                // Arabic
                "\u062d\u062f\u064a\u062b", "\u0623\u062d\u0627\u062f\u064a\u062b",
                "\u0627\u0644\u0633\u0646\u0629 \u0627\u0644\u0646\u0628\u0648\u064a\u0629",
                "\u0633\u0646\u0629", "\u0627\u0644\u0631\u0633\u0648\u0644",
                "\u0625\u0633\u0646\u0627\u062f", "\u0631\u0648\u0627\u064a\u0629",
                "\u0627\u0644\u0628\u062e\u0627\u0631\u064a", "\u0645\u0633\u0644\u0645",
                "\u0635\u062d\u064a\u062d", "\u0634\u0631\u062d \u0627\u0644\u062d\u062f\u064a\u062b",
                // Dutch
                "hadith", "profetische tradities", "soennah", "profeet Mohammed",
                "hadith verzameling", "overlevering"
        ));

        m.put("hadith-40", List.of(
                // English
                "forty hadith", "Nawawi", "Imam Nawawi", "40 hadith", "Nawawi collection",
                "An-Nawawi", "foundational hadith",
                // Arabic
                "\u0627\u0644\u0623\u0631\u0628\u0639\u0648\u0646 \u0627\u0644\u0646\u0648\u0648\u064a\u0629",
                "\u0627\u0644\u0625\u0645\u0627\u0645 \u0627\u0644\u0646\u0648\u0648\u064a",
                "\u0623\u0631\u0628\u0639\u0648\u0646 \u062d\u062f\u064a\u062b\u0627",
                "\u0634\u0631\u062d \u0627\u0644\u0623\u0631\u0628\u0639\u064a\u0646",
                // Dutch
                "veertig hadith", "Imam Nawawi", "Nawawi collectie", "40 hadith"
        ));

        m.put("seerah", List.of(
                // English
                "seerah", "prophetic biography", "life of Prophet Muhammad",
                "prophet's life", "companions", "sahaba", "Makkah", "Madinah",
                "hijrah", "migration", "battles", "Islamic biography",
                // Arabic
                "\u0627\u0644\u0633\u064a\u0631\u0629 \u0627\u0644\u0646\u0628\u0648\u064a\u0629",
                "\u0633\u064a\u0631\u0629", "\u062d\u064a\u0627\u0629 \u0627\u0644\u0646\u0628\u064a",
                "\u0627\u0644\u0635\u062d\u0627\u0628\u0629", "\u0645\u0643\u0629", "\u0627\u0644\u0645\u062f\u064a\u0646\u0629",
                "\u0627\u0644\u0647\u062c\u0631\u0629", "\u063a\u0632\u0648\u0627\u062a",
                "\u0633\u064a\u0631\u0629 \u0627\u0644\u0631\u0633\u0648\u0644",
                // Dutch
                "sierah", "profetische biografie", "leven van de Profeet",
                "metgezellen", "Mekka", "Medina", "hidjra"
        ));

        m.put("tafsir", List.of(
                // English
                "tafsir", "Quran explanation", "Quran commentary", "Quran exegesis",
                "Quran interpretation", "asbab al-nuzul", "reasons of revelation",
                "Ibn Kathir", "Quran study",
                // Arabic
                "\u062a\u0641\u0633\u064a\u0631", "\u062a\u0641\u0633\u064a\u0631 \u0627\u0644\u0642\u0631\u0622\u0646",
                "\u0634\u0631\u062d \u0627\u0644\u0642\u0631\u0622\u0646",
                "\u0623\u0633\u0628\u0627\u0628 \u0627\u0644\u0646\u0632\u0648\u0644",
                "\u0627\u0628\u0646 \u0643\u062b\u064a\u0631",
                "\u062f\u0631\u0627\u0633\u0629 \u0627\u0644\u0642\u0631\u0622\u0646",
                // Dutch
                "tafsir", "Koran uitleg", "Koran commentaar", "Koran exegese",
                "Koran interpretatie", "Koran studie"
        ));

        m.put("tafsir-bites", List.of(
                // English
                "quick tafsir", "short tafsir", "tafsir gems", "brief Quran explanation",
                "tafsir insights", "daily tafsir", "Quran reflection",
                // Arabic
                "\u062a\u0641\u0633\u064a\u0631 \u0645\u062e\u062a\u0635\u0631",
                "\u0644\u0637\u0627\u0626\u0641 \u062a\u0641\u0633\u064a\u0631\u064a\u0629",
                "\u062f\u0631\u0631 \u062a\u0641\u0633\u064a\u0631\u064a\u0629",
                "\u062a\u0623\u0645\u0644\u0627\u062a \u0642\u0631\u0622\u0646\u064a\u0629",
                // Dutch
                "korte tafsir", "tafsir inzichten", "dagelijkse tafsir",
                "Koran reflectie"
        ));

        m.put("aqeedah", List.of(
                // English
                "aqeedah", "creed", "Islamic belief", "faith", "theology",
                "tawheed", "monotheism", "pillars of faith", "names of Allah",
                "attributes of Allah", "angels", "divine decree", "qadr",
                // Arabic
                "\u0639\u0642\u064a\u062f\u0629", "\u0625\u064a\u0645\u0627\u0646",
                "\u062a\u0648\u062d\u064a\u062f", "\u0623\u0635\u0648\u0644 \u0627\u0644\u062f\u064a\u0646",
                "\u0623\u0631\u0643\u0627\u0646 \u0627\u0644\u0625\u064a\u0645\u0627\u0646",
                "\u0623\u0633\u0645\u0627\u0621 \u0627\u0644\u0644\u0647", "\u0635\u0641\u0627\u062a \u0627\u0644\u0644\u0647",
                "\u0627\u0644\u0645\u0644\u0627\u0626\u0643\u0629", "\u0627\u0644\u0642\u062f\u0631",
                // Dutch
                "aqidah", "geloofsleer", "islamitisch geloof", "theologie",
                "tawheed", "monotheisme", "zuilen van geloof",
                "namen van Allah", "engelen"
        ));

        m.put("fiqh", List.of(
                // English
                "fiqh", "Islamic jurisprudence", "Islamic law", "rulings",
                "halal", "haram", "worship", "prayer", "salah", "fasting",
                "sawm", "zakat", "hajj", "wudu", "purification", "fatwa",
                // Arabic
                "\u0641\u0642\u0647", "\u0623\u062d\u0643\u0627\u0645",
                "\u0634\u0631\u064a\u0639\u0629", "\u0641\u0642\u0647 \u0627\u0644\u0639\u0628\u0627\u062f\u0627\u062a",
                "\u062d\u0644\u0627\u0644", "\u062d\u0631\u0627\u0645",
                "\u0635\u0644\u0627\u0629", "\u0635\u064a\u0627\u0645", "\u0632\u0643\u0627\u0629",
                "\u062d\u062c", "\u0648\u0636\u0648\u0621", "\u0637\u0647\u0627\u0631\u0629", "\u0641\u062a\u0648\u0649",
                // Dutch
                "fiqh", "islamitische jurisprudentie", "islamitisch recht",
                "halal", "haram", "aanbidding", "gebed", "vasten",
                "zakat", "hadj", "woedoe", "reiniging"
        ));

        m.put("kids", List.of(
                // English
                "kids", "children", "Islamic education", "learn Islam", "kids Quran",
                "Islamic stories", "prophets stories", "Islamic cartoons",
                "kids learning", "Muslim kids", "children's education",
                // Arabic
                "\u0623\u0637\u0641\u0627\u0644", "\u062a\u0639\u0644\u064a\u0645 \u0625\u0633\u0644\u0627\u0645\u064a",
                "\u0642\u0635\u0635 \u0625\u0633\u0644\u0627\u0645\u064a\u0629",
                "\u0642\u0635\u0635 \u0627\u0644\u0623\u0646\u0628\u064a\u0627\u0621",
                "\u062a\u0639\u0644\u064a\u0645 \u0627\u0644\u0623\u0637\u0641\u0627\u0644",
                "\u0627\u0644\u0642\u0631\u0622\u0646 \u0644\u0644\u0623\u0637\u0641\u0627\u0644",
                // Dutch
                "kinderen", "islamitisch onderwijs", "leer Islam",
                "islamitische verhalen", "profetenverhalen",
                "kinderen leren", "moslim kinderen"
        ));

        m.put("youth", List.of(
                // English
                "youth", "teens", "Muslim youth", "Islamic guidance", "young Muslims",
                "campus life", "student life", "peer advice", "identity",
                // Arabic
                "\u0634\u0628\u0627\u0628", "\u0645\u0631\u0627\u0647\u0642\u064a\u0646",
                "\u0627\u0644\u0634\u0628\u0627\u0628 \u0627\u0644\u0645\u0633\u0644\u0645",
                "\u062a\u0648\u062c\u064a\u0647 \u0627\u0644\u0634\u0628\u0627\u0628",
                "\u0627\u0644\u0647\u0648\u064a\u0629 \u0627\u0644\u0625\u0633\u0644\u0627\u0645\u064a\u0629",
                // Dutch
                "jeugd", "tieners", "moslimjeugd", "islamitische begeleiding",
                "jonge moslims", "studentenleven"
        ));

        m.put("arabic", List.of(
                // English
                "Arabic language", "learn Arabic", "Arabic grammar", "Arabic vocabulary",
                "Quranic Arabic", "Arabic alphabet", "nahw", "sarf", "Arabic reading",
                "Arabic writing", "Arabic lessons", "Arabic for non-native",
                // Arabic
                "\u0627\u0644\u0644\u063a\u0629 \u0627\u0644\u0639\u0631\u0628\u064a\u0629",
                "\u062a\u0639\u0644\u0645 \u0627\u0644\u0639\u0631\u0628\u064a\u0629",
                "\u0646\u062d\u0648", "\u0635\u0631\u0641", "\u0642\u0648\u0627\u0639\u062f",
                "\u0645\u0641\u0631\u062f\u0627\u062a", "\u0627\u0644\u0623\u0628\u062c\u062f\u064a\u0629",
                "\u0627\u0644\u0639\u0631\u0628\u064a\u0629 \u0627\u0644\u0642\u0631\u0622\u0646\u064a\u0629",
                // Dutch
                "Arabische taal", "leer Arabisch", "Arabische grammatica",
                "Arabisch alfabet", "Arabische woordenschat", "Arabische lessen"
        ));

        m.put("nasheed", List.of(
                // English
                "nasheed", "Islamic song", "voice only", "acapella", "anasheed",
                "Islamic music", "vocal nasheed", "nasheed collection",
                "inspirational nasheed", "halal music",
                // Arabic
                "\u0646\u0634\u064a\u062f", "\u0623\u0646\u0627\u0634\u064a\u062f",
                "\u0623\u0646\u0627\u0634\u064a\u062f \u0625\u0633\u0644\u0627\u0645\u064a\u0629",
                "\u0628\u062f\u0648\u0646 \u0645\u0648\u0633\u064a\u0642\u0649",
                "\u0635\u0648\u062a \u0641\u0642\u0637",
                // Dutch
                "nasheed", "islamitisch lied", "a capella", "islamitische muziek",
                "vocale nasheed", "nasheed collectie"
        ));

        m.put("lifestyle", List.of(
                // English
                "Muslim lifestyle", "daily life", "Islamic living", "everyday Islam",
                "practical Islam", "Muslim family", "halal lifestyle",
                "Islamic advice", "community", "khutbah",
                // Arabic
                "\u062d\u064a\u0627\u0629 \u0627\u0644\u0645\u0633\u0644\u0645",
                "\u0627\u0644\u062d\u064a\u0627\u0629 \u0627\u0644\u064a\u0648\u0645\u064a\u0629",
                "\u0623\u0633\u0644\u0648\u0628 \u062d\u064a\u0627\u0629 \u0625\u0633\u0644\u0627\u0645\u064a",
                "\u0627\u0644\u0623\u0633\u0631\u0629 \u0627\u0644\u0645\u0633\u0644\u0645\u0629",
                "\u062e\u0637\u0628\u0629",
                // Dutch
                "moslim levensstijl", "dagelijks leven", "islamitisch leven",
                "praktische Islam", "moslim gezin", "halal levensstijl", "gemeenschap"
        ));

        m.put("history", List.of(
                // English
                "Islamic history", "Islamic civilization", "golden age",
                "Muslim scholars", "Islamic heritage", "Andalusia", "Ottoman",
                "Abbasid", "Umayyad", "caliphate", "Islamic architecture",
                // Arabic
                "\u062a\u0627\u0631\u064a\u062e \u0625\u0633\u0644\u0627\u0645\u064a",
                "\u062d\u0636\u0627\u0631\u0629 \u0625\u0633\u0644\u0627\u0645\u064a\u0629",
                "\u0627\u0644\u0639\u0635\u0631 \u0627\u0644\u0630\u0647\u0628\u064a",
                "\u0639\u0644\u0645\u0627\u0621 \u0627\u0644\u0645\u0633\u0644\u0645\u064a\u0646",
                "\u0627\u0644\u0623\u0646\u062f\u0644\u0633", "\u0627\u0644\u062e\u0644\u0627\u0641\u0629",
                "\u0627\u0644\u0639\u0645\u0627\u0631\u0629 \u0627\u0644\u0625\u0633\u0644\u0627\u0645\u064a\u0629",
                // Dutch
                "islamitische geschiedenis", "islamitische beschaving",
                "gouden eeuw", "moslim geleerden", "islamitisch erfgoed",
                "Andalusie", "Ottomaans", "kalifaat"
        ));

        m.put("revert-support", List.of(
                // English
                "new Muslim", "revert", "convert", "shahada", "Islam basics",
                "new to Islam", "embrace Islam", "Muslim convert", "first steps",
                "learning Islam", "shahada story", "guidance",
                // Arabic
                "\u0645\u0633\u0644\u0645 \u062c\u062f\u064a\u062f",
                "\u0625\u0633\u0644\u0627\u0645", "\u0634\u0647\u0627\u062f\u0629",
                "\u0623\u0633\u0627\u0633\u064a\u0627\u062a \u0627\u0644\u0625\u0633\u0644\u0627\u0645",
                "\u062f\u062e\u0648\u0644 \u0627\u0644\u0625\u0633\u0644\u0627\u0645",
                "\u062a\u0648\u062c\u064a\u0647",
                // Dutch
                "nieuwe moslim", "bekeerling", "shahada", "Islam basis",
                "nieuw in Islam", "Islam omarmen", "eerste stappen"
        ));

        m.put("wellness", List.of(
                // English
                "wellness", "mindfulness", "mental health", "spiritual wellbeing",
                "Islamic meditation", "stress relief", "self-care", "dhikr",
                "remembrance of Allah", "gratitude", "dua", "breathing",
                "Muslim wellness", "emotional health",
                // Arabic
                "\u0635\u062d\u0629 \u0646\u0641\u0633\u064a\u0629",
                "\u062a\u0623\u0645\u0644", "\u0631\u0641\u0627\u0647\u064a\u0629",
                "\u0627\u0644\u0635\u062d\u0629 \u0627\u0644\u0631\u0648\u062d\u064a\u0629",
                "\u0630\u0643\u0631", "\u0630\u0643\u0631 \u0627\u0644\u0644\u0647",
                "\u062f\u0639\u0627\u0621", "\u0634\u0643\u0631",
                "\u0627\u0644\u0639\u0646\u0627\u064a\u0629 \u0628\u0627\u0644\u0646\u0641\u0633",
                // Dutch
                "welzijn", "mindfulness", "geestelijke gezondheid",
                "spiritueel welzijn", "stressverlichting", "zelfzorg",
                "dhikr", "dankbaarheid", "dua"
        ));

        CATEGORY_TAGS = Collections.unmodifiableMap(m);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRANSLATION_DICT: Islamic terms with all language variants
    // When any variant is found in tags, all variants are added
    // ─────────────────────────────────────────────────────────────────────────

    static final Map<String, List<String>> TRANSLATION_DICT;

    static {
        Map<String, List<String>> d = new LinkedHashMap<>();

        // ── Quran-related ────────────────────────────────────────────────
        d.put("quran", List.of(
                "Quran", "Qur'an", "Qur'aan", "Quraan", "Qoran", "Kuran", "Qouran",
                "\u0627\u0644\u0642\u0631\u0622\u0646", "\u0642\u0631\u0622\u0646",
                "Koran", "Koraan"));
        d.put("recitation", List.of(
                "recitation", "tilawah", "tilaawah", "tilawa", "tilaawa",
                "\u062a\u0644\u0627\u0648\u0629",
                "recitatie"));
        d.put("surah", List.of(
                "surah", "sura", "soorah", "suurah",
                "\u0633\u0648\u0631\u0629",
                "soera", "soerah"));
        d.put("ayah", List.of(
                "ayah", "aya", "ayat", "aayah", "aayat",
                "\u0622\u064a\u0629", "\u0622\u064a\u0627\u062a",
                "vers", "Koranvers"));
        d.put("juz", List.of(
                "juz", "juz'", "joez", "juzz", "juzu",
                "\u062c\u0632\u0621",
                "djuz", "djoez"));
        d.put("tajweed", List.of(
                "tajweed", "tajwid", "tajwied", "tajwead", "tajuid", "tadsjwied",
                "\u062a\u062c\u0648\u064a\u062f"));
        d.put("hifdh", List.of(
                "hifdh", "hifz", "hifth", "hifdz", "hifzh", "hifaz",
                "memorization", "memorisation",
                "\u062d\u0641\u0638",
                "memorisatie"));
        d.put("tafsir", List.of(
                "tafsir", "tafseer", "tafsier", "tafsiir",
                "\u062a\u0641\u0633\u064a\u0631",
                "exegese", "uitleg"));
        d.put("mushaf", List.of(
                "mushaf", "mus-haf", "moshaf", "moeshaf", "mushaf",
                "\u0645\u0635\u062d\u0641",
                "Koranexemplaar"));
        d.put("qari", List.of(
                "qari", "qaari", "qari'", "qarie",
                "\u0642\u0627\u0631\u0626",
                "reciteerder"));

        // ── Hadith-related ───────────────────────────────────────────────
        d.put("hadith", List.of(
                "hadith", "hadeeth", "hadieth", "hadiths", "ahadith", "ahaadith",
                "\u062d\u062f\u064a\u062b", "\u0623\u062d\u0627\u062f\u064a\u062b",
                "overlevering", "hadies"));
        d.put("sunnah", List.of(
                "sunnah", "sunna", "sunneh", "soennah", "soenna", "soenneh",
                "\u0633\u0646\u0629", "\u0627\u0644\u0633\u0646\u0629 \u0627\u0644\u0646\u0628\u0648\u064a\u0629"));
        d.put("sahih", List.of(
                "sahih", "saheeh", "sahieh", "shahih",
                "\u0635\u062d\u064a\u062d",
                "authentic", "authentiek"));
        d.put("bukhari", List.of(
                "Bukhari", "Bukhary", "Bukhaari", "Boukhari",
                "\u0627\u0644\u0628\u062e\u0627\u0631\u064a",
                "Boechari", "Boekharie", "Boekhari"));
        d.put("nawawi", List.of(
                "Nawawi", "An-Nawawi", "Nawawie", "Nawawy", "An-Nawawie",
                "\u0627\u0644\u0646\u0648\u0648\u064a"));

        // ── Theology ─────────────────────────────────────────────────────
        d.put("aqeedah", List.of(
                "aqeedah", "aqeeda", "aqidah", "aqida", "akidah", "akieda",
                "aqiedah", "aqieda", "creed",
                "\u0639\u0642\u064a\u062f\u0629",
                "geloofsleer", "akieda"));
        d.put("tawheed", List.of(
                "tawheed", "tawhid", "tauheed", "tauhid", "tawhied", "tauhied", "taohied",
                "monotheism",
                "\u062a\u0648\u062d\u064a\u062f",
                "monotheisme"));
        d.put("iman", List.of(
                "iman", "imaan", "eemaan", "iemaan",
                "faith",
                "\u0625\u064a\u0645\u0627\u0646",
                "geloof"));
        d.put("qadr", List.of(
                "qadr", "qadar", "kadr", "kadar", "qadaa",
                "\u0627\u0644\u0642\u062f\u0631",
                "goddelijk besluit", "voorbeschikking"));

        // ── Fiqh ─────────────────────────────────────────────────────────
        d.put("fiqh", List.of(
                "fiqh", "fiqah", "fikeh", "fikh", "fiqih",
                "jurisprudence",
                "\u0641\u0642\u0647",
                "jurisprudentie"));
        d.put("salah", List.of(
                "salah", "salat", "salaah", "salaat", "sholat", "shalat", "solaat", "namaz",
                "prayer",
                "\u0635\u0644\u0627\u0629",
                "gebed", "salaat"));
        d.put("sawm", List.of(
                "sawm", "saum", "siyam", "siyaam", "saom", "sjaum",
                "fasting",
                "\u0635\u064a\u0627\u0645", "\u0635\u0648\u0645",
                "vasten"));
        d.put("zakat", List.of(
                "zakat", "zakaat", "zakaah", "zakah", "zakaaat",
                "\u0632\u0643\u0627\u0629",
                "zakaat"));
        d.put("hajj", List.of(
                "hajj", "haj", "hadj", "hadjdj", "hadsch",
                "pilgrimage",
                "\u062d\u062c",
                "hadj", "bedevaart"));
        d.put("umrah", List.of(
                "umrah", "umra", "oemrah", "oemra",
                "\u0639\u0645\u0631\u0629"));
        d.put("wudu", List.of(
                "wudu", "wudhu", "wudoo", "wudhoo", "wudoe",
                "ablution",
                "\u0648\u0636\u0648\u0621",
                "woedoe", "woedhoe"));
        d.put("halal", List.of(
                "halal", "halaal", "helal",
                "\u062d\u0644\u0627\u0644"));
        d.put("haram", List.of(
                "haram", "haraam",
                "\u062d\u0631\u0627\u0645"));
        d.put("fatwa", List.of(
                "fatwa", "fatwaa", "fatawa", "fatwah",
                "\u0641\u062a\u0648\u0649", "\u0641\u062a\u0627\u0648\u0649"));
        d.put("shariah", List.of(
                "shariah", "sharia", "shari'ah", "shariyah", "shariaa",
                "sjaria", "syariah",
                "Islamic law",
                "\u0634\u0631\u064a\u0639\u0629",
                "islamitisch recht"));

        // ── Seerah ───────────────────────────────────────────────────────
        d.put("seerah", List.of(
                "seerah", "sirah", "siera", "sierah", "siira", "seera",
                "biography",
                "\u0633\u064a\u0631\u0629",
                "biografie"));
        d.put("prophet", List.of(
                "Prophet", "prophet Muhammad", "Muhammed", "Mohammad", "Mohammed",
                "\u0627\u0644\u0646\u0628\u064a", "\u0627\u0644\u0631\u0633\u0648\u0644", "\u0645\u062d\u0645\u062f",
                "Profeet", "profeet Mohammed"));
        d.put("sahaba", List.of(
                "sahaba", "sahabah", "sahaaba", "sahaabah", "sahabi",
                "companions",
                "\u0627\u0644\u0635\u062d\u0627\u0628\u0629",
                "metgezellen"));
        d.put("hijrah", List.of(
                "hijrah", "hijra", "hegira", "hidjra", "hidjrah", "hidjre",
                "migration",
                "\u0627\u0644\u0647\u062c\u0631\u0629"));
        d.put("makkah", List.of(
                "Makkah", "Makka", "Mecca", "Mekkah",
                "\u0645\u0643\u0629",
                "Mekka"));
        d.put("madinah", List.of(
                "Madinah", "Madina", "Medina", "Medinah",
                "\u0627\u0644\u0645\u062f\u064a\u0646\u0629"));

        // ── General Islamic ──────────────────────────────────────────────
        d.put("islam", List.of(
                "Islam", "Islaam", "al-Islam",
                "\u0625\u0633\u0644\u0627\u0645", "\u0627\u0644\u0625\u0633\u0644\u0627\u0645"));
        d.put("muslim", List.of(
                "Muslim", "Muslimah", "Moslem",
                "\u0645\u0633\u0644\u0645",
                "moslim", "moslima"));
        d.put("allah", List.of(
                "Allah", "Allaah",
                "\u0627\u0644\u0644\u0647"));
        d.put("dua", List.of(
                "dua", "du'a", "du'aa", "duaa", "doea", "doe'a",
                "supplication",
                "\u062f\u0639\u0627\u0621",
                "smeekbede"));
        d.put("dhikr", List.of(
                "dhikr", "zikr", "thikr", "dikr", "zikir", "dzikr", "dikir",
                "remembrance",
                "\u0630\u0643\u0631",
                "gedenking"));
        d.put("khutbah", List.of(
                "khutbah", "khutba", "kutba", "kutbah", "khotba", "khotbah", "choetba",
                "sermon",
                "\u062e\u0637\u0628\u0629",
                "preek", "vrijdagpreek"));
        d.put("halaqah", List.of(
                "halaqah", "halaqa", "halaka", "halakah", "halaqa",
                "study circle",
                "\u062d\u0644\u0642\u0629",
                "studiekring"));
        d.put("dawah", List.of(
                "dawah", "da'wah", "daawa", "dakwah", "da'wa", "daawah",
                "invitation to Islam",
                "\u062f\u0639\u0648\u0629",
                "uitnodiging tot Islam"));
        d.put("nasheed", List.of(
                "nasheed", "nashied", "nashid", "naseed", "anasheed", "anashied", "anaasheed",
                "\u0646\u0634\u064a\u062f", "\u0623\u0646\u0627\u0634\u064a\u062f"));
        d.put("masjid", List.of(
                "masjid", "masjed", "masdjid", "mesjid",
                "mosque",
                "\u0645\u0633\u062c\u062f",
                "moskee"));
        d.put("ramadan", List.of(
                "Ramadan", "Ramadhaan", "Ramadhan", "Ramadaan", "Ramadaane", "Ramzan",
                "\u0631\u0645\u0636\u0627\u0646"));
        d.put("shahada", List.of(
                "shahada", "shahadah", "shahaadah", "sjahada",
                "\u0634\u0647\u0627\u062f\u0629",
                "geloofsbelijdenis"));
        d.put("eid", List.of(
                "Eid", "Eid al-Fitr", "Eid al-Adha", "Ied", "Ied al-Fitr", "Ied al-Adha",
                "\u0639\u064a\u062f", "\u0639\u064a\u062f \u0627\u0644\u0641\u0637\u0631", "\u0639\u064a\u062f \u0627\u0644\u0623\u0636\u062d\u0649"));
        d.put("ruqyah", List.of(
                "ruqyah", "ruqya", "roqya", "rukya", "roqia", "ruqia",
                "\u0631\u0642\u064a\u0629", "\u0627\u0644\u0631\u0642\u064a\u0629 \u0627\u0644\u0634\u0631\u0639\u064a\u0629"));
        d.put("adhkar", List.of(
                "adhkar", "athkar", "azkar", "adzkaar", "adkaar",
                "\u0623\u0630\u0643\u0627\u0631"));

        // ── Education ────────────────────────────────────────────────────
        d.put("arabic", List.of(
                "Arabic", "al-Arabiyyah",
                "\u0627\u0644\u0639\u0631\u0628\u064a\u0629",
                "Arabisch"));
        d.put("ilm", List.of(
                "ilm", "'ilm", "ielm",
                "knowledge",
                "\u0639\u0644\u0645",
                "kennis"));
        d.put("ulama", List.of(
                "ulama", "ulema", "oelama", "oelema",
                "scholars",
                "\u0639\u0644\u0645\u0627\u0621",
                "geleerden"));
        d.put("sheikh", List.of(
                "sheikh", "shaykh", "shaikh", "sheik", "sjiekh", "sjeikh",
                "\u0634\u064a\u062e"));
        d.put("ustadh", List.of(
                "ustadh", "ustaadh", "ustaz", "ustad",
                "\u0623\u0633\u062a\u0627\u0630"));

        // ── People & community ───────────────────────────────────────────
        d.put("revert", List.of(
                "revert", "convert", "new Muslim",
                "\u0645\u0633\u0644\u0645 \u062c\u062f\u064a\u062f",
                "bekeerling", "nieuwe moslim"));
        d.put("family", List.of(
                "family",
                "\u0623\u0633\u0631\u0629",
                "gezin", "familie"));
        d.put("children", List.of(
                "children", "kids",
                "\u0623\u0637\u0641\u0627\u0644",
                "kinderen"));
        d.put("youth", List.of(
                "youth", "teens",
                "\u0634\u0628\u0627\u0628",
                "jeugd", "tieners"));

        // ── History ──────────────────────────────────────────────────────
        d.put("andalusia", List.of(
                "Andalusia", "Al-Andalus",
                "\u0627\u0644\u0623\u0646\u062f\u0644\u0633",
                "Andalusie"));
        d.put("caliphate", List.of(
                "caliphate", "khilafah", "khilaafah", "khilafa", "chilafaat",
                "\u062e\u0644\u0627\u0641\u0629",
                "kalifaat"));
        d.put("golden_age", List.of(
                "golden age",
                "\u0627\u0644\u0639\u0635\u0631 \u0627\u0644\u0630\u0647\u0628\u064a",
                "gouden eeuw"));

        // ── Wellness & Spirituality ──────────────────────────────────────
        d.put("tawakkul", List.of(
                "tawakkul", "tawakul", "tawakkol", "tawakoel",
                "reliance on Allah",
                "\u062a\u0648\u0643\u0644",
                "vertrouwen op Allah"));
        d.put("sabr", List.of(
                "sabr", "sabar", "sabir",
                "patience",
                "\u0635\u0628\u0631",
                "geduld"));
        d.put("shukr", List.of(
                "shukr", "syukur", "shukur", "sjoekr",
                "gratitude",
                "\u0634\u0643\u0631",
                "dankbaarheid"));
        d.put("taqwa", List.of(
                "taqwa", "takwa", "taqwaa", "takwaa",
                "God-consciousness",
                "\u062a\u0642\u0648\u0649",
                "godvrezendheid"));
        d.put("tawbah", List.of(
                "tawbah", "tawba", "taubah", "tauba", "tobah", "toebah",
                "repentance",
                "\u062a\u0648\u0628\u0629",
                "berouw"));
        d.put("ikhlas", List.of(
                "ikhlas", "ikhlaas", "ichlaas", "ikhlash",
                "sincerity",
                "\u0625\u062e\u0644\u0627\u0635"));

        // ── Surahs commonly referenced ───────────────────────────────────
        d.put("al-fatiha", List.of(
                "Al-Fatiha", "Al-Fatihah", "Al-Faatiha", "Al-Fateha", "Al-Faticha",
                "\u0627\u0644\u0641\u0627\u062a\u062d\u0629",
                "De Opening"));
        d.put("al-baqarah", List.of(
                "Al-Baqarah", "Al-Baqara", "Al-Bakara", "Al-Bakarah",
                "\u0627\u0644\u0628\u0642\u0631\u0629",
                "De Koe"));
        d.put("al-imran", List.of(
                "Al-Imran", "Aal-Imran", "Ali-Imraan", "Aal-i-Imraan",
                "\u0622\u0644 \u0639\u0645\u0631\u0627\u0646"));
        d.put("yasin", List.of(
                "Yasin", "Ya-Sin", "Yaseen", "Yaasin", "Yaasien",
                "\u064a\u0633"));
        d.put("ar-rahman", List.of(
                "Ar-Rahman", "Ar-Rahmaan", "Al-Rahman", "Ar-Rahmaan",
                "\u0627\u0644\u0631\u062d\u0645\u0646",
                "De Barmhartige"));
        d.put("al-mulk", List.of(
                "Al-Mulk", "Al-Moelk", "Al-Mulk",
                "\u0627\u0644\u0645\u0644\u0643",
                "De Heerschappij"));
        d.put("al-kahf", List.of(
                "Al-Kahf", "Al-Kahfi", "Al-Kahaf",
                "\u0627\u0644\u0643\u0647\u0641",
                "De Grot"));
        d.put("al-ikhlas", List.of(
                "Al-Ikhlas", "Al-Ikhlaas", "Al-Ichlaas",
                "\u0627\u0644\u0625\u062e\u0644\u0627\u0635"));
        d.put("al-falaq", List.of(
                "Al-Falaq", "Al-Falak",
                "\u0627\u0644\u0641\u0644\u0642"));
        d.put("an-nas", List.of(
                "An-Nas", "An-Naas",
                "\u0627\u0644\u0646\u0627\u0633"));
        d.put("juz-amma", List.of(
                "Juz Amma", "Juz' Amma", "Juzz Amma", "Joez Amma",
                "\u062c\u0632\u0621 \u0639\u0645",
                "Djuz Amma", "Djoez Amma"));
        d.put("al-waaqiah", List.of(
                "Al-Waaqiah", "Al-Waqiah", "Al-Waaqi'ah", "Al-Vakiah",
                "\u0627\u0644\u0648\u0627\u0642\u0639\u0629"));

        // ── Common Arabic phrases in Islamic content ─────────────────────
        d.put("bismillah", List.of(
                "Bismillah", "Bismillaah", "Bism Allah", "Bismillahi",
                "\u0628\u0633\u0645 \u0627\u0644\u0644\u0647"));
        d.put("alhamdulillah", List.of(
                "Alhamdulillah", "Al-hamdulillah", "Alhamdulillaah",
                "\u0627\u0644\u062d\u0645\u062f \u0644\u0644\u0647"));
        d.put("subhanallah", List.of(
                "SubhanAllah", "Subhan Allah", "SubhaanAllaah",
                "\u0633\u0628\u062d\u0627\u0646 \u0627\u0644\u0644\u0647"));
        d.put("allahuakbar", List.of(
                "Allahu Akbar", "Allaahu Akbar", "AllahuAkbar",
                "\u0627\u0644\u0644\u0647 \u0623\u0643\u0628\u0631"));
        d.put("inshaallah", List.of(
                "InshaAllah", "Insha'Allah", "InshAllah", "Inshallah", "Insjallah",
                "\u0625\u0646 \u0634\u0627\u0621 \u0627\u0644\u0644\u0647"));

        TRANSLATION_DICT = Collections.unmodifiableMap(d);
    }
}
