package com.albunyaan.tube.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.ar.ArabicNormalizationFilter;
import org.apache.lucene.analysis.ar.ArabicStemFilter;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.core.DecimalDigitFilter;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter;
import org.apache.lucene.analysis.fa.PersianNormalizationFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Text analysis and the three-tier query ladder (spec §4.3-§4.5). Stateless; safe from any thread. */
final class SearchText {

    static final int MAX_TOKENS = 10;
    /** Fuzzy builds an automaton per token and field: none past 20 code points, and only the 4 longest tokens (F1). */
    static final int MAX_FUZZY_LENGTH = 20, MAX_FUZZY_TOKENS = 4;

    // Lucene 9 counts every leaf of the rewritten tree, and a fuzzy query expands to 50 terms on each
    // of 3 fields. Even with fuzzy on 4 tokens (F1), ten long tokens that all have concept groups reach
    // 1,260 leaves at tier 3 (measured), past the default 1,024, which would answer 500. Process-wide;
    // this class is loaded before the first query is built.
    static {
        IndexSearcher.setMaxClauseCount(4096);
    }

    private static final NormalizeCharMap CHARS = charMap();
    static final Analyzer BASE = analyzer(false);
    static final Analyzer STEM = analyzer(true);
    /** Index-time analyzer: `*_stem` fields get the stemming chain, every other field the base one. */
    static final Analyzer INDEX = new PerFieldAnalyzerWrapper(BASE,
            Map.of("title_stem", STEM, "keywords_stem", STEM, "channel_stem", STEM));

    private static final Set<String> STOPWORDS = stopwords();
    private static final Map<String, Set<List<String>>> CONCEPTS = loadConcepts();

    /** Base fields with a `_stem` twin, in boost order; `description` is term-only at boost 1. */
    private static final String[] FIELDS = {"title", "keywords", "channel"};
    private static final float[] BOOSTS = {4f, 2f, 1.5f};

    private SearchText() {}

    /** Characters Lucene's Arabic normaliser misses (Uthmani marks), plus apostrophes and `_`. */
    private static NormalizeCharMap charMap() {
        NormalizeCharMap.Builder b = new NormalizeCharMap.Builder();
        // ʾ ʿ (U+02BE/02BF) are the hamza/ʿayn of scholarly transliteration ("ʿOmar", "Qurʾaan"), siblings of ʼ ʻ.
        for (String apostrophe : new String[]{"'", "’", "ʼ", "ʻ", "`", "ʾ", "ʿ"}) b.add(apostrophe, "");
        b.add("_", " ");
        b.add("ٱ", "ا"); // alef wasla -> alef
        b.add("ٰ", "");       // superscript alef
        for (char c = 'ٓ'; c <= 'ٟ'; c++) b.add(String.valueOf(c), "");
        // Arabic signs U+0610-061A, Quranic signs U+06D6-06ED and Arabic Extended-A U+08D3-08FF: the marks (Mn) and the
        // small waw/yeh (U+06E5/06E6, Lm: a long vowel the plain spelling omits, لَهُۥ = له) go. The two end-of-ayah
        // signs (U+06DD, U+08E2) are format characters the tokenizer glues to both neighbours: they end the word.
        // The two symbols (U+06DE, U+06E9) already split words.
        for (char[] range : new char[][]{{'\u0610', '\u061A'}, {'\u06D6', '\u06ED'}, {'\u08D3', '\u08FF'}}) {
            for (char c = range[0]; c <= range[1]; c++) {
                int type = Character.getType(c);
                if (type == Character.NON_SPACING_MARK || type == Character.MODIFIER_LETTER) b.add(String.valueOf(c), "");
            }
        }
        b.add("\u06DD", " ");
        b.add("\u08E2", " ");
        // Directional controls (LRM, RLM, ALM, the embeddings/overrides U+202A-202E, the isolates U+2066-2069) and the
        // BOM: invisible format characters the tokenizer keeps inside the word. They never separate words, so they go
        // without a space. ZWNJ/ZWJ stay: they carry meaning inside Persian words.
        for (char c : "\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069\uFEFF".toCharArray()) {
            b.add(String.valueOf(c), "");
        }
        return b.build();
    }

