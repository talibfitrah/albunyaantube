package com.albunyaan.tube.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Inner search's one property and its files (spec R25, R26, §5). `search.inner.dir` empty (the Java default; prod's
 * config replaces application.yml) or unusable = the feature is off and search is exactly today's. Inside it:
 * state.json, the optional STOP file (the token-free kill switch) and corpus/.
 */
@Component
public class InnerStore {
    private static final Logger log = LoggerFactory.getLogger(InnerStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final InnerState state;

    public InnerStore(@Value("${search.inner.dir:}") String dir) {
        Path usable = null;
        InnerState loaded = new InnerState();
        if (!dir.isBlank()) {
            try {
                Path d = Path.of(dir);
                Files.createDirectories(d.resolve("corpus"));
                if (!Files.isWritable(d) || !Files.isWritable(d.resolve("corpus"))) throw new IOException(dir + " is not writable");
                loaded = load(d.resolve("state.json"));
                usable = d;
            } catch (IOException | RuntimeException e) {
                log.error("search.inner.dir {} is not usable; inner search is off", dir, e);
            }
        }
        this.dir = usable;
        this.state = loaded;
    }

    public boolean enabled() {
        return dir != null;
    }

    public InnerState state() {
        return state;
    }

    Path corpusDir() {
        return dir.resolve("corpus");
    }

    Path corpusFile(String sourceKey) {
        return InnerCorpus.file(corpusDir(), sourceKey);
    }

    /** While this file exists the crawler acts as OFF (R25): `touch <dir>/STOP` over SSH, no token, no restart. */
    public boolean stopped() {
        return dir != null && Files.exists(dir.resolve("STOP"));
    }

    /** Temp + force + atomic move (R15): a crash leaves the old state or the new one, never half of one. */
    public void save() {
        if (dir == null) return;
        synchronized (state) {
            try {
                ByteBuffer bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(state));
                Path tmp = dir.resolve("state.json.tmp");
                try (FileChannel ch = FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE)) {
                    while (bytes.hasRemaining()) ch.write(bytes);
                    ch.force(true);
                }
                Files.move(tmp, dir.resolve("state.json"), ATOMIC_MOVE, REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("inner state not saved", e);
            }
        }
    }

    private static InnerState load(Path file) {
        if (!Files.exists(file)) return new InnerState();
        try {
            InnerState s = JSON.readValue(file.toFile(), InnerState.class);
            if (s == null) s = new InnerState(); // a literal `null` file
            if (s.breaker == null) s.breaker = new InnerState.Breaker();
            if (s.sources == null) s.sources = new ConcurrentHashMap<>();
            if (s.signals == null) s.signals = new ArrayList<>();
            if (s.requestsByDay == null) s.requestsByDay = new TreeMap<>();
            if (s.memory == null) s.memory = new ArrayList<>();
            return s;
        } catch (IOException e) {
            Path kept = file.resolveSibling("state.json.corrupt");
            try {
                Files.move(file, kept, REPLACE_EXISTING);
            } catch (IOException m) {
                log.error("could not keep corrupt state as {}", kept, m);
            }
            log.error("inner state {} unreadable; kept as {}; starting from the defaults (mode OFF)", file, kept, e);
            return new InnerState();
        }
    }
}
