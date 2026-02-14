package com.albunyaan.tube.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExclusionCountBackfill argument parsing and constants.
 * The backfill's Firestore interaction is integration-tested against the emulator.
 */
class ExclusionCountBackfillTest {

    // ======================== parseMaxDocs ========================

    @Nested
    @DisplayName("parseMaxDocs")
    class ParseMaxDocsTests {

        @Test
        @DisplayName("returns default 10000 when no --cap arg")
        void noArg_returnsDefault() {
            assertEquals(10_000, ExclusionCountBackfill.parseMaxDocs(new String[]{}));
        }

        @Test
        @DisplayName("returns default when args is null")
        void nullArgs_returnsDefault() {
            assertEquals(10_000, ExclusionCountBackfill.parseMaxDocs(null));
        }

        @Test
        @DisplayName("parses valid --cap value")
        void validCap_returnsParsedValue() {
            assertEquals(50_000, ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=50000"}));
        }

        @Test
        @DisplayName("ignores unrelated args")
        void unrelatedArgs_returnsDefault() {
            assertEquals(10_000, ExclusionCountBackfill.parseMaxDocs(
                    new String[]{"--spring.profiles.active=backfill-exclusion-counts", "--other=value"}));
        }

        @Test
        @DisplayName("extracts --cap from mixed args")
        void mixedArgs_extractsCap() {
            assertEquals(25_000, ExclusionCountBackfill.parseMaxDocs(
                    new String[]{"--spring.profiles.active=backfill-exclusion-counts", "--cap=25000"}));
        }

        @Test
        @DisplayName("ignores non-positive --cap (zero)")
        void zeroCap_returnsDefault() {
            assertEquals(10_000, ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=0"}));
        }

        @Test
        @DisplayName("ignores negative --cap")
        void negativeCap_returnsDefault() {
            assertEquals(10_000, ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=-5"}));
        }

        @Test
        @DisplayName("ignores non-numeric --cap")
        void nonNumericCap_returnsDefault() {
            assertEquals(10_000, ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=abc"}));
        }

        @Test
        @DisplayName("handles --cap=1 (minimum valid)")
        void capOne_returnsOne() {
            assertEquals(1, ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=1"}));
        }

        @Test
        @DisplayName("clamps --cap above hard max (100000) to hard max")
        void capAboveHardMax_clampedToHardMax() {
            assertEquals(ExclusionCountBackfill.HARD_MAX_DOCS,
                    ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=999999"}));
        }

        @Test
        @DisplayName("--cap exactly at hard max returns hard max")
        void capAtHardMax_returnsHardMax() {
            assertEquals(100_000, ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=100000"}));
        }

        @Test
        @DisplayName("--cap just below hard max returns exact value")
        void capBelowHardMax_returnsExact() {
            assertEquals(99_999, ExclusionCountBackfill.parseMaxDocs(new String[]{"--cap=99999"}));
        }
    }

    // ======================== parseStartAfterChannel ========================

    @Nested
    @DisplayName("parseStartAfterChannel")
    class ParseStartAfterChannelTests {

        @Test
        @DisplayName("returns null when no arg")
        void noArg_returnsNull() {
            assertNull(ExclusionCountBackfill.parseStartAfterChannel(new String[]{}));
        }

        @Test
        @DisplayName("returns null when args is null")
        void nullArgs_returnsNull() {
            assertNull(ExclusionCountBackfill.parseStartAfterChannel(null));
        }

        @Test
        @DisplayName("parses valid --startAfterChannel value")
        void validValue_returnsParsed() {
            assertEquals("ch-abc-123",
                    ExclusionCountBackfill.parseStartAfterChannel(new String[]{"--startAfterChannel=ch-abc-123"}));
        }

        @Test
        @DisplayName("extracts from mixed args")
        void mixedArgs_extractsValue() {
            assertEquals("doc42",
                    ExclusionCountBackfill.parseStartAfterChannel(
                            new String[]{"--cap=5000", "--startAfterChannel=doc42", "--other=x"}));
        }

        @Test
        @DisplayName("returns null for empty value")
        void emptyValue_returnsNull() {
            assertNull(ExclusionCountBackfill.parseStartAfterChannel(new String[]{"--startAfterChannel="}));
        }

        @Test
        @DisplayName("returns null for whitespace-only value")
        void whitespaceValue_returnsNull() {
            assertNull(ExclusionCountBackfill.parseStartAfterChannel(new String[]{"--startAfterChannel=   "}));
        }

        @Test
        @DisplayName("does not match --startAfterPlaylist arg")
        void doesNotMatchPlaylistArg() {
            assertNull(ExclusionCountBackfill.parseStartAfterChannel(
                    new String[]{"--startAfterPlaylist=pl-123"}));
        }
    }

    // ======================== parseStartAfterPlaylist ========================

    @Nested
    @DisplayName("parseStartAfterPlaylist")
    class ParseStartAfterPlaylistTests {

        @Test
        @DisplayName("returns null when no arg")
        void noArg_returnsNull() {
            assertNull(ExclusionCountBackfill.parseStartAfterPlaylist(new String[]{}));
        }

        @Test
        @DisplayName("returns null when args is null")
        void nullArgs_returnsNull() {
            assertNull(ExclusionCountBackfill.parseStartAfterPlaylist(null));
        }

        @Test
        @DisplayName("parses valid --startAfterPlaylist value")
        void validValue_returnsParsed() {
            assertEquals("pl-xyz-789",
                    ExclusionCountBackfill.parseStartAfterPlaylist(new String[]{"--startAfterPlaylist=pl-xyz-789"}));
        }

        @Test
        @DisplayName("extracts from mixed args")
        void mixedArgs_extractsValue() {
            assertEquals("doc99",
                    ExclusionCountBackfill.parseStartAfterPlaylist(
                            new String[]{"--startAfterChannel=ch-1", "--startAfterPlaylist=doc99"}));
        }

        @Test
        @DisplayName("returns null for empty value")
        void emptyValue_returnsNull() {
            assertNull(ExclusionCountBackfill.parseStartAfterPlaylist(new String[]{"--startAfterPlaylist="}));
        }

        @Test
        @DisplayName("does not match --startAfterChannel arg")
        void doesNotMatchChannelArg() {
            assertNull(ExclusionCountBackfill.parseStartAfterPlaylist(
                    new String[]{"--startAfterChannel=ch-123"}));
        }
    }

    // ======================== Constants ========================

    @Nested
    @DisplayName("Constants")
    class ConstantsTests {

        @Test
        @DisplayName("BATCH_SIZE is 500 (Firestore WriteBatch limit)")
        void batchSizeIs500() {
            assertEquals(500, ExclusionCountBackfill.BATCH_SIZE);
        }

        @Test
        @DisplayName("DEFAULT_MAX_DOCS is 10000")
        void defaultMaxDocsIs10000() {
            assertEquals(10_000, ExclusionCountBackfill.DEFAULT_MAX_DOCS);
        }

        @Test
        @DisplayName("HARD_MAX_DOCS is 100000")
        void hardMaxDocsIs100000() {
            assertEquals(100_000, ExclusionCountBackfill.HARD_MAX_DOCS);
        }
    }
}
