package com.albunyaan.tube.util;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One-time backfill utility to populate missing exclusion count fields on legacy documents.
 *
 * <p><b>Problem:</b> The exclusions workspace queries use
 * {@code whereGreaterThan("excludedItems.totalExcludedCount", 0)} for channels and
 * {@code whereGreaterThan("excludedVideoCount", 0)} for playlists. Legacy documents
 * created before these count fields were introduced will have exclusion arrays but
 * missing count fields, making them invisible to Firestore inequality queries.
 *
 * <p><b>Solution:</b> Paginated cursor-based scan of all documents ordered by document ID
 * (guaranteed present on every document — unlike {@code createdAt} which may be missing on
 * legacy docs and would cause {@code orderBy} to silently exclude them). Computes the
 * correct count from the exclusion arrays and writes only the count field via
 * {@code WriteBatch.update()} — no side effects on {@code updatedAt} or other fields.
 *
 * <p><b>Usage:</b>
 * <pre>
 * # Default: scan up to 10,000 docs per collection
 * ./gradlew bootRun --args='--spring.profiles.active=backfill-exclusion-counts'
 *
 * # Override cap for large collections (hard max: 100,000)
 * ./gradlew bootRun --args='--spring.profiles.active=backfill-exclusion-counts --cap=50000'
 *
 * # Resume channels from a previous run's last-processed document ID
 * ./gradlew bootRun --args='--spring.profiles.active=backfill-exclusion-counts --startAfterChannel=LAST_CH_DOC_ID'
 *
 * # Resume playlists from a previous run's last-processed document ID
 * ./gradlew bootRun --args='--spring.profiles.active=backfill-exclusion-counts --startAfterPlaylist=LAST_PL_DOC_ID'
 *
 * # Combined: resume both with higher cap
 * ./gradlew bootRun --args='--spring.profiles.active=backfill-exclusion-counts --cap=50000 --startAfterChannel=ABC --startAfterPlaylist=XYZ'
 * </pre>
 *
 * <p><b>Safety:</b>
 * <ul>
 *   <li>Ordered by document ID ({@code FieldPath.documentId()}) — guaranteed to exist on
 *       every document, unlike {@code createdAt} which silently excludes docs where it is
 *       missing from {@code orderBy} results</li>
 *   <li>Strict cap: exactly {@code --cap=N} documents are scanned per collection (default
 *       10,000; hard max 100,000). Query fetch size is clamped to remaining count.</li>
 *   <li>Resumable: {@code --startAfterChannel=<docId>} and {@code --startAfterPlaylist=<docId>}
 *       skip previously processed documents without re-scanning them</li>
 *   <li>Uses {@code WriteBatch.update()} to write only the count field — does NOT touch
 *       {@code updatedAt} or overwrite the entire document</li>
 *   <li>Only writes documents that actually have exclusions (skips documents with empty lists)</li>
 *   <li>Idempotent: safe to run multiple times</li>
 * </ul>
 */
