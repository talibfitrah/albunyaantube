package com.albunyaan.tube.registry;

import com.albunyaan.tube.registry.dto.CategoryTagDto;
import com.albunyaan.tube.registry.dto.ChannelSummaryDto;
import com.albunyaan.tube.registry.dto.CursorPage;
import com.albunyaan.tube.registry.dto.CursorPageInfo;
import com.albunyaan.tube.registry.dto.PlaylistSummaryDto;
import com.albunyaan.tube.registry.dto.VideoSummaryDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistryQueryService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private static final CategoryTagDto QURAN = new CategoryTagDto("quran", "Quran");
    private static final CategoryTagDto SEERAH = new CategoryTagDto("seerah", "Seerah");
    private static final CategoryTagDto KIDS = new CategoryTagDto("kids", "Kids");
    private static final CategoryTagDto LECTURES = new CategoryTagDto("lectures", "Lectures");

    private static final ChannelSummaryDto YAQEEN_CHANNEL = new ChannelSummaryDto(
        "chan-yaqeen",
        "UC9jKNK9E8bZMXsFQ0iCFJMw",
        "Yaqeen Institute",
        "https://yt3.googleusercontent.com/ytc/AIf8zZS0Q8Wn1x-yaqeen=s88-c-k-c0x00ffffff-no-rj",
        325_000L,
        List.of(LECTURES)
    );

    private static final ChannelSummaryDto BAYYINAH_CHANNEL = new ChannelSummaryDto(
        "chan-bayyina",
        "UC3o5hQk_lJ0Y9rJ1Z2J6Rmg",
        "Bayyinah",
        "https://yt3.googleusercontent.com/ytc/AIf8zZSbayyinah=s88-c-k-c0x00ffffff-no-rj",
        2_040_000L,
        List.of(QURAN, LECTURES)
    );

    private static final ChannelSummaryDto MIRACLE_KIDS_CHANNEL = new ChannelSummaryDto(
        "chan-miracle-kids",
        "UCx4fVJQ4J5BVQxZsR3aohKg",
        "Miracle Kids",
        "https://yt3.googleusercontent.com/ytc/AIf8zZSmiraclekids=s88-c-k-c0x00ffffff-no-rj",
        185_000L,
        List.of(KIDS)
    );

    private static final ChannelSummaryDto FIRDOWS_CHANNEL = new ChannelSummaryDto(
        "chan-firdaws",
        "UC1l1FirdawszzU8uY2",
        "Firdaws",
        "https://yt3.googleusercontent.com/ytc/AIf8zZSfirdaws=s88-c-k-c0x00ffffff-no-rj",
        92_500L,
        List.of(SEERAH, LECTURES)
    );

    private static final ChannelSummaryDto ALHIKMAH_CHANNEL = new ChannelSummaryDto(
        "chan-alhikmah",
        "UC5alHikmah837Lmqg",
        "Al-Hikmah",
        "https://yt3.googleusercontent.com/ytc/AIf8zZSalhikmah=s88-c-k-c0x00ffffff-no-rj",
        58_200L,
        List.of(QURAN)
    );

    private static final List<ChannelSummaryDto> CHANNELS = List.of(
        YAQEEN_CHANNEL,
        BAYYINAH_CHANNEL,
        MIRACLE_KIDS_CHANNEL,
        FIRDOWS_CHANNEL,
        ALHIKMAH_CHANNEL,
        new ChannelSummaryDto(
            "chan-nasirj",
            "UC8NasirJLectures",
            "Nasir J Lectures",
            "https://yt3.googleusercontent.com/ytc/AIf8zZSnasirj=s88-c-k-c0x00ffffff-no-rj",
            143_000L,
            List.of(LECTURES)
        ),
        new ChannelSummaryDto(
            "chan-qalam",
            "UC1QalamInstitute",
            "Qalam Institute",
            "https://yt3.googleusercontent.com/ytc/AIf8zZSqalam=s88-c-k-c0x00ffffff-no-rj",
            420_000L,
            List.of(SEERAH, LECTURES)
        ),
        new ChannelSummaryDto(
            "chan-mina",
            "UC7MinasGarden",
            "Mina's Garden",
            "https://yt3.googleusercontent.com/ytc/AIf8zSminasgarden=s88-c-k-c0x00ffffff-no-rj",
            78_400L,
            List.of(KIDS, SEERAH)
        )
    );

    private static final List<PlaylistSummaryDto> PLAYLISTS = List.of(
        new PlaylistSummaryDto(
            "pl-yaqeen-doubt",
            "PLyaqeen001",
            "Doubts & Faith",
            "https://img.youtube.com/vi/yaqeen01/hqdefault.jpg",
            18,
            YAQEEN_CHANNEL,
            List.of(LECTURES),
            Boolean.TRUE
        ),
        new PlaylistSummaryDto(
            "pl-bayyina-grammar",
            "PLbayyinah001",
            "Quranic Arabic Grammar",
            "https://img.youtube.com/vi/bayyina01/hqdefault.jpg",
            24,
            BAYYINAH_CHANNEL,
            List.of(QURAN),
            Boolean.TRUE
        ),
        new PlaylistSummaryDto(
            "pl-miracle-bedtime",
            "PLmiracleKids001",
            "Bedtime Stories",
            "https://img.youtube.com/vi/miracle01/hqdefault.jpg",
            12,
            MIRACLE_KIDS_CHANNEL,
            List.of(KIDS),
            Boolean.FALSE
        ),
        new PlaylistSummaryDto(
            "pl-firdaws-seerah",
            "PLfirdaws001",
            "Seerah Series",
            "https://img.youtube.com/vi/firdaws01/hqdefault.jpg",
            30,
            FIRDOWS_CHANNEL,
            List.of(SEERAH),
            Boolean.TRUE
        ),
        new PlaylistSummaryDto(
            "pl-alhikmah-recitation",
            "PLhikmah001",
            "Quran Recitations",
            "https://img.youtube.com/vi/hikmah01/hqdefault.jpg",
            40,
            ALHIKMAH_CHANNEL,
            List.of(QURAN),
            Boolean.TRUE
        ),
        new PlaylistSummaryDto(
            "pl-qalam-ramadan",
            "PLqalam001",
            "Ramadan Reflections",
            "https://img.youtube.com/vi/qalam01/hqdefault.jpg",
            10,
            new ChannelSummaryDto(
                "chan-qalam",
                "UC1QalamInstitute",
                "Qalam Institute",
                "https://yt3.googleusercontent.com/ytc/AIf8zZSqalam=s88-c-k-c0x00ffffff-no-rj",
                420_000L,
                List.of(SEERAH, LECTURES)
            ),
            List.of(SEERAH),
            Boolean.TRUE
        )
    );

    private static final List<VideoSummaryDto> VIDEOS;

    static {
        var videos = new ArrayList<VideoSummaryDto>();
        videos.add(new VideoSummaryDto(
            "vid-yaqeen-01",
            "yaqeen01",
            "Dealing with Doubt",
            "https://img.youtube.com/vi/yaqeen01/hqdefault.jpg",
            1_200,
            Instant.parse("2024-04-12T10:00:00Z"),
            125_000L,
            YAQEEN_CHANNEL,
            List.of(LECTURES),
            null,
            null
        ));
        videos.add(new VideoSummaryDto(
            "vid-bayyina-01",
            "bayyina01",
            "Arabic Grammar Lesson 1",
            "https://img.youtube.com/vi/bayyina01/hqdefault.jpg",
            900,
            Instant.parse("2024-03-05T08:30:00Z"),
            210_000L,
            BAYYINAH_CHANNEL,
            List.of(QURAN),
            null,
            null
        ));
        videos.add(new VideoSummaryDto(
            "vid-miracle-01",
            "miracle01",
            "Prophet Stories for Kids",
            "https://img.youtube.com/vi/miracle01/hqdefault.jpg",
            480,
            Instant.parse("2024-04-20T18:15:00Z"),
            45_000L,
            MIRACLE_KIDS_CHANNEL,
            List.of(KIDS, SEERAH),
            Boolean.TRUE,
            Boolean.FALSE
        ));
        videos.add(new VideoSummaryDto(
            "vid-firdaws-01",
            "firdaws01",
            "Early Life of the Prophet ﷺ",
            "https://img.youtube.com/vi/firdaws01/hqdefault.jpg",
            1_560,
            Instant.parse("2024-02-14T14:00:00Z"),
            62_000L,
            FIRDOWS_CHANNEL,
            List.of(SEERAH),
            null,
            null
        ));
        videos.add(new VideoSummaryDto(
            "vid-hikmah-01",
            "hikmah01",
            "Surah Yasin Recitation",
            "https://img.youtube.com/vi/hikmah01/hqdefault.jpg",
            1_020,
            Instant.parse("2024-04-01T04:45:00Z"),
            31_000L,
            ALHIKMAH_CHANNEL,
            List.of(QURAN),
            null,
            null
        ));
        videos.add(new VideoSummaryDto(
            "vid-qalam-01",
            "qalam01",
            "Ramadan Day 10 Reminder",
            "https://img.youtube.com/vi/qalam01/hqdefault.jpg",
            780,
            Instant.parse("2024-03-20T21:00:00Z"),
            54_000L,
            new ChannelSummaryDto(
                "chan-qalam",
                "UC1QalamInstitute",
                "Qalam Institute",
                "https://yt3.googleusercontent.com/ytc/AIf8zZSqalam=s88-c-k-c0x00ffffff-no-rj",
                420_000L,
                List.of(SEERAH, LECTURES)
            ),
            List.of(SEERAH, LECTURES),
            null,
            null
        ));
        VIDEOS = List.copyOf(videos);
    }

    public CursorPage<ChannelSummaryDto> listChannels(String cursor, int requestedLimit, String categoryId) {
        var limit = normalizeLimit(requestedLimit);
        var filtered = filterByCategory(CHANNELS, categoryId);
        return paginate(filtered, cursor, limit);
    }

    public CursorPage<PlaylistSummaryDto> listPlaylists(String cursor, int requestedLimit, String categoryId) {
        var limit = normalizeLimit(requestedLimit);
        var filtered = filterByCategory(PLAYLISTS, categoryId);
        return paginate(filtered, cursor, limit);
    }

    public CursorPage<VideoSummaryDto> listVideos(
        String cursor,
        int requestedLimit,
        String categoryId,
        String query,
        String length,
        String date,
        String sort
    ) {
        var limit = normalizeLimit(requestedLimit);
        var filtered = filterVideos(categoryId, query, length, date, sort);
        return paginate(filtered, cursor, limit);
    }

    private <T> CursorPage<T> paginate(List<T> source, String cursor, int limit) {
        var offset = normalizeCursor(cursor, source.size());
        var end = Math.min(offset + limit, source.size());
        var items = source.subList(offset, end);
        var hasNext = end < source.size();
        var pageInfo = new CursorPageInfo(
            offset > 0 ? String.valueOf(offset) : null,
            hasNext ? String.valueOf(end) : null,
            hasNext,
            limit
        );
        return new CursorPage<>(List.copyOf(items), pageInfo);
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit < MIN_LIMIT || requestedLimit > MAX_LIMIT) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Limit must be between %d and %d".formatted(MIN_LIMIT, MAX_LIMIT)
            );
        }
        return requestedLimit;
    }

    private int normalizeCursor(String cursor, int size) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            var value = Integer.parseInt(cursor);
            if (value < 0 || value > size) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor is out of range");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor must be a non-negative integer");
        }
    }

    private <T> List<T> filterByCategory(List<T> source, String categoryId) {
        if (!StringUtils.hasText(categoryId)) {
            return source;
        }
        var normalized = categoryId.trim().toLowerCase(Locale.ROOT);
        return source
            .stream()
            .filter(item -> {
                if (item instanceof ChannelSummaryDto channel) {
                    return channel.categories().stream().anyMatch(tag -> tag.id().equals(normalized));
                }
                if (item instanceof PlaylistSummaryDto playlist) {
                    return playlist.categories().stream().anyMatch(tag -> tag.id().equals(normalized));
                }
                if (item instanceof VideoSummaryDto video) {
                    return video.categories().stream().anyMatch(tag -> tag.id().equals(normalized));
                }
                return false;
            })
            .collect(Collectors.toUnmodifiableList());
    }

    private List<VideoSummaryDto> filterVideos(String categoryId, String query, String length, String date, String sort) {
        var stream = VIDEOS.stream();

        if (StringUtils.hasText(categoryId)) {
            var normalized = categoryId.trim().toLowerCase(Locale.ROOT);
            stream = stream.filter(video -> video.categories().stream().anyMatch(tag -> tag.id().equals(normalized)));
        }

        if (StringUtils.hasText(query)) {
            var normalized = query.trim().toLowerCase(Locale.ROOT);
            stream = stream.filter(video -> video.title().toLowerCase(Locale.ROOT).contains(normalized));
        }

        if (StringUtils.hasText(length)) {
            var normalized = length.trim().toUpperCase(Locale.ROOT);
            stream = stream.filter(video -> switch (normalized) {
                case "SHORT" -> video.durationSeconds() < 300;
                case "MEDIUM" -> video.durationSeconds() >= 300 && video.durationSeconds() < 1_200;
                case "LONG" -> video.durationSeconds() >= 1_200;
                default -> true;
            });
        }

        if (StringUtils.hasText(date)) {
            var normalized = date.trim().toUpperCase(Locale.ROOT);
            var now = Instant.parse("2024-05-01T00:00:00Z");
            stream = stream.filter(video -> {
                var published = video.publishedAt();
                return switch (normalized) {
                    case "LAST_24_HOURS" -> published.isAfter(now.minusSeconds(86_400));
                    case "LAST_7_DAYS" -> published.isAfter(now.minusSeconds(7 * 86_400L));
                    case "LAST_30_DAYS" -> published.isAfter(now.minusSeconds(30 * 86_400L));
                    case "ANYTIME" -> true;
                    default -> true;
                };
            });
        }

        var videos = stream.collect(Collectors.toCollection(ArrayList::new));

        if (StringUtils.hasText(sort)) {
            var normalized = sort.trim().toUpperCase(Locale.ROOT);
            if (Objects.equals(normalized, "POPULAR")) {
                videos.sort(Comparator.comparingLong(VideoSummaryDto::viewCount).reversed());
            } else if (Objects.equals(normalized, "RECENT")) {
                videos.sort(Comparator.comparing(VideoSummaryDto::publishedAt).reversed());
            }
        } else {
            videos.sort(Comparator.comparing(VideoSummaryDto::publishedAt).reversed());
        }

        return List.copyOf(videos);
    }
}

