package com.albunyaan.tube.search;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.albunyaan.tube.search.SearchText.BASE;
import static com.albunyaan.tube.search.SearchText.tokens;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void quranicMarksFromU06D6ToU06EDNormaliseLikeThePlainSpelling() {
        // Cubic P3 (1.0.4): the map stripped only U+0653-065F, U+0670 and U+0671, so these stayed inside the words.
        assertEquals(tokens(BASE, "الحمد"), tokens(BASE, "ٱلۡحَمۡدُ")); // U+06E1 Uthmani sukun
        assertEquals(tokens(BASE, "انه به"), tokens(BASE, "إِنَّهُۥ بِهِۦ")); // U+06E5/U+06E6: a long vowel the plain spelling omits
        assertEquals(tokens(BASE, "الرحيم ١"), tokens(BASE, "ٱلرَّحِيمِ۝١")); // U+06DD end of ayah ends the word
        for (char c = '\u06D6'; c <= '\u06ED'; c++) {
            // Marks and the small waw/yeh vanish; the end-of-ayah sign and the two symbols (U+06DE, U+06E9) split.
            String plain = c == '\u06DD' || c == '\u06DE' || c == '\u06E9' ? "به م" : "بهم";
            assertEquals(tokens(BASE, plain), tokens(BASE, "به" + c + "م"), String.format("U+%04X", (int) c));
        }
    }

    @Test
    void arabicMarksFromU0610ToU061AAndU08D3ToU08FFNormaliseLikeThePlainSpelling() {
        // Round 2 ruling (1.0.4): the same gap outside U+06D6-06ED: open tanween (KFGQPC Uthmani), honorific signs.
        assertEquals(tokens(BASE, "غفورا رحيما"), tokens(BASE, "غَفُور\u08F0ا رَّحِيم\u08F0ا")); // U+08F0 open fathatan
        assertEquals(tokens(BASE, "محمد"), tokens(BASE, "محمد\u0610")); // U+0610 sallallahou alayhe wassallam
        assertEquals(tokens(BASE, "الرحيم ١"), tokens(BASE, "الرحيم\u08E2١")); // U+08E2 disputed end of ayah ends the word
        for (int[] range : new int[][]{{0x0610, 0x061A}, {0x08D3, 0x08FF}}) {
            for (char c = (char) range[0]; c <= range[1]; c++) {
                String plain = c == '\u08E2' ? "به م" : "بهم";
                assertEquals(tokens(BASE, plain), tokens(BASE, "به" + c + "م"), String.format("U+%04X", (int) c));
            }
        }
    }

    @Test
    void bidiMarksVanishWithoutJoiningWordsASpaceSeparates() {
        // Round 3 ruling (1.0.4): LRM, RLM and ALM are format characters the tokenizer kept inside the word, so a title
        // carrying "الله" + RLM never matched the query "الله".
        for (char mark : new char[]{'\u200E', '\u200F', '\u061C'}) {
            String name = String.format("U+%04X", (int) mark);
            assertEquals(tokens(BASE, "قال الله"), tokens(BASE, "قال" + mark + " " + mark + "الله" + mark), name);
            assertEquals(tokens(BASE, "quran tv"), tokens(BASE, "quran" + mark + " tv"), name);
            assertEquals(tokens(BASE, "abcd"), tokens(BASE, "ab" + mark + "cd"), name); // invisible: it never split a word
        }
    }

    @Test
    void directionalEmbeddingsIsolatesAndTheBomVanishWithoutJoiningWordsASpaceSeparates() {
        // Round 4 ruling (1.0.4): an embedding (RLE...PDF) or isolate (LRI...PDI) pair kept its controls inside the words.
        assertEquals(tokens(BASE, "تفسير ابن كثير سورة الكهف"),
                tokens(BASE, "\u202Bتفسير \u2066ابن كثير\u2069 سورة الكهف\u202C"));
        for (char c : "\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF".toCharArray()) {
            String name = String.format("U+%04X", (int) c);
            assertEquals(tokens(BASE, "قال الله"), tokens(BASE, "قال" + c + " " + c + "الله" + c), name);
            assertEquals(tokens(BASE, "abcd"), tokens(BASE, "ab" + c + "cd"), name); // invisible: it never split a word
        }
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
    void arabicIndicAndPersianDigitsFoldToAsciiDigits() {
        // Search-languages audit (2026-10-17): titles write 0-9 or ٠-٩ (38 of 3,949 sampled titles), Arabic keyboards type
        // ٠-٩, and "برنامج فسيروا ٣" ranked season 3 below seasons 2 and 1 in the related tier.
        assertEquals(tokens(BASE, "شاشات رمضان 1439"), tokens(BASE, "شاشات رمضان ١٤٣٩"));
        assertEquals(tokens(BASE, "فسيروا 3"), tokens(BASE, "فسيروا ۳")); // Persian digits U+06F0-06F9
    }

    @Test
    void transliterationAynAndHamzaSignsVanishLikeApostrophes() {
        // Audit: U+02BF/U+02BE are the ʿayn/hamza of scholarly transliteration (live title "ʿOmar ibn ʿAbdoel-ʿAziez"). The
        // apostrophe rule had their siblings ʻ ʼ but not them, so "omar" missed that title in the exact tier.
        assertEquals(List.of("kalief", "omar", "ibn", "abdoel", "aziez"), tokens(BASE, "kalief ʿOmar ibn ʿAbdoel-ʿAziez"));
        assertEquals(tokens(BASE, "Quraan Islam"), tokens(BASE, "Qurʾaan ʾIslām"));
    }

    @Test
    void persianAndUrduLetterFormsFoldToTheArabicLetters() {
        // Audit: a Persian/Urdu keyboard types ی (U+06CC) and ک (U+06A9) for ي/ى and ك. Words under 4 letters get no fuzzy
        // clause, so "احمد علی" never found "احمد علي" above the related tier.
        assertEquals(tokens(BASE, "مصطفى العدوي كتاب علي"), tokens(BASE, "مصطفی العدوی کتاب علی"));
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
        BooleanQuery q = (BooleanQuery) tier(1, List.of("story", "of", "yusuf"));
        assertEquals(List.of(BooleanClause.Occur.MUST, BooleanClause.Occur.SHOULD, BooleanClause.Occur.MUST),
                q.clauses().stream().map(BooleanClause::getOccur).toList());
        BooleanQuery onlyStopwords = (BooleanQuery) tier(1, List.of("of", "the"));
        assertEquals(1, onlyStopwords.getMinimumNumberShouldMatch());
    }

    @Test
    void theRelatedTierNeedsAnyOneWordAndStopwordsOnlyAddScore() {
        // Ruling (stage 9, B5) amends spec §4.4's flat tier 3 (every token SHOULD, minimumShouldMatch = 1).
        BooleanQuery q = (BooleanQuery) tier(3, List.of("arabic", "of", "learn"));
        assertEquals(0, q.getMinimumNumberShouldMatch());
        assertEquals(List.of(BooleanClause.Occur.SHOULD, BooleanClause.Occur.MUST),
                q.clauses().stream().map(BooleanClause::getOccur).toList()); // "of", then the words
        BooleanQuery words = (BooleanQuery) q.clauses().get(1).getQuery();
        assertEquals(1, words.getMinimumNumberShouldMatch());
        assertEquals(2, words.clauses().size());
        assertTrue(words.clauses().stream().allMatch(c -> c.getOccur() == BooleanClause.Occur.SHOULD));
    }

    // ---- Fix round 1 -------------------------------------------------------------------------

    /** Ten distinct 8-letter tokens; the index holds 208 neighbours of each within 2 edits. */
    private static final List<String> ZZ_TOKENS = List.of("tafsirzz", "hadithzz", "aqeedazz", "sunnahzz",
            "seerahzz", "tawhidzz", "zakaatzz", "wudhoozz", "ramadnzz", "hijrahzz");
    /** Ten long single-token concept variants from ten different groups (worst case for tier 3). */
    private static final List<String> CONCEPT_TOKENS = List.of("vrijdagpreek", "geloofsleer", "monotheism",
            "memorization", "overlevering", "remembrance", "repentance", "biography", "supplication", "companions");

    /**
     * Lucene 9 counts every leaf of the rewritten tree. Distinct tokens do not collapse the way identical
     * ones do, and each long token's fuzzy query expands to 50 terms on each of three fields.
     */
    private static void searchTenLongTokens(int tier, List<String> tokens) throws IOException {
        try (Directory directory = new ByteBuffersDirectory()) {
            try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(SearchText.INDEX))) {
                for (String token : tokens) {
                    String root = token.substring(0, token.length() - 2);
                    for (char a = 'a'; a <= 'h'; a++) {
                        for (char b = 'a'; b <= 'z'; b++) {
                            Document doc = new Document();
                            for (String field : List.of("title", "keywords", "channel")) {
                                doc.add(new TextField(field, root + a + b, Field.Store.NO));
                            }
                            writer.addDocument(doc);
                        }
                    }
                }
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                // The neighbours really are reachable, so the fuzzy queries below do expand.
                assertTrue(searcher.search(tier(2, List.of(tokens.get(0))), 10).totalHits.value > 0);
                searcher.search(tier(tier, tokens), 10); // must not throw TooManyNestedClauses
            }
        }
    }

    @Test
    void approximateTierAnswersTenDistinctLongTokens() throws IOException {
        searchTenLongTokens(2, ZZ_TOKENS);
    }

    @Test
    void relatedTierAnswersTenDistinctLongTokens() throws IOException {
        searchTenLongTokens(3, ZZ_TOKENS);
    }

    @Test
    void relatedTierAnswersTenLongTokensThatAllHaveConceptGroups() throws IOException {
        searchTenLongTokens(3, CONCEPT_TOKENS);
    }

    @Test
    void stopwordsAreNotExpandedByTheConceptMap() {
        // Arabic stopwords whose stem collides with a concept key (the Allah and ayah groups).
        for (String stopword : List.of("\u0644\u0647", "\u0627\u064A")) {
            assertEquals(1, ((BooleanQuery) tier(1, List.of(stopword))).getMinimumNumberShouldMatch(),
                    "precondition: stopword");
            assertFalse(SearchText.concepts(stopword).isEmpty(), "precondition: stem collides with a concept key");
            assertTrue(leaves(tier(3, List.of(stopword))).stream().noneMatch(l -> l.underBoost(0.5f)),
                    "stopword expanded: " + stopword);
        }
    }

    @Test
    void relatedTierDoesNotRepeatTheTokensOwnClauseAsAnExpansion() {
        assertTrue(SearchText.concepts("quran").contains(List.of("quran")), "precondition: own variant is in the group");
        long ownTitleTerms = leaves(tier(3, List.of("quran"))).stream()
                .filter(l -> l.query() instanceof TermQuery t && t.getTerm().equals(new Term("title", "quran")))
                .count();
        assertEquals(1, ownTitleTerms);
    }

    @Test
    void conceptGroupsCannotBeMutatedByCallers() {
        Set<List<String>> group = SearchText.concepts("gebed");
        assertThrows(UnsupportedOperationException.class, () -> group.add(List.of("x")));
        assertThrows(UnsupportedOperationException.class, () -> group.iterator().next().add("x"));
    }

    @Test
    void onlyTheFourLongestTokensOfAtMostTwentyCodePointsGetFuzzy() {
        // F1: each FuzzyQuery builds an automaton per field; a crafted query (one 128-character token,
        // ten long tokens) must not buy dozens of them. Lengths: 4, 12, 21, 5, 16, 20, 10, 3.
        String twenty = "a".repeat(20);
        List<String> tokens = List.of("abcd", "abcdefghijkl", "b".repeat(21), "abcde", "abcdefghijklmnop", twenty,
                "abcdefghij", "abc");
        for (int tier : new int[]{2, 3}) {
            List<FuzzyQuery> fuzzy = leaves(tier(tier, tokens)).stream()
                    .filter(l -> l.query() instanceof FuzzyQuery).map(l -> (FuzzyQuery) l.query()).toList();
            assertEquals(Set.of(twenty, "abcdefghijklmnop", "abcdefghijkl", "abcdefghij"),
                    fuzzy.stream().map(f -> f.getTerm().text()).collect(java.util.stream.Collectors.toSet()), "tier " + tier);
            assertEquals(12, fuzzy.size(), "4 tokens x 3 fields, tier " + tier);
        }
        assertTrue(leaves(tier(2, List.of("x".repeat(128)))).stream()
                .noneMatch(l -> l.query() instanceof FuzzyQuery), "no fuzzy past 20 code points");
    }

    @Test
    void tiersTwoAndThreeAskForTheSameFuzzyClausesSoOneRewriteServesBoth() {
        // F1: SearchIndex rewrites each fuzzy clause once per search; that only works if both tiers ask for equal ones.
        List<String> tokens = List.of("abcdefghijkl", "zakir", "tafsir", "abcdefghijklmn", "abcde");
        List<Query> asked = new ArrayList<>();
        java.util.function.UnaryOperator<Query> rewriter = q -> {
            asked.add(q);
            return q;
        };
        SearchText.tier(2, tokens, rewriter);
        List<Query> tier2 = List.copyOf(asked);
        asked.clear();
        SearchText.tier(3, tokens, rewriter);

        assertEquals(12, tier2.size(), "4 tokens x 3 fields");
        assertTrue(tier2.stream().allMatch(q -> q instanceof FuzzyQuery));
        assertEquals(new HashSet<>(tier2), new HashSet<>(asked));
    }

    @Test
    void queryShapeFollowsTheSpec() {
        // "abc", the Arabic word for prayer with full tashkeel, "abcdefgh"
        List<String> tokens = tokens(BASE,
                "abc \u0627\u064E\u0644\u0635\u0651\u064E\u0644\u064E\u0627\u0629 abcdefgh");
        String arabic = tokens.get(1);
        assertEquals(6, arabic.codePointCount(0, arabic.length()), "precondition: 6 code points once normalised");

        List<Leaf> exact = leaves(tier(1, tokens));
        List<Leaf> approximate = leaves(tier(2, tokens));
        Map<String, Set<Integer>> editsByToken = new HashMap<>();
        Set<String> fuzzyFields = new HashSet<>();
        for (Leaf leaf : approximate) {
            if (leaf.query() instanceof FuzzyQuery fuzzy) {
                editsByToken.computeIfAbsent(fuzzy.getTerm().text(), k -> new HashSet<>()).add(fuzzy.getMaxEdits());
                fuzzyFields.add(fuzzy.getTerm().field());
            }
        }
        Set<String> lastTokenPrefixes = Set.of("title:abcdefgh", "keywords:abcdefgh", "channel:abcdefgh");

        List<Leaf> related = leaves(tier(3, List.of("prophet")));
        List<Leaf> phrases = related.stream().filter(l -> l.query() instanceof PhraseQuery).toList();
        assertAll(
                // no fuzzy on 3 letters, 1 edit on 6 code points, 2 edits on 8
                () -> assertEquals(Map.of(arabic, Set.of(1), "abcdefgh", Set.of(2)), editsByToken),
                () -> assertEquals(Set.of("title", "keywords", "channel"), fuzzyFields),
                () -> assertTrue(exact.stream().noneMatch(l -> l.query() instanceof FuzzyQuery), "tier 1 has fuzzy"),
                // the prefix is on the last token only, and on the base fields only
                () -> assertEquals(lastTokenPrefixes, prefixes(exact)),
                () -> assertEquals(lastTokenPrefixes, prefixes(approximate)),
                // concept expansions: every leaf outside the token's own clause sits under a 0.5 boost
                () -> assertTrue(related.stream().anyMatch(l -> l.underBoost(0.5f)), "no expansion"),
                () -> assertTrue(related.stream().filter(l -> !l.underBoost(0.5f))
                        .allMatch(l -> l.query() instanceof TermQuery t && t.getTerm().text().equals("prophet")
                                || l.query() instanceof FuzzyQuery || l.query() instanceof PrefixQuery),
                        "a concept variant escaped the 0.5 boost"),
                () -> assertTrue(related.stream().anyMatch(l -> l.underBoost(0.5f)
                        && l.query() instanceof TermQuery t && t.getTerm().text().equals("mohammed")), "single variant"),
                () -> assertTrue(related.stream().filter(l -> l.underBoost(0.5f))
                        .noneMatch(l -> l.query() instanceof FuzzyQuery || l.query() instanceof PrefixQuery),
                        "expansions are term-only"),
                // a multi-token variant ("prophet Muhammad") is a phrase on the title
                () -> assertEquals(List.of("title"), phrases.stream().map(l -> ((PhraseQuery) l.query()).getField())
                        .distinct().toList()),
                () -> assertTrue(phrases.stream().anyMatch(l -> List.of(((PhraseQuery) l.query()).getTerms()).stream()
                        .map(Term::text).toList().equals(List.of("prophet", "muhammad")))),
                () -> assertTrue(phrases.stream().allMatch(l -> l.underBoost(0.5f)), "phrase escaped the 0.5 boost"));
    }

    /** The query SearchText builds for `tier`, with every fuzzy clause left as a FuzzyQuery. */
    private static Query tier(int tier, List<String> tokens) {
        return SearchText.tier(tier, tokens, q -> q);
    }

    private static Set<String> prefixes(List<Leaf> leaves) {
        Set<String> out = new HashSet<>();
        for (Leaf leaf : leaves) {
            if (leaf.query() instanceof PrefixQuery prefix) {
                out.add(prefix.getPrefix().field() + ":" + prefix.getPrefix().text());
            }
        }
        return out;
    }

    /** A leaf query and the chain of queries above it. */
    private record Leaf(Query query, List<Query> parents) {
        boolean underBoost(float boost) {
            return parents.stream().anyMatch(p -> p instanceof BoostQuery b && b.getBoost() == boost);
        }
    }

    private static List<Leaf> leaves(Query root) {
        List<Leaf> out = new ArrayList<>();
        root.visit(collector(List.of(), out));
        return out;
    }

    private static QueryVisitor collector(List<Query> parents, List<Leaf> out) {
        return new QueryVisitor() {
            @Override
            public void consumeTerms(Query query, Term... terms) {
                out.add(new Leaf(query, parents));
            }

            @Override
            public void consumeTermsMatching(Query query, String field, Supplier<ByteRunAutomaton> automaton) {
                out.add(new Leaf(query, parents));
            }

            @Override
            public QueryVisitor getSubVisitor(BooleanClause.Occur occur, Query parent) {
                List<Query> chain = new ArrayList<>(parents);
                chain.add(parent);
                return collector(chain, out);
            }
        };
    }
}
