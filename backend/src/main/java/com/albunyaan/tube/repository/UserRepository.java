package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.User;
import org.springframework.cache.annotation.Cacheable;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.AggregateQuery;
import com.google.cloud.firestore.AggregateQuerySnapshot;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FIREBASE-MIGRATE-03: User Repository (Firestore)
 *
 * All Firestore operations use configurable, operation-specific timeouts to prevent
 * indefinite blocking and thread pool exhaustion in case of network issues or Firestore unavailability.
 */
@Repository
public class UserRepository {

    private static final String COLLECTION_NAME = "users";
    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    /**
     * F10: ApplicationContext is used only to retrieve the proxied self-reference
     * so {@code @Cacheable} on {@link #loadByUid(String)} fires when invoked from
     * {@link #findByUid(String)}. Direct {@code this.loadByUid(uid)} would bypass
     * the cache aspect (proxy unwraps to the raw target). Field-injected on
     * purpose: ctor-injection would force the existing UserRepositoryTest to be
     * rewritten with a Spring application context.
     */
    @Autowired
    private ApplicationContext applicationContext;

    public UserRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public User save(User user) throws ExecutionException, InterruptedException, TimeoutException {
        user.touch();

        // Use Firebase UID as document ID
        ApiFuture<WriteResult> result = getCollection()
                .document(user.getUid())
                .set(user);

        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return user;
    }

