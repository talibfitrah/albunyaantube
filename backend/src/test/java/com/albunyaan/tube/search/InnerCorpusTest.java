package com.albunyaan.tube.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Spec §5, R15: the corpus format, its file names, atomic writes, and an unreadable file read as missing. */
class InnerCorpusTest {

    @TempDir Path dir;

    @Test
    void itemsRoundTripInListingOrderAndOmitWhatIsUnknown() throws IOException {
        List<InnerCorpus.Item> items = List.of(
                new InnerCorpus.Item("ilgiwqpcOC8", "Lezing: 'De rechtvaardige kalief ʿOmar ibn ʿAbdoel-ʿAziez'", 2712, 20180, null, null),
                new InnerCorpus.Item("cBjnzYHSV4M", "شرح العقيدة السفارينية", 0, null, "Dawah Nederland", "UCfassTU6mGWIj-yb26_ADjQ"));
        Path f = InnerCorpus.file(dir, "playlist:PLiTKQO5a7ZXn3ePWq7YzVAXeXUmdFu6PA");
        InnerCorpus.write(f, items);
        assertEquals(items, InnerCorpus.read(f));
        String text = new String(new GZIPInputStream(Files.newInputStream(f)).readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("{\"id\":\"ilgiwqpcOC8\",\"t\":\"Lezing: 'De rechtvaardige kalief ʿOmar ibn ʿAbdoel-ʿAziez'\",\"d\":2712,\"a\":20180}",
                text.lines().findFirst().orElseThrow());
        assertFalse(Files.exists(f.resolveSibling(f.getFileName() + ".tmp")));
    }

    @Test
    void aFileNameMapsBackToItsSourceKeyEvenWithUnderscoresInTheId() {
        Path f = InnerCorpus.file(dir, "playlist:PLXS0usquloW_VC2gPb6nu1FFQTxWc1Ww_");
        assertEquals("playlist_PLXS0usquloW_VC2gPb6nu1FFQTxWc1Ww_.jsonl.gz", f.getFileName().toString());
        assertEquals("playlist:PLXS0usquloW_VC2gPb6nu1FFQTxWc1Ww_", InnerCorpus.sourceKey(f));
        assertEquals("channel:UCvWyP-vFhlS1-XENpD6w68A",
                InnerCorpus.sourceKey(InnerCorpus.file(dir, "channel:UCvWyP-vFhlS1-XENpD6w68A")));
    }

    @Test
    void aMissingOrUnreadableFileReadsAsMissing() throws IOException {
        assertNull(InnerCorpus.read(dir.resolve("channel_UCnone.jsonl.gz")));
        Path garbage = dir.resolve("channel_UCgarbage.jsonl.gz");
        Files.write(garbage, new byte[]{1, 2, 3});
        assertNull(InnerCorpus.read(garbage));
        Path notJson = dir.resolve("channel_UCnotjson.jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(notJson))) {
            out.write("{\"id\":\"ilgiwqpcOC8\",\"t\":\"ok\",\"d\":1}\nnot json\n".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(InnerCorpus.read(notJson));
    }

    @Test
    void filesListsOnlyCorpusFilesInNameOrder() throws IOException {
        InnerCorpus.write(InnerCorpus.file(dir, "playlist:PLb"), List.of());
        InnerCorpus.write(InnerCorpus.file(dir, "channel:UCa"), List.of());
        Files.writeString(dir.resolve("channel_UCc.jsonl.gz.tmp"), "half");
        Files.writeString(dir.resolve("notes.txt"), "x");
        assertEquals(List.of("channel_UCa.jsonl.gz", "playlist_PLb.jsonl.gz"),
                InnerCorpus.files(dir).stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void aRewriteReplacesTheWholeFile() throws IOException {
        Path f = InnerCorpus.file(dir, "channel:UCa");
        InnerCorpus.write(f, List.of(new InnerCorpus.Item("lesson00001", "a", 1, null, null, null),
                new InnerCorpus.Item("lesson00002", "b", 1, null, null, null)));
        InnerCorpus.write(f, List.of(new InnerCorpus.Item("lesson00003", "c", 1, null, null, null)));
        assertEquals(List.of("lesson00003"), InnerCorpus.read(f).stream().map(InnerCorpus.Item::id).toList());
    }

    @Test
    void aSourceKeyThatCouldEscapeTheDirIsRefused() {
        for (String bad : new String[]{"channel:../x", "channel:UC/../../x", "channel:UC\\x", "playlist:", "video:UCa",
                "channel:XXa", "../channel:UCa", "channel:UCa\0", "", "UCa"}) {
            assertThrows(IllegalArgumentException.class, () -> InnerCorpus.file(dir, bad), bad);
        }
        assertEquals(dir, InnerCorpus.file(dir, "channel:UUab-_9").getParent());
    }

    @Test
    void unknownFieldsOnALineAreIgnored() throws IOException {
        Path f = dir.resolve("channel_UCa.jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f))) {
            out.write("{\"id\":\"lesson00001\",\"t\":\"x\",\"d\":1,\"future\":true}\n".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(List.of(new InnerCorpus.Item("lesson00001", "x", 1, null, null, null)), InnerCorpus.read(f));
    }
}
