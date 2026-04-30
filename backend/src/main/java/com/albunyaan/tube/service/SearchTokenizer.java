package com.albunyaan.tube.service;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SearchTokenizer {

    /**
     * Normalize Arabic text for consistent search token generation.
     * - Strips tashkeel (diacritics): U+064B–U+065F
     * - Normalizes alef variants (أ إ آ ٱ) → ا
     * - Normalizes teh marbuta (ة) → heh (ه)
     */
    public String normalizeArabic(String text) {
        if (text == null) return "";
        // Strip tashkeel and other diacritics
        String r = text.replaceAll("[\\u064B-\\u065F]", "");
        // Normalize alef variants
        r = r.replaceAll("[\\u0623\\u0625\\u0622\\u0671]", "ا");
        // Normalize teh marbuta → heh
        r = r.replaceAll("ة", "ه");
        return r;
    }

    /**
     * Tokenize a stream title (and optionally channel name) into search tokens.
     * Each word produces up to two tokens: the original lowercase form and, if it
     * contains Arabic characters, its normalized form.
     */
    public List<String> tokenize(String title, String channelName) {
        Set<String> tokens = new LinkedHashSet<>();
        if (title != null) addWordTokens(tokens, title);
        if (channelName != null) addWordTokens(tokens, channelName);
        return new ArrayList<>(tokens);
    }

    private void addWordTokens(Set<String> tokens, String text) {
        // Split on whitespace, punctuation, and common Arabic punctuation
        for (String word : text.split("[\\s\\p{Punct}\\u060C\\u061B\\u061F\\u00BB\\u00AB]+")) {
            if (word.length() < 3) continue;
            String lower = word.toLowerCase(Locale.ROOT);
            tokens.add(lower);
            String normalized = normalizeArabic(lower);
            if (!normalized.equals(lower) && !normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
    }
}