    /**
     * F10: returns a defensive copy of the cached User on every call.
     *
     * Pre-fix, @Cacheable cached Optional<User> wrapping a mutable User. Every
     * cache hit returned the SAME mutable reference, so AuthService.recordLogin
     * (which calls user.recordLogin() then save) was mutating the cached User
     * in place. Concurrent callers saw torn state for up to the 60s TTL window.
     *
     * Fix shape: a package-private @Cacheable {@link #loadByUid(String)} owns
     * the cache layer. The public findByUid delegates through the Spring proxy
     * (cacheManager-driven {@code self-call via this::loadByUid won't apply
     * @Cacheable}, so we use the injected self-reference) and ALWAYS clones
     * before returning. The cached value stays canonical; callers receive
     * independent copies they can freely mutate without corrupting cache.
     */
    public Optional<User> findByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        Optional<User> cached = self().loadByUid(uid);
        // Defensive copy — Optional.map allocates a new Optional too.
        return cached.map(User::copy);
    }

    /**
     * Package-private cached loader. Not for direct call by other classes —
     * callers must go through {@link #findByUid(String)} which adds the
     * defensive copy on the way out.
     *
     * <p>F19: access modifier is now package-private (was {@code public}
     * pre-fix). The docstring already said "package-private" but the keyword
     * said otherwise, so external callers could trust the comment and skip
     * the defensive copy — re-introducing the F10 mutable-shared-reference
     * bug. Package-private both honours the docstring and gives the compiler
     * teeth to enforce it.
     *
     * <p>Spring's CGLIB AOP proxy works with package-private methods just
     * fine (subclass-based proxying). JDK dynamic proxies would have required
     * a public method on an interface, but {@code @Cacheable} on a concrete
     * bean uses CGLIB by default.
     */
    @Cacheable(value = "userStatus", key = "#uid")
    Optional<User> loadByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(uid);
        // Single document reads: use shorter timeout (2 seconds)
        User user = docRef.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObject(User.class);
        return Optional.ofNullable(user);
    }

    /**
     * Returns the proxied self-reference so {@code @Cacheable} on
     * {@link #loadByUid} fires when called from a sibling method. Without this,
     * a direct {@code this.loadByUid(uid)} call would bypass the cache aspect.
     *
     * If {@code applicationContext} is null (unit test that constructs the
     * repository directly without a Spring context), fall back to {@code this}.
     * The cache aspect won't fire in that scenario — acceptable for unit tests
     * that mock Firestore directly.
     */
    private UserRepository self() {
        return applicationContext == null
                ? this
                : applicationContext.getBean(UserRepository.class);
    }

    public Optional<User> findByEmail(String email) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("email", email)
                .limit(1)
                .get();

        List<User> users = query.get(timeoutProperties.getRead(), TimeUnit.SECONDS).toObjects(User.class);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.get(0));
    }

    /**
     * Find all users (returns complete result set).
     * For paginated queries, use {@link #findAll(int, int)} instead.
     */
    public List<User> findAll() throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find all users, optionally including soft-deleted ones.
     *
     * D3: uses a whereIn whitelist over the three live statuses rather than
     * whereNotEqualTo("status", "deleted"), because != excludes documents with
     * a missing/null status field — which would hide pre-backfill legacy users.
     *
     * @param includeDeleted when true, delegates to findAll() (no filter).
     *                       when false, returns only active/blocked/pending_profile users.
     */
    public List<User> findAll(boolean includeDeleted) throws ExecutionException, InterruptedException, TimeoutException {
        if (includeDeleted) {
            return findAll();
        }
        QuerySnapshot snap = firestore.collection(COLLECTION_NAME)
                .whereIn("status", List.of("active", "blocked", "pending_profile"))
                .get()
                .get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<User> users = new ArrayList<>();
        for (QueryDocumentSnapshot doc : snap.getDocuments()) {
            users.add(doc.toObject(User.class));
        }
        return users;
    }

    /**
     * Find all users with pagination (paginated query).
     * @param limit Maximum number of users to return
     * @param offset Starting offset for pagination
     */
    public List<User> findAll(int limit, int offset) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .offset(offset)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find users by role (returns complete result set).
     * For paginated queries, use {@link #findByRole(String, int, int)} instead.
     * @param role User role to filter by
     */
    public List<User> findByRole(String role) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("role", role)
                .orderBy("displayName", Query.Direction.ASCENDING)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Find users by role with pagination (paginated query).
     * @param role User role to filter by
     * @param limit Maximum number of users to return
     * @param offset Starting offset for pagination
     */
    public List<User> findByRole(String role, int limit, int offset) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("role", role)
                .orderBy("displayName", Query.Direction.ASCENDING)
                .limit(limit)
                .offset(offset)
                .get();

        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(User.class);
    }

    /**
     * Paginated cursor query ordered by document ID, used by UserBackfillMigration.
     * Returns up to {@code batchSize} users whose document ID is strictly after {@code cursor}.
     * Pass {@code cursor = null} to start from the beginning.
     *
     * Note: uses {@link FieldPath#documentId()} rather than "uid" because the uid
     * field is annotated {@code @DocumentId} and is therefore NOT stored as a field
     * inside the Firestore document — only as the document ID. orderBy("uid") would
     * find no results on any collection ordered this way.
     */
    public List<User> findAfter(String cursor, int batchSize)
            throws ExecutionException, InterruptedException, TimeoutException {
        Query q = getCollection()
                .orderBy(FieldPath.documentId())
                .limit(batchSize);
        if (cursor != null) {
            q = q.startAfter(cursor);
        }
        QuerySnapshot snap = q.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        List<User> users = new ArrayList<>();
        for (QueryDocumentSnapshot doc : snap.getDocuments()) {
            User u = doc.toObject(User.class);
            // @DocumentId is populated by toObject(), but set explicitly as a safety net
            // in case the mapping is skipped on partial documents.
            if (u.getUid() == null) {
                u.setUid(doc.getId());
            }
            users.add(u);
        }
        return users;
    }

    /**
     * Persist a User document without calling {@link User#touch()}.
     * Used by integration test fixtures to seed legacy-shaped documents that
     * intentionally lack timestamps, so the migration can normalise them.
     * Do NOT use in production code — always prefer {@link #save(User)}.
     */
    public void saveRaw(User user) throws ExecutionException, InterruptedException, TimeoutException {
        getCollection()
                .document(user.getUid())
                .set(user)
                .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    public void deleteByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<WriteResult> result = getCollection().document(uid).delete();
        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    public boolean existsByUid(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(uid);
        // Single document reads: use shorter timeout (2 seconds)
        return docRef.get().get(timeoutProperties.getRead(), TimeUnit.SECONDS).exists();
    }

    /**
     * Count all users (including soft-deleted) using server-side aggregation.
     *
     * F11: pre-fix this was the only count. Dashboard "total users" mixed in
     * deleted users, while GET /api/admin/users hid them — so the dashboard
     * "total" was always larger than the visible row count. Use
     * {@link #countAll(boolean)} with {@code includeDeleted=false} for live
     * dashboard totals.
     */
    public long countAll() throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery countQuery = getCollection().count();
        AggregateQuerySnapshot snapshot = countQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS);
        return snapshot.getCount();
    }

    /**
     * Count users, optionally excluding soft-deleted ones.
     *
     * D3 parity: matches {@link #findAll(boolean)}'s whitelist semantics
     * (uses {@code whereIn("status", [active, blocked, pending_profile])})
     * rather than {@code whereNotEqualTo("status", "deleted")} — Firestore's
     * {@code !=} excludes documents with a missing/null status field, which
     * would silently drop pre-backfill legacy users from the count.
     *
     * @param includeDeleted when true, delegates to {@link #countAll()}
     *                       (no filter). When false, counts only
     *                       active/blocked/pending_profile users.
     */
    public long countAll(boolean includeDeleted) throws ExecutionException, InterruptedException, TimeoutException {
        if (includeDeleted) return countAll();
        AggregateQuery q = firestore.collection(COLLECTION_NAME)
                .whereIn("status", List.of("active", "blocked", "pending_profile"))
                .count();
        return q.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();
    }

    /**
     * Count soft-deleted users. Useful for admin dashboards that want to
     * expose both "live" and "deleted" totals.
     */
    public long countDeleted() throws ExecutionException, InterruptedException, TimeoutException {
        AggregateQuery q = getCollection().whereEqualTo("status", "deleted").count();
        return q.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();
    }

    /**
     * Count users with admin or moderator role using server-side aggregation.
     *
     * F9: role values are stored canonical-lowercase post-Plan-A (D6). Pre-fix
     * the query was whereEqualTo("role", "ADMIN") / "MODERATOR" — uppercase
     * literals that no longer match any document, so the admin dashboard
     * showed "0 moderators" regardless of reality.
     */
    public long countModerators() throws ExecutionException, InterruptedException, TimeoutException {
        // Note: Firestore doesn't support OR queries, so we count separately and sum.
        // D6: role values in Firestore are canonical lowercase.
        AggregateQuery adminCountQuery = getCollection()
                .whereEqualTo("role", "admin")
                .count();
        AggregateQuery modCountQuery = getCollection()
                .whereEqualTo("role", "moderator")
                .count();

        long adminCount = adminCountQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();
        long modCount = modCountQuery.get().get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).getCount();

        return adminCount + modCount;
    }
}

