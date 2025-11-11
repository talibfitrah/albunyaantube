package com.albunyaan.tube.util;

/**
 * Utility class for YouTube URL operations
 */
public final class YouTubeUrlUtils {

    private YouTubeUrlUtils() {
        // Prevent instantiation
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Extract YouTube ID from URL
     * <p>
     * Examples:
     * <ul>
     *   <li>"https://www.youtube.com/channel/UCxxxxxx" -> "UCxxxxxx"</li>
     *   <li>"https://www.youtube.com/watch?v=abc123" -> "abc123"</li>
     *   <li>"https://www.youtube.com/playlist?list=PLxxxxxx" -> "PLxxxxxx"</li>
     *   <li>"https://youtu.be/abc123" -> "abc123"</li>
     * </ul>
     *
     * @param url YouTube URL to extract ID from
     * @return Extracted YouTube ID, or null if URL is null, empty, or extraction results in empty string
     */
    public static String extractYouTubeId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        // Check for query parameters first (video or playlist URLs)
        if (url.contains("?")) {
            String queryString = url.substring(url.indexOf("?") + 1);
            String[] params = queryString.split("&");

            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0];
                    String value = keyValue[1];

                    // Check for video ID parameter
                    if ("v".equals(key) && !value.isEmpty()) {
                        return value;
                    }

                    // Check for playlist ID parameter
                    if ("list".equals(key) && !value.isEmpty()) {
                        return value;
                    }
                }
            }
        }

        // Handle youtu.be short URLs (e.g., https://youtu.be/abc123)
        if (url.contains("youtu.be/")) {
            String[] parts = url.split("youtu.be/");
            if (parts.length > 1) {
                String idPart = parts[1].split("\\?")[0]; // Remove query params if present
                if (!idPart.isEmpty()) {
                    return idPart;
                }
            }
        }

        // Fall back to path-based extraction for channel/user URLs
        String[] parts = url.split("/");
        String lastPart = parts[parts.length - 1];

        // Remove query parameters if present
        String extractedId = lastPart.split("\\?")[0];

        // Return null if extraction resulted in empty string
        if (extractedId.isEmpty()) {
            return null;
        }

        return extractedId;
    }
}
