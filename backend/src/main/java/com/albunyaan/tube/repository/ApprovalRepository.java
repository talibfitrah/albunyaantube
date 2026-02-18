package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.util.CursorUtils;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Repository for approval-related queries with cursor-based pagination.
 *
 * Uses CursorUtils for opaque, URL-safe cursor tokens and the limit+1 pattern
 * for accurate hasNext detection.
 */
@Repository
public class ApprovalRepository {

    private static final String CHANNELS_COLLECTION = "channels";
    private static final String PLAYLISTS_COLLECTION = "playlists";
    private static final String VIDEOS_COLLECTION = "videos";

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public ApprovalRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getChannelsCollection() {
        return firestore.collection(CHANNELS_COLLECTION);
    }

    private CollectionReference getPlaylistsCollection() {
        return firestore.collection(PLAYLISTS_COLLECTION);
    }

    private CollectionReference getVideosCollection() {
        return firestore.collection(VIDEOS_COLLECTION);
    }

    // ========================================================================
    // Channel queries
    // ========================================================================

    public PaginatedResult<Channel> findPendingChannelsWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getChannelsCollection()
                .whereEqualTo("status", "PENDING")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getChannelsCollection(), limit, cursor, Channel.class, Channel::setId);
    }

    public PaginatedResult<Channel> findPendingChannelsByCategoryWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getChannelsCollection()
                .whereEqualTo("status", "PENDING")
                .whereArrayContains("categoryIds", category)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getChannelsCollection(), limit, cursor, Channel.class, Channel::setId);
    }

    public PaginatedResult<Channel> findChannelsBySubmitterAndStatus(String submittedBy, String status, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getChannelsCollection()
                .whereEqualTo("submittedBy", submittedBy)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getChannelsCollection(), limit, cursor, Channel.class, Channel::setId);
    }

    // ========================================================================
    // Playlist queries
    // ========================================================================

    public PaginatedResult<Playlist> findPendingPlaylistsWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getPlaylistsCollection()
                .whereEqualTo("status", "PENDING")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getPlaylistsCollection(), limit, cursor, Playlist.class, Playlist::setId);
    }

    public PaginatedResult<Playlist> findPendingPlaylistsByCategoryWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getPlaylistsCollection()
                .whereEqualTo("status", "PENDING")
                .whereArrayContains("categoryIds", category)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getPlaylistsCollection(), limit, cursor, Playlist.class, Playlist::setId);
    }

    public PaginatedResult<Playlist> findPlaylistsBySubmitterAndStatus(String submittedBy, String status, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getPlaylistsCollection()
                .whereEqualTo("submittedBy", submittedBy)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getPlaylistsCollection(), limit, cursor, Playlist.class, Playlist::setId);
    }

    // ========================================================================
    // Video queries
    // ========================================================================

    public PaginatedResult<Video> findPendingVideosWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getVideosCollection()
                .whereEqualTo("status", "PENDING")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getVideosCollection(), limit, cursor, Video.class, Video::setId);
    }

    public PaginatedResult<Video> findPendingVideosByCategoryWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getVideosCollection()
                .whereEqualTo("status", "PENDING")
                .whereArrayContains("categoryIds", category)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getVideosCollection(), limit, cursor, Video.class, Video::setId);
    }

    public PaginatedResult<Video> findVideosBySubmitterAndStatus(String submittedBy, String status, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query query = getVideosCollection()
                .whereEqualTo("submittedBy", submittedBy)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING);
        return executePaginatedQuery(query, getVideosCollection(), limit, cursor, Video.class, Video::setId);
    }

    // ========================================================================
    // Count queries
    // ========================================================================

    /**
     * Count all pending items across channels, playlists, and videos.
     * Fires all three count queries in parallel for better latency.
     */
    public long countAllPending()
            throws ExecutionException, InterruptedException, TimeoutException {
        var channelsFuture = getChannelsCollection()
                .whereEqualTo("status", "PENDING")
                .count()
                .get();
        var playlistsFuture = getPlaylistsCollection()
                .whereEqualTo("status", "PENDING")
                .count()
                .get();
        var videosFuture = getVideosCollection()
                .whereEqualTo("status", "PENDING")
                .count()
                .get();

        long channels = channelsFuture.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();
        long playlists = playlistsFuture.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();
        long videos = videosFuture.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();
        return channels + playlists + videos;
    }

    // ========================================================================
    // Generic pagination helper
    // ========================================================================

    /**
     * Execute a paginated Firestore query with cursor support.
     * Uses the limit+1 pattern for accurate hasNext detection.
     *
     * @param baseQuery Pre-built query with filters and ordering (without limit)
     * @param collection The collection reference (for cursor document lookup)
     * @param limit Number of items to return
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @param clazz The model class to deserialize documents to
     * @param idSetter Function to set the document ID on the deserialized object
     * @return PaginatedResult with items, next cursor, and hasNext flag
     */
    private <T> PaginatedResult<T> executePaginatedQuery(
            Query baseQuery,
            CollectionReference collection,
            int limit,
            String cursor,
            Class<T> clazz,
            BiConsumer<T, String> idSetter)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = baseQuery.limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData == null) {
                throw new IllegalArgumentException("Invalid cursor: failed to decode");
            }

            // Use cursor field values for startAfter() so pagination works even if the
            // cursor document has been deleted. The cursor contains the ordering field
            // values (createdAt) and document ID, which is the full ordering tuple.
            Object[] startAfterValues = CursorUtils.getStartAfterValues(cursorData, "createdAt");
            if (startAfterValues != null && startAfterValues.length > 0 && startAfterValues[0] != null) {
                // startAfter with (createdAt, documentId) to match the query's orderBy clause
                query = query.startAfter(startAfterValues[0], cursorData.getId());
            } else {
                // Cursor is missing ordering fields - this is an invalid/corrupt cursor
                throw new IllegalArgumentException("Invalid cursor: missing ordering fields");
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<T> items = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            T item = doc.toObject(clazz);
            idSetter.accept(item, doc.getId());
            items.add(item);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "createdAt");
            }
        }

        return new PaginatedResult<>(items, nextCursor, hasNext);
    }

    /**
     * Generic paginated result wrapper.
     */
    public static class PaginatedResult<T> {
        private final List<T> items;
        private final String nextCursor;
        private final boolean hasNext;

        public PaginatedResult(List<T> items, String nextCursor, boolean hasNext) {
            this.items = items;
            this.nextCursor = nextCursor;
            this.hasNext = hasNext;
        }

        public List<T> getItems() {
            return items;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public boolean hasNext() {
            return hasNext;
        }
    }
}
