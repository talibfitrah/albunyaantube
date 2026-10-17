package com.albunyaan.tube.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * The inner index's only persistent truth (inner-search spec §5, R15): one gzip JSONL file per source, one item per
 * line in YouTube's listing order. A file that does not read is treated as missing; the crawler crawls that source again.
 */
final class InnerCorpus {
    private static final Logger log = LoggerFactory.getLogger(InnerCorpus.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUFFIX = ".jsonl.gz";
    /** `channel:` or `playlist:` plus a YouTube id (UC/UU/PL/OL/RD/LL...): the id alphabet is the path-traversal guard, the cap keeps the file name legal. */
    private static final Pattern SOURCE_KEY = Pattern.compile("(channel|playlist):[A-Za-z0-9_-]{1,64}");

    /** `d` seconds (0 = unknown or live); `a` approximate upload day (days since epoch); `c`/`u` only for playlist sources. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String id, String t, int d, Integer a, String c, String u) {}

    private InnerCorpus() {
    }

    static Path file(Path corpusDir, String sourceKey) {
        if (!SOURCE_KEY.matcher(sourceKey).matches()) throw new IllegalArgumentException("not a source key: " + sourceKey);
        return corpusDir.resolve(sourceKey.replace(':', '_') + SUFFIX);
    }

    static String sourceKey(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - SUFFIX.length()).replaceFirst("_", ":");
    }

    static List<Path> files(Path corpusDir) throws IOException {
        try (Stream<Path> all = Files.list(corpusDir)) {
            return all.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        }
    }

    static List<Item> read(Path file) {
        if (!Files.exists(file)) return null;
        List<Item> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8))) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (!line.isEmpty()) out.add(JSON.readValue(line, Item.class));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("inner corpus {} unreadable; treated as missing", file.getFileName(), e);
            return null;
        }
    }

    /** Temp file + force + atomic move: a crash leaves the old file or the new one, never half of one. */
    static void write(Path file, List<Item> items) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE)) {
                GZIPOutputStream gz = new GZIPOutputStream(Channels.newOutputStream(ch), 65_536);
                Writer w = new OutputStreamWriter(gz, StandardCharsets.UTF_8);
                for (Item it : items) {
                    w.write(JSON.writeValueAsString(it));
                    w.write('\n');
                }
                w.flush();
                gz.finish();
                ch.force(true);
            }
            Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp); // gone after a successful move; a failed write leaves no stale .tmp
        }
    }
}
