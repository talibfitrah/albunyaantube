package com.albunyaan.tube.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parser for YouTube data JSON file.
 *
 * JSON Structure:
 * [
 *   { "UCxxx": "Channel Name|Global", ... },  // Channels (start with UC)
 *   { "PLxxx": "Playlist Title|Global", ... }, // Playlists (start with PL)
 *   { "videoId": "Video Title|Global", ... }   // Videos (11 chars, various patterns)
 * ]
 */
public class YouTubeDataParser {

    private static final Logger log = LoggerFactory.getLogger(YouTubeDataParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static class ParsedYouTubeData {
        public final List<YouTubeChannel> channels;
        public final List<YouTubePlaylist> playlists;
        public final List<YouTubeVideo> videos;

        public ParsedYouTubeData(List<YouTubeChannel> channels, List<YouTubePlaylist> playlists, List<YouTubeVideo> videos) {
            this.channels = channels;
            this.playlists = playlists;
            this.videos = videos;
        }
    }

    public static class YouTubeChannel {
        public final String youtubeId;
        public final String name;
        public final String tag;

        public YouTubeChannel(String youtubeId, String name, String tag) {
            this.youtubeId = youtubeId;
            this.name = name;
            this.tag = tag;
        }
    }

    public static class YouTubePlaylist {
        public final String playlistId;
        public final String title;
        public final String tag;

        public YouTubePlaylist(String playlistId, String title, String tag) {
            this.playlistId = playlistId;
            this.title = title;
            this.tag = tag;
        }
    }

    public static class YouTubeVideo {
        public final String videoId;
        public final String title;
        public final String tag;

        public YouTubeVideo(String videoId, String title, String tag) {
            this.videoId = videoId;
            this.title = title;
            this.tag = tag;
        }
    }

    /**
     * Parse youtube-data.json from classpath
     */
    public static ParsedYouTubeData parseFromResource() throws IOException {
        ClassPathResource resource = new ClassPathResource("youtube-data.json");
        List<Map<String, String>> jsonArray = mapper.readValue(
            resource.getInputStream(),
            new TypeReference<List<Map<String, String>>>() {}
        );
        return parse(jsonArray);
    }

    /**
     * Parse YouTube data from JSON structure
     */
    public static ParsedYouTubeData parse(List<Map<String, String>> jsonArray) {
        List<YouTubeChannel> channels = new ArrayList<>();
        List<YouTubePlaylist> playlists = new ArrayList<>();
        List<YouTubeVideo> videos = new ArrayList<>();

        for (Map<String, String> map : jsonArray) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                String id = entry.getKey();
                String value = entry.getValue();

                // Parse title and tag (format: "Title|Tag")
                String[] parts = value.split("\\|");
                String title = parts[0].trim();
                String tag = parts.length > 1 ? parts[1].trim() : "General";

                // Identify type by ID pattern
                if (id.startsWith("UC")) {
                    // Channel ID (starts with UC)
                    channels.add(new YouTubeChannel(id, title, tag));
                    log.debug("Parsed channel: {} - {}", id, title);
                } else if (id.startsWith("PL") || id.startsWith("UU") || id.startsWith("OL")) {
                    // Playlist ID (starts with PL, UU, or OL)
                    playlists.add(new YouTubePlaylist(id, title, tag));
                    log.debug("Parsed playlist: {} - {}", id, title);
                } else if (id.length() == 11 || id.length() == 12) {
                    // Video ID (typically 11 characters, sometimes 12)
                    videos.add(new YouTubeVideo(id, title, tag));
                    log.debug("Parsed video: {} - {}", id, title);
                } else {
                    log.warn("Unknown ID pattern: {} - {}", id, title);
                }
            }
        }

        log.info("Parsed YouTube data: {} channels, {} playlists, {} videos",
                channels.size(), playlists.size(), videos.size());

        return new ParsedYouTubeData(channels, playlists, videos);
    }

    /**
     * Get unique tags from parsed data
     */
    public static Set<String> extractTags(ParsedYouTubeData data) {
        Set<String> tags = new HashSet<>();
        data.channels.forEach(c -> tags.add(c.tag));
        data.playlists.forEach(p -> tags.add(p.tag));
        data.videos.forEach(v -> tags.add(v.tag));
        return tags;
    }

    /**
     * Group channels by tag
     */
    public static Map<String, List<YouTubeChannel>> groupChannelsByTag(ParsedYouTubeData data) {
        return data.channels.stream()
                .collect(Collectors.groupingBy(c -> c.tag));
    }

    /**
     * Group playlists by tag
     */
    public static Map<String, List<YouTubePlaylist>> groupPlaylistsByTag(ParsedYouTubeData data) {
        return data.playlists.stream()
                .collect(Collectors.groupingBy(p -> p.tag));
    }

    /**
     * Group videos by tag
     */
    public static Map<String, List<YouTubeVideo>> groupVideosByTag(ParsedYouTubeData data) {
        return data.videos.stream()
                .collect(Collectors.groupingBy(v -> v.tag));
    }
}

