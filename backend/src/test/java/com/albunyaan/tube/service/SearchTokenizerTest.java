package com.albunyaan.tube.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SearchTokenizerTest {

    private SearchTokenizer tokenizer;

    @BeforeEach
    void setUp() { tokenizer = new SearchTokenizer(); }

    @Test
    void normalizeArabic_stripsHarakat() {
        // الصَّلاة (with fatha + shadda) → الصلاه (clean + ة→ه)
        assertEquals("الصلاه", tokenizer.normalizeArabic("الصَّلاة"));
    }

    @Test
    void normalizeArabic_normalizesAlefVariants() {
        // إيمان (alef with kasra below) → ايمان
        assertEquals("ايمان", tokenizer.normalizeArabic("إيمان"));
        // آية (alef with madda) → ايه
        assertEquals("ايه", tokenizer.normalizeArabic("آية"));
    }

    @Test
    void normalizeArabic_normalizesAlefWasla() {
        // ٱلله → الله
        assertEquals("الله", tokenizer.normalizeArabic("ٱلله"));
    }

    @Test
    void tokenize_splitsEnglishTitle() {
        List<String> tokens = tokenizer.tokenize("Quran Recitation", null);
        assertTrue(tokens.contains("quran"));
        assertTrue(tokens.contains("recitation"));
    }

    @Test
    void tokenize_skipsShortWords() {
        List<String> tokens = tokenizer.tokenize("a the in Quran", null);
        assertFalse(tokens.contains("a"));
        assertFalse(tokens.contains("in"));
        assertTrue(tokens.contains("the"));  // 3 chars — kept
        assertTrue(tokens.contains("quran"));
    }

    @Test
    void tokenize_addsNormalizedArabicForm() {
        List<String> tokens = tokenizer.tokenize("صلاة الفجر", null);
        // Original forms
        assertTrue(tokens.contains("صلاة"));
        assertTrue(tokens.contains("الفجر"));
        // Normalized: ة → ه
        assertTrue(tokens.contains("صلاه"));
    }

    @Test
    void tokenize_includesChannelNameTokens() {
        List<String> tokens = tokenizer.tokenize("Lecture 1", "Sheikh Ali");
        assertTrue(tokens.contains("lecture"));
        assertTrue(tokens.contains("sheikh"));
        assertTrue(tokens.contains("ali"));
    }

    @Test
    void tokenize_deduplicates() {
        List<String> tokens = tokenizer.tokenize("Ali Ali", null);
        assertEquals(1, tokens.stream().filter("ali"::equals).count());
    }
}