    private static Analyzer analyzer(boolean stem) {
        return new Analyzer() {
            @Override
            protected Reader initReader(String fieldName, Reader reader) {
                return new MappingCharFilter(CHARS, reader);
            }

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer source = new StandardTokenizer();
                // Digits fold to 0-9 (Arabic keyboards type ٠-٩); Persian/Urdu ی ک fold to ي ك.
                TokenStream ts = new ASCIIFoldingFilter(new PersianNormalizationFilter(new ArabicNormalizationFilter(
                        new DecimalDigitFilter(new LowerCaseFilter(source)))));
                // ponytail: no Dutch stemmer; on one mixed-language field its false merges (god/good, rap/rape) outweigh
                // the Dutch gain (2 of 110 audit queries, 2026-10-17). Add one only together with per-language analysis.
                if (stem) ts = new EnglishMinimalStemFilter(new ArabicStemFilter(ts));
                return new TokenStreamComponents(source, ts);
            }
        };
    }

    /** The analyzed tokens of `text`, in order. */
    static List<String> tokens(Analyzer analyzer, String text) {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) out.add(term.toString());
            ts.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static String stem(String token) {
        List<String> stemmed = tokens(STEM, token);
        return stemmed.isEmpty() ? token : stemmed.get(0);
    }

    /** A BASE token in the stop sets: tier 1 makes it optional unless the query is all stopwords (B5). */
    static boolean isStopword(String token) {
        return STOPWORDS.contains(token);
    }

    /** English + Dutch + Arabic stop sets, each run once through BASE (spec §4.4). */
    private static Set<String> stopwords() {
        Set<String> out = new HashSet<>();
        for (CharArraySet set : List.of(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET,
                DutchAnalyzer.getDefaultStopSet(), ArabicAnalyzer.getDefaultStopSet())) {
            for (Object word : set) {
                out.addAll(tokens(BASE, word instanceof char[] chars ? new String(chars) : String.valueOf(word)));
            }
        }
        return out;
    }

    /** concepts.txt keyed by the stem of every single-token variant (spec §4.5). */
    private static Map<String, Set<List<String>>> loadConcepts() {
        Map<String, Set<List<String>>> byStem = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                SearchText.class.getResourceAsStream("/search/concepts.txt"), "search/concepts.txt is missing"),
                StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int comment = line.indexOf('#');
                List<List<String>> group = new ArrayList<>();
                for (String variant : (comment >= 0 ? line.substring(0, comment) : line).split(",")) {
                    List<String> variantTokens = tokens(BASE, variant);
                    if (!variantTokens.isEmpty()) group.add(List.copyOf(variantTokens));
                }
                for (List<String> variant : group) {
                    if (variant.size() == 1) {
                        byStem.computeIfAbsent(stem(variant.get(0)), k -> new LinkedHashSet<>()).addAll(group);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byStem;
    }

    /** Concept-map variants (each a list of BASE tokens) for a query token, matched by stem. */
    static Set<List<String>> concepts(String token) {
        return Collections.unmodifiableSet(CONCEPTS.getOrDefault(stem(token), Set.of()));
    }

    /**
     * Tier 1 = exact, 2 = approximate, 3 = related (spec §4.4). `tokens` are BASE tokens. Each FuzzyQuery passes
     * through `fuzzy`, which may swap it for its rewrite: SearchIndex builds each automaton once per search (F1).
     */
    static Query tier(int tier, List<String> tokens, UnaryOperator<Query> fuzzy) {
        Set<Integer> chosen = tier >= 2 ? fuzzyTokens(tokens) : Set.of();
        boolean onlyStopwords = STOPWORDS.containsAll(tokens);
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        // Tier 3 needs any one non-stopword, not all; a stopword only adds score unless the query is all stopwords (B5).
        BooleanQuery.Builder anyWord = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean stopword = STOPWORDS.contains(token);
            Query clause = tokenClause(token, i == tokens.size() - 1, chosen.contains(i) ? fuzzy : null);
            if (tier == 3 && !stopword) {
                // A stopword can share a stem with a concept key (له with the Allah group): never expand it.
                anyWord.add(withConcepts(clause, token), Occur.SHOULD);
            } else {
                query.add(clause, tier == 3 || stopword ? Occur.SHOULD : Occur.MUST);
            }
        }
        if (onlyStopwords) query.setMinimumNumberShouldMatch(1);
        else if (tier == 3) query.add(anyWord.build(), Occur.MUST);
        return query.build();
    }

    /** Positions of the tokens that get fuzzy clauses: 4 to 20 code points, the 4 longest (the earlier one on a tie). */
    private static Set<Integer> fuzzyTokens(List<String> tokens) {
        return IntStream.range(0, tokens.size()).boxed()
                .filter(i -> length(tokens.get(i)) >= 4 && length(tokens.get(i)) <= MAX_FUZZY_LENGTH)
                .sorted(Comparator.comparingInt((Integer i) -> length(tokens.get(i))).reversed())
                .limit(MAX_FUZZY_TOKENS)
                .collect(Collectors.toSet());
    }

    private static int length(String token) {
        return token.codePointCount(0, token.length());
    }

    /** One token over every field: terms, stems, a prefix if it is the last token, fuzzy unless `fuzzy` is null. */
    private static Query tokenClause(String token, boolean last, UnaryOperator<Query> fuzzy) {
        String stem = stem(token);
        int length = length(token);
        int edits = length >= 8 ? 2 : length >= 4 ? 1 : 0;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (int f = 0; f < FIELDS.length; f++) {
            float half = BOOSTS[f] / 2;
            b.add(new BoostQuery(new TermQuery(new Term(FIELDS[f], token)), BOOSTS[f]), Occur.SHOULD);
            b.add(new BoostQuery(new TermQuery(new Term(FIELDS[f] + "_stem", stem)), half), Occur.SHOULD);
            if (last && length >= 2) {
                b.add(new BoostQuery(new PrefixQuery(new Term(FIELDS[f], token)), half), Occur.SHOULD);
            }
            if (fuzzy != null && edits > 0) {
                b.add(new BoostQuery(fuzzy.apply(new FuzzyQuery(new Term(FIELDS[f], token), edits)), half), Occur.SHOULD);
            }
        }
        b.add(new TermQuery(new Term("description", token)), Occur.SHOULD);
        return b.build();
    }

    /** Tier 3: the token's own clause OR any concept-map variant at boost 0.5 (spec §4.5). */
    private static Query withConcepts(Query clause, String token) {
        Set<List<String>> variants = concepts(token);
        if (variants.isEmpty()) return clause;
        BooleanQuery.Builder b = new BooleanQuery.Builder().add(clause, Occur.SHOULD);
        List<String> self = List.of(token);
        for (List<String> variant : variants) {
            if (variant.equals(self)) continue; // already the token's own clause
            Query expansion = variant.size() == 1
                    ? tokenClause(variant.get(0), false, null)
                    : new BoostQuery(new PhraseQuery("title", variant.toArray(String[]::new)), BOOSTS[0]);
            b.add(new BoostQuery(expansion, 0.5f), Occur.SHOULD);
        }
        return b.build();
    }
}