@Component
@Profile("backfill-exclusion-counts")
public class ExclusionCountBackfill implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ExclusionCountBackfill.class);

    static final int BATCH_SIZE = 500;
    static final int DEFAULT_MAX_DOCS = 10_000;
    static final int HARD_MAX_DOCS = 100_000;

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public ExclusionCountBackfill(
            Firestore firestore,
            FirestoreTimeoutProperties timeoutProperties
    ) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    @Override
    public void run(String... args) throws Exception {
        int maxDocs = parseMaxDocs(args);
        String startAfterChannel = parseStartAfterChannel(args);
        String startAfterPlaylist = parseStartAfterPlaylist(args);

        log.info("Starting exclusion count backfill (cap={}, startAfterChannel={}, startAfterPlaylist={})...",
                maxDocs, startAfterChannel, startAfterPlaylist);

        int channelsFixed = backfillChannelExclusionCounts(maxDocs, startAfterChannel);
        log.info("Channels backfilled: {} updated", channelsFixed);

        int playlistsFixed = backfillPlaylistExclusionCounts(maxDocs, startAfterPlaylist);
        log.info("Playlists backfilled: {} updated", playlistsFixed);

        log.info("Exclusion count backfill complete. Channels: {}, Playlists: {}", channelsFixed, playlistsFixed);
    }

    /**
     * Parse {@code --cap=N} from command-line arguments to override the default safety cap.
     * Values above {@link #HARD_MAX_DOCS} are clamped to that limit.
     *
     * @param args Command-line arguments
     * @return Max documents to scan per collection (default 10,000; hard max 100,000)
     */
    static int parseMaxDocs(String[] args) {
        if (args == null) {
            return DEFAULT_MAX_DOCS;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--cap=")) {
                try {
                    int val = Integer.parseInt(arg.substring("--cap=".length()));
                    if (val <= 0) {
                        log.warn("Ignoring non-positive --cap value: {}", val);
                        continue;
                    }
                    if (val > HARD_MAX_DOCS) {
                        log.warn("--cap={} exceeds hard maximum of {}. Clamping to {}.",
                                val, HARD_MAX_DOCS, HARD_MAX_DOCS);
                        return HARD_MAX_DOCS;
                    }
                    return val;
                } catch (NumberFormatException e) {
                    log.warn("Ignoring invalid --cap value: {}", arg);
                }
            }
        }
        return DEFAULT_MAX_DOCS;
    }

    /**
     * Parse {@code --startAfterChannel=<docId>} from command-line arguments for channel resume.
     *
     * @param args Command-line arguments
     * @return Document ID to resume after, or null for fresh scan
     */
    static String parseStartAfterChannel(String[] args) {
        return parseStringArg(args, "--startAfterChannel=");
    }

    /**
     * Parse {@code --startAfterPlaylist=<docId>} from command-line arguments for playlist resume.
     *
     * @param args Command-line arguments
     * @return Document ID to resume after, or null for fresh scan
     */
    static String parseStartAfterPlaylist(String[] args) {
        return parseStringArg(args, "--startAfterPlaylist=");
    }

    private static String parseStringArg(String[] args, String prefix) {
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                String val = arg.substring(prefix.length()).trim();
                if (!val.isEmpty()) {
                    return val;
                }
                log.warn("Ignoring empty {} value", prefix.substring(0, prefix.length() - 1));
            }
        }
        return null;
    }

    /**
     * Paginated scan of all channels to backfill totalExcludedCount.
     *
     * <p>Orders by {@code FieldPath.documentId()} to guarantee every document is visited,
     * including legacy docs that may be missing {@code createdAt}. For each channel with
     * exclusion arrays, computes the real count from the arrays and writes only
     * {@code excludedItems.totalExcludedCount} via WriteBatch.update().
     * Does not touch updatedAt or overwrite the document.
     *
     * <p>The {@code --cap} is enforced strictly: each batch fetches at most
     * {@code min(BATCH_SIZE, remaining)} documents, so the total scanned never exceeds
     * the cap.
     *
     * @param maxDocs Strict cap on total documents scanned
     * @param startAfterDocId Document ID to resume after, or null for fresh scan
     * @return Number of channels updated
     */
    private int backfillChannelExclusionCounts(int maxDocs, String startAfterDocId) throws Exception {
        CollectionReference collection = firestore.collection("channels");
        int updated = 0;
        int scanned = 0;
        QueryDocumentSnapshot lastDoc = null;
        String lastDocId = startAfterDocId;

        if (startAfterDocId != null) {
            log.info("Resuming channel scan after document: {}", startAfterDocId);
        }

        while (scanned < maxDocs) {
            int remaining = maxDocs - scanned;
            int fetchLimit = Math.min(BATCH_SIZE, remaining);

            // Order by document ID for scan completeness. Unlike createdAt, document ID
            // is guaranteed to exist on every document — orderBy("createdAt") silently
            // excludes docs where the field is missing, which defeats the backfill purpose.
            Query query = collection
                    .orderBy(FieldPath.documentId())
                    .limit(fetchLimit);

            if (lastDoc != null) {
                query = query.startAfter(lastDoc);
            } else if (startAfterDocId != null) {
                // First iteration with resume: use document ID string directly.
                // FieldPath.documentId() ordering accepts document ID strings as cursor values.
                query = query.startAfter(startAfterDocId);
            }

            QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
            List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

            if (docs.isEmpty()) {
                break;
            }

            WriteBatch batch = firestore.batch();
            int batchCount = 0;

            for (QueryDocumentSnapshot doc : docs) {
                scanned++;
                Channel channel = doc.toObject(Channel.class);
                Channel.ExcludedItems items = channel.getExcludedItems();
                if (items != null) {
                    // Compute count directly from arrays — do NOT rely on getTotalExcludedCount()
                    // which may return a stale stored value. This ensures legacy docs with
                    // missing count fields but populated arrays are always detected.
                    int realCount = safeSize(items.getVideos()) + safeSize(items.getLiveStreams())
                            + safeSize(items.getShorts()) + safeSize(items.getPlaylists())
                            + safeSize(items.getPosts());
                    if (realCount > 0) {
                        DocumentReference docRef = collection.document(doc.getId());
                        batch.update(docRef, "excludedItems.totalExcludedCount", realCount);
                        batchCount++;
                        log.debug("Backfilling channel {} ({}): totalExcludedCount={}",
                                doc.getId(), channel.getName(), realCount);
                    }
                }

                if (scanned >= maxDocs) {
                    break;
                }
            }

            if (batchCount > 0) {
                batch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                updated += batchCount;
            }

            lastDoc = docs.get(docs.size() - 1);
            lastDocId = lastDoc.getId();
            log.info("Channel batch: fetched={}, updated={} (total: scanned={}/{}, updated={}, lastDocId={})",
                    docs.size(), batchCount, scanned, maxDocs, updated, lastDocId);

            if (docs.size() < fetchLimit) {
                break; // No more documents in collection
            }
        }

        if (scanned >= maxDocs) {
            log.warn("Channel scan reached cap ({} docs). "
                    + "Resume with: --startAfterChannel={} --cap=<N>", maxDocs, lastDocId);
        }

        log.info("Channel scan complete: scanned={}, updated={}", scanned, updated);
        return updated;
    }

    /**
     * Paginated scan of all playlists to backfill excludedVideoCount.
     *
     * <p>Orders by {@code FieldPath.documentId()} to guarantee every document is visited.
     * For each playlist with excluded video IDs, computes the count from the array
     * and writes only {@code excludedVideoCount} via WriteBatch.update().
     * Does not touch updatedAt or overwrite the document.
     *
     * @param maxDocs Strict cap on total documents scanned
     * @param startAfterDocId Document ID to resume after, or null for fresh scan
     * @return Number of playlists updated
     */
    private int backfillPlaylistExclusionCounts(int maxDocs, String startAfterDocId) throws Exception {
        CollectionReference collection = firestore.collection("playlists");
        int updated = 0;
        int scanned = 0;
        QueryDocumentSnapshot lastDoc = null;
        String lastDocId = startAfterDocId;

        if (startAfterDocId != null) {
            log.info("Resuming playlist scan after document: {}", startAfterDocId);
        }

        while (scanned < maxDocs) {
            int remaining = maxDocs - scanned;
            int fetchLimit = Math.min(BATCH_SIZE, remaining);

            Query query = collection
                    .orderBy(FieldPath.documentId())
                    .limit(fetchLimit);

            if (lastDoc != null) {
                query = query.startAfter(lastDoc);
            } else if (startAfterDocId != null) {
                query = query.startAfter(startAfterDocId);
            }

            QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
            List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

            if (docs.isEmpty()) {
                break;
            }

            WriteBatch batch = firestore.batch();
            int batchCount = 0;

            for (QueryDocumentSnapshot doc : docs) {
                scanned++;
                Playlist playlist = doc.toObject(Playlist.class);
                List<String> excludedIds = playlist.getExcludedVideoIds();
                if (excludedIds != null && !excludedIds.isEmpty()) {
                    DocumentReference docRef = collection.document(doc.getId());
                    batch.update(docRef, "excludedVideoCount", excludedIds.size());
                    batchCount++;
                    log.debug("Backfilling playlist {} ({}): excludedVideoCount={}",
                            doc.getId(), playlist.getTitle(), excludedIds.size());
                }

                if (scanned >= maxDocs) {
                    break;
                }
            }

            if (batchCount > 0) {
                batch.commit().get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                updated += batchCount;
            }

            lastDoc = docs.get(docs.size() - 1);
            lastDocId = lastDoc.getId();
            log.info("Playlist batch: fetched={}, updated={} (total: scanned={}/{}, updated={}, lastDocId={})",
                    docs.size(), batchCount, scanned, maxDocs, updated, lastDocId);

            if (docs.size() < fetchLimit) {
                break; // No more documents in collection
            }
        }

        if (scanned >= maxDocs) {
            log.warn("Playlist scan reached cap ({} docs). "
                    + "Resume with: --startAfterPlaylist={} --cap=<N>", maxDocs, lastDocId);
        }

        log.info("Playlist scan complete: scanned={}, updated={}", scanned, updated);
        return updated;
    }

    private static int safeSize(List<?> list) {
        return list != null ? list.size() : 0;
    }
}
