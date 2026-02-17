package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.util.CursorUtils;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
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

    /**
     * Find pending channels with cursor-based pagination.
     *
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing channels and next cursor
     */
    public PaginatedResult<Channel> findPendingChannelsWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getChannelsCollection()
                .whereEqualTo("status", "PENDING")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getChannelsCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: channel document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Channel> channels = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Channel channel = doc.toObject(Channel.class);
            channel.setId(doc.getId());
            channels.add(channel);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "createdAt");
            }
        }

        return new PaginatedResult<>(channels, nextCursor, hasNext);
    }

    /**
     * Find pending channels by category with cursor-based pagination.
     *
     * @param category Category ID to filter by
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing channels and next cursor
     */
    public PaginatedResult<Channel> findPendingChannelsByCategoryWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getChannelsCollection()
                .whereEqualTo("status", "PENDING")
                .whereArrayContains("categoryIds", category)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getChannelsCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: channel document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Channel> channels = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Channel channel = doc.toObject(Channel.class);
            channel.setId(doc.getId());
            channels.add(channel);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "createdAt");
            }
        }

        return new PaginatedResult<>(channels, nextCursor, hasNext);
    }

    /**
     * Find pending playlists with cursor-based pagination.
     *
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing playlists and next cursor
     */
    public PaginatedResult<Playlist> findPendingPlaylistsWithCursor(int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getPlaylistsCollection()
                .whereEqualTo("status", "PENDING")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getPlaylistsCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: playlist document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Playlist> playlists = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Playlist playlist = doc.toObject(Playlist.class);
            playlist.setId(doc.getId());
            playlists.add(playlist);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "createdAt");
            }
        }

        return new PaginatedResult<>(playlists, nextCursor, hasNext);
    }

    /**
     * Find pending playlists by category with cursor-based pagination.
     *
     * @param category Category ID to filter by
     * @param limit Number of items to fetch
     * @param cursor Encoded cursor string from previous page, or null for first page
     * @return PaginatedResult containing playlists and next cursor
     */
    public PaginatedResult<Playlist> findPendingPlaylistsByCategoryWithCursor(String category, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getPlaylistsCollection()
                .whereEqualTo("status", "PENDING")
                .whereArrayContains("categoryIds", category)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getPlaylistsCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: playlist document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Playlist> playlists = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Playlist playlist = doc.toObject(Playlist.class);
            playlist.setId(doc.getId());
            playlists.add(playlist);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "createdAt");
            }
        }

        return new PaginatedResult<>(playlists, nextCursor, hasNext);
    }

    /**
     * Find channels by submitter and status with cursor-based pagination.
     */
    public PaginatedResult<Channel> findChannelsBySubmitterAndStatus(String submittedBy, String status, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getChannelsCollection()
                .whereEqualTo("submittedBy", submittedBy)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getChannelsCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: channel document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Channel> channels = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Channel channel = doc.toObject(Channel.class);
            channel.setId(doc.getId());
            channels.add(channel);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "createdAt");
            }
        }

        return new PaginatedResult<>(channels, nextCursor, hasNext);
    }

    /**
     * Find playlists by submitter and status with cursor-based pagination.
     */
    public PaginatedResult<Playlist> findPlaylistsBySubmitterAndStatus(String submittedBy, String status, int limit, String cursor)
            throws ExecutionException, InterruptedException, TimeoutException {

        Query query = getPlaylistsCollection()
                .whereEqualTo("submittedBy", submittedBy)
                .whereEqualTo("status", status)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
                .limit(limit + 1);

        if (cursor != null && !cursor.isEmpty()) {
            CursorUtils.CursorData cursorData = CursorUtils.decode(cursor);
            if (cursorData != null) {
                DocumentReference cursorDoc = getPlaylistsCollection().document(cursorData.getId());
                var cursorSnapshot = cursorDoc.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
                if (cursorSnapshot.exists()) {
                    query = query.startAfter(cursorSnapshot);
                } else {
                    throw new IllegalArgumentException("Invalid cursor: playlist document '" + cursorData.getId() + "' not found");
                }
            }
        }

        QuerySnapshot snapshot = query.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<QueryDocumentSnapshot> docs = snapshot.getDocuments();

        boolean hasNext = docs.size() > limit;
        if (hasNext) {
            docs = docs.subList(0, limit);
        }

        List<Playlist> playlists = new ArrayList<>();
        String nextCursor = null;

        for (int i = 0; i < docs.size(); i++) {
            QueryDocumentSnapshot doc = docs.get(i);
            Playlist playlist = doc.toObject(Playlist.class);
            playlist.setId(doc.getId());
            playlists.add(playlist);

            if (i == docs.size() - 1 && hasNext) {
                nextCursor = CursorUtils.encodeFromSnapshot(doc, "createdAt");
            }
        }

        return new PaginatedResult<>(playlists, nextCursor, hasNext);
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
