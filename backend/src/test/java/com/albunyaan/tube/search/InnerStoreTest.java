package com.albunyaan.tube.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Spec R26: one property, empty = off; R15: state.json is atomic and survives a restart; R25: the STOP file. */
class InnerStoreTest {

    @TempDir Path tmp;

    @Test
    void anEmptyDirTurnsTheFeatureOff() {
        InnerStore store = new InnerStore("");
        assertFalse(store.enabled());
        assertEquals(InnerState.Mode.OFF, store.state().mode);
        assertFalse(store.state().serveInner);
        assertFalse(store.stopped());
        store.save(); // a no-op, never a throw
    }

    @Test
    void theDefaultsAreOffServingNothingAtTheFirstPassPace() {
        InnerState s = new InnerState();
        assertEquals(InnerState.Mode.OFF, s.mode);
        assertFalse(s.serveInner || s.streamGate || s.backfillHeld);
        assertEquals(10_000, s.capPerSource);
        assertEquals(30_000, s.breaker.intervalMs);
    }

    @Test
    void aSavedStateComesBackAfterARestart() throws IOException {
        Path dir = tmp.resolve("inner");
        InnerStore store = new InnerStore(dir.toString());
        assertTrue(store.enabled());
        assertTrue(Files.isDirectory(dir.resolve("corpus")));
        InnerState s = store.state();
        s.mode = InnerState.Mode.FIRST_PASS;
        s.serveInner = true;
        s.capPerSource = 2_000;
        s.breaker.intervalMs = 60_000;
        s.breaker.pausedUntil = 123L;
        s.signals.add(new InnerState.Signal(1L, "HTTP_429", "www.youtube.com/youtubei/v1/browse", 429));
        InnerState.Source src = new InnerState.Source();
        src.hl = "ar";
        src.count = 1634;
        src.shortCrawl = true;
        src.completeAt = 9L;
        s.sources.put("channel:UCfassTU6mGWIj-yb26_ADjQ", src);
        s.requestsByDay.computeIfAbsent("2026-10-20", d -> new TreeMap<>()).put("inner-crawler", 7L);
        store.save();

        InnerState back = new InnerStore(dir.toString()).state();
        assertEquals(InnerState.Mode.FIRST_PASS, back.mode);
        assertTrue(back.serveInner);
        assertEquals(2_000, back.capPerSource);
        assertEquals(60_000, back.breaker.intervalMs);
        assertEquals(123L, back.breaker.pausedUntil);
        assertEquals("HTTP_429", back.signals.get(0).type());
        InnerState.Source b = back.sources.get("channel:UCfassTU6mGWIj-yb26_ADjQ");
        assertEquals("ar", b.hl);
        assertEquals(1634, b.count);
        assertTrue(b.shortCrawl);
        assertEquals(9L, b.completeAt);
        assertEquals(7L, back.requestsByDay.get("2026-10-20").get("inner-crawler"));
        assertFalse(Files.exists(dir.resolve("state.json.tmp")));
        assertTrue(Files.readString(dir.resolve("state.json")).contains("\"short\":true"));
    }

    @Test
    void theStopFileIsSeenAtOnce() throws IOException {
        Path dir = tmp.resolve("inner");
        InnerStore store = new InnerStore(dir.toString());
        assertFalse(store.stopped());
        Files.createFile(dir.resolve("STOP"));
        assertTrue(store.stopped());
        Files.delete(dir.resolve("STOP"));
        assertFalse(store.stopped());
    }

    @Test
    void anUnreadableStateStartsOffAndSaysSoAtError() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("inner"));
        Files.writeString(dir.resolve("state.json"), "{not json");
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(InnerStore.class)).addAppender(logs);
        try {
            InnerStore store = new InnerStore(dir.toString());
            assertTrue(store.enabled());
            assertEquals(InnerState.Mode.OFF, store.state().mode);
            assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR));
        } finally {
            ((Logger) LoggerFactory.getLogger(InnerStore.class)).detachAppender(logs);
        }
    }

    @Test
    void anUnwritableDirTurnsTheFeatureOff() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        assumeFalse("root".equals(System.getProperty("user.name")));
        Path dir = Files.createDirectories(tmp.resolve("locked"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertFalse(new InnerStore(dir.resolve("inner").toString()).enabled());
            assertFalse(new InnerStore(dir.toString()).enabled());
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void unknownNestedFieldsFromANewerBuildStillLoad() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("inner"));
        Files.writeString(dir.resolve("state.json"),
                "{\"breaker\":{\"intervalMs\":60000,\"future\":1},\"sources\":{\"a\":{\"hl\":\"ar\",\"future\":2}}}");
        InnerState s = new InnerStore(dir.toString()).state();
        assertEquals(60_000, s.breaker.intervalMs);
        assertEquals("ar", s.sources.get("a").hl);
    }

    @Test
    void aCorruptStateIsKeptBesideTheNewOne() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("inner"));
        Files.writeString(dir.resolve("state.json"), "{not json");
        new InnerStore(dir.toString()).save();
        assertEquals("{not json", Files.readString(dir.resolve("state.json.corrupt")));
    }

    @Test
    void aNullFileOrNullPartsLoadAsDefaults() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("inner"));
        Files.writeString(dir.resolve("state.json"), "null");
        assertEquals(InnerState.Mode.OFF, new InnerStore(dir.toString()).state().mode);
        Files.writeString(dir.resolve("state.json"), "{\"sources\":null,\"breaker\":null}");
        InnerState s = new InnerStore(dir.toString()).state();
        assertNotNull(s.sources);
        assertNotNull(s.breaker);
    }
}
