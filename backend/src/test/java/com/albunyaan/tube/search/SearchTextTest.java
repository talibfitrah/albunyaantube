package com.albunyaan.tube.search;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.albunyaan.tube.search.SearchText.BASE;
import static com.albunyaan.tube.search.SearchText.tokens;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §4.3 analyzer cases and §4.4/§4.5 query shape. */
class SearchTextTest {

    @Test
    void apostrophesAreRemoved() {
        assertEquals(List.of("ksa", "quran", "tv"), tokens(BASE, "KSA Qur’an TV"));
        assertEquals(List.of("the", "holy", "quran"), tokens(BASE, "The Holy Qur'an"));
    }

    @Test
    void uthmaniMarksAndTashkeelAreRemoved() {
        // ٱلرَّحْمَٰن -> الرحمن
        assertEquals(List.of("الرحمن"),
                tokens(BASE, "ٱلرَّحْمَٰن"));
    }

    @Test
    void tatweelIsRemoved() {
        // رمـــضان -> رمضان
        assertEquals(List.of("رمضان"),
                tokens(BASE, "رمـــضان"));
    }

    @Test
    void underscoreSplitsWords() {
        assertEquals(List.of("learn", "arabic", "تعلم", "العربيه"), tokens(BASE, "#learn_arabic #تعلم_العربية"));
    }

    @Test
    void hamzaAlefYaAndTaMarbutaFoldTogether() {
        assertEquals(tokens(BASE, "الاصول العقيده مصطفي"), tokens(BASE, "الأصول العقيدة مصطفى"));
    }

    @Test
    void latinAccentsFold() {
        assertEquals(List.of("ideeen", "voor", "ramadan"), tokens(BASE, "Ideeën voor Ramadan"));
    }

    @Test
    void stemDropsTheArabicArticleAndEnglishPlurals() {
        assertEquals(SearchText.stem("عقيدة"), SearchText.stem("العقيدة"));
        assertEquals("lecture", SearchText.stem("lectures"));
    }

    @Test
    void conceptMapFindsAGroupThroughAnyVariant() {
        assertTrue(SearchText.concepts("gebed").contains(List.of("prayer")));
        assertTrue(SearchText.concepts("الصلاة").contains(List.of("salah")));
        assertTrue(SearchText.concepts("xqzvbnm").isEmpty());
    }

    @Test
    void stopwordsAreOptionalAndEveryOtherTokenIsRequired() {
        BooleanQuery q = (BooleanQuery) SearchText.tier(1, List.of("story", "of", "yusuf"));
        assertEquals(List.of(BooleanClause.Occur.MUST, BooleanClause.Occur.SHOULD, BooleanClause.Occur.MUST),
                q.clauses().stream().map(BooleanClause::getOccur).toList());
        BooleanQuery onlyStopwords = (BooleanQuery) SearchText.tier(1, List.of("of", "the"));
        assertEquals(1, onlyStopwords.getMinimumNumberShouldMatch());
    }

    @Test
    void theRelatedTierMakesEveryTokenOptional() {
        BooleanQuery q = (BooleanQuery) SearchText.tier(3, List.of("arabic", "learn"));
        assertTrue(q.clauses().stream().allMatch(c -> c.getOccur() == BooleanClause.Occur.SHOULD));
        assertEquals(1, q.getMinimumNumberShouldMatch());
    }
}
