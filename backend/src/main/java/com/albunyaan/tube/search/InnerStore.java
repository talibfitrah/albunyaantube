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
            return JSON.readValue(file.toFile(), InnerState.class);
        } catch (IOException e) {
            log.error("inner state {} unreadable; starting from the defaults (mode OFF)", file, e);
            return new InnerState();
        }
    }
}
