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
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Text analysis and the three-tier query ladder (spec §4.3-§4.5). Stateless; safe from any thread. */
final class SearchText {

    static final int MAX_TOKENS = 10;

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
        for (String apostrophe : new String[]{"'", "’", "ʼ", "ʻ", "`"}) b.add(apostrophe, "");
        b.add("_", " ");
        b.add("ٱ", "ا"); // alef wasla -> alef
        b.add("ٰ", "");       // superscript alef
        for (char c = 'ٓ'; c <= 'ٟ'; c++) b.add(String.valueOf(c), "");
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
                TokenStream ts = new ASCIIFoldingFilter(new ArabicNormalizationFilter(new LowerCaseFilter(source)));
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
                    if (!variantTokens.isEmpty()) group.add(variantTokens);
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
        return CONCEPTS.getOrDefault(stem(token), Set.of());
    }

    /** Tier 1 = exact, 2 = approximate, 3 = related (spec §4.4). `tokens` are BASE tokens. */
    static Query tier(int tier, List<String> tokens) {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            Query clause = tokenClause(token, i == tokens.size() - 1, tier >= 2);
            if (tier == 3) clause = withConcepts(clause, token);
            boolean optional = tier == 3 || STOPWORDS.contains(token);
            query.add(clause, optional ? Occur.SHOULD : Occur.MUST);
        }
        if (tier == 3 || STOPWORDS.containsAll(tokens)) query.setMinimumNumberShouldMatch(1);
        return query.build();
    }

    /** One token over every field: terms, stems, a prefix if it is the last token, fuzzy from tier 2. */
    private static Query tokenClause(String token, boolean last, boolean fuzzy) {
        String stem = stem(token);
        int length = token.codePointCount(0, token.length());
        int edits = length >= 8 ? 2 : length >= 4 ? 1 : 0;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (int f = 0; f < FIELDS.length; f++) {
            float half = BOOSTS[f] / 2;
            b.add(new BoostQuery(new TermQuery(new Term(FIELDS[f], token)), BOOSTS[f]), Occur.SHOULD);
            b.add(new BoostQuery(new TermQuery(new Term(FIELDS[f] + "_stem", stem)), half), Occur.SHOULD);
            if (last && length >= 2) {
                b.add(new BoostQuery(new PrefixQuery(new Term(FIELDS[f], token)), half), Occur.SHOULD);
            }
            if (fuzzy && edits > 0) {
                b.add(new BoostQuery(new FuzzyQuery(new Term(FIELDS[f], token), edits), half), Occur.SHOULD);
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
        for (List<String> variant : variants) {
            Query expansion = variant.size() == 1
                    ? tokenClause(variant.get(0), false, false)
                    : new BoostQuery(new PhraseQuery("title", variant.toArray(String[]::new)), BOOSTS[0]);
            b.add(new BoostQuery(expansion, 0.5f), Occur.SHOULD);
        }
        return b.build();
    }
}
