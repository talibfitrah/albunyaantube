package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.User;
import org.springframework.cache.annotation.CacheEvict;
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

    /**
     * Persist a User document.
     *
     * <p>F21: also evicts the {@code userStatus} cache entry for this uid so
     * post-save reads via {@link #findByUid(String)} return the fresh state.
     *
     * <p>Pre-F21 the cache was self-coherent because {@code findByUid}
     * returned the SAME mutable reference on every cache hit — mutating it
     * also mutated the cached object. F10 fixed that by returning a
     * defensive copy, but in doing so it broke the invariant: callers that
     * mutate the returned copy and then call {@code save} have an
     * up-to-date Firestore document but a stale cache entry for the 60s
     * TTL. {@code AuthService.recordLogin} is the canonical example
     * (read user → user.recordLogin() → save). The lifecycle methods
     * (block/unblock/etc) write directly via {@code tx.set} inside a
     * transaction so they don't go through this path; they have their own
     * explicit {@code evictUserStatus} call.
     *
     * <p>Eviction cost: one extra Firestore round-trip on the next read of
     * the affected uid. Acceptable — much cheaper than allowing stale data
     * to surface.
     *
     * <p>Cubic R-final4 P2 — known sub-millisecond stale-read window. Spring
     * {@code @CacheEvict} with default {@code beforeInvocation=false} fires
     * the eviction AFTER successful method return, so the order is:
     * <ol>
     *   <li>Firestore commit returns</li>
     *   <li>{@code save} returns to the caller</li>
     *   <li>AOP aspect evicts the cache entry</li>
     * </ol>
     * Between (2) and (3) a concurrent {@code findByUid(uid)} call could
     * observe the pre-write cached value. The window is sub-millisecond
     * because the eviction is in-process Caffeine. Accepted because:
     * <ul>
     *   <li>Lifecycle methods (block/unblock/etc) bypass this path entirely
     *       — they use {@code tx.set} + explicit {@code evictUserStatus} in
     *       a {@code finally} block, with the eviction happening before
     *       any caller can observe the post-tx state.</li>
     *   <li>The only callers that go through {@code save()} are recordLogin
     *       and createUser. Both write data tolerant of brief staleness
     *       (lastLoginAt / new-user genesis row that the cache didn't
     *       have anyway).</li>
     * </ul>
     * Tightening this further (switch to {@code @CachePut} or manual
     * {@code cacheManager.getCache(…).put(uid, Optional.of(user))} inside
     * the method body) is plausible but requires aligning the cache value
     * type with {@code loadByUid}'s {@code Optional<User>} return —
     * {@code @CachePut} on a {@code User}-returning method caches the
     * naked {@code User} and breaks the {@code Optional.map} chain in
     * {@code findByUid}. Not worth the ripple for a sub-ms window.
     */
    @CacheEvict(value = "userStatus", key = "#user.uid")
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
     * Plan G review-fix (codex P1 lost-update) — field-level merge update.
     *
     * <p>{@link #save(User)} is a whole-document overwrite via {@code .set(user)}.
     * Under the new {@code PUT /api/account/profile} flow, two concurrent edits
     * (e.g. mobile + tablet) racing through the read-modify-write window in
     * {@code AccountProfileService.updateProfile} could each load the same
     * pre-edit snapshot and then overwrite each other — name-only edit and
     * DOB-only edit would lose one field.
     *
     * <p>This method issues a Firestore {@code .update(fields)} which merges
     * at the field level, so concurrent edits that touch disjoint fields are
     * both preserved. Touches {@code updatedAt} with a server-side timestamp
     * so the audit/read path sees a fresh value without an extra round-trip.
     *
     * <p>Cache key matches the existing {@link #save(User)} eviction so the
     * {@code userStatus} cache (used by {@code FirebaseAuthFilter}) is
     * invalidated identically.
     */
    @CacheEvict(value = "userStatus", key = "#uid")
    public void updateFields(String uid, java.util.Map<String, Object> fields)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("updateFields requires a non-blank uid");
        }
        java.util.Map<String, Object> withTouch = new java.util.LinkedHashMap<>(fields);
        withTouch.put("updatedAt", com.google.cloud.Timestamp.now());
        ApiFuture<WriteResult> result = getCollection()
                .document(uid)
                .update(withTouch);
        result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
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
     * Cubic R5 P1 — bypass the {@code userStatus} cache for callers that must
     * see writes from other JVM instances immediately.
     *
     * <p>The default {@code @Cacheable} path returns a per-JVM Caffeine entry.
     * If another node promotes a user to admin (or blocks one), only that
     * node's cache is evicted; this node still serves the stale role/status
     * for up to the configured TTL. For most reads the staleness window is
     * acceptable, but the bulk-action admin-target guard MUST see the live
     * role — otherwise a just-promoted admin can be silently bulk-blocked
     * from a different node.
     *
     * <p>Distinct from the global {@code FirebaseAuthFilter} multi-instance
     * staleness (cubic R5 P0, deferred to Tier C): that one needs a shared
     * Redis cache + pub/sub eviction; this one is a single hot-path read so
     * a direct Firestore round-trip is fine.
     *
     * <p>Returns a defensive copy, matching {@link #findByUid} semantics.
     */
    public Optional<User> findByUidUncached(String uid)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = getCollection().document(uid);
        User user = docRef.get()
                .get(timeoutProperties.getRead(), TimeUnit.SECONDS)
                .toObject(User.class);
        return Optional.ofNullable(user).map(User::copy);
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

    /**
     * Atomically read-then-create the user doc for {@code uid}: if the doc
     * already exists, return it; otherwise persist {@code factory.get()} as a
     * fresh doc inside a Firestore transaction so concurrent first-time
     * /api/account/me callers cannot race and clobber each other's
     * {@code createdAt}/lifecycle fields (cubic R4 P2). Uses
     * {@code firestore.runTransaction} so the read + conditional write commit
     * atomically; Firestore's optimistic concurrency aborts and retries
     * losers.
     *
     * <p><b>Factory contract</b> (cubic R5 P2): the supplier MUST be pure —
     * no side effects, no audit emissions, no external state mutation. Firestore
     * may invoke the transaction lambda multiple times on conflict; any side
     * effect inside the factory will fire once per retry. Capture audit/event
     * emissions in the controller AFTER {@code getOrCreate} returns.
     *
     * <p>The cache eviction matches {@link #save(User)} so the next
     * {@code findByUid} call returns the freshly-written user rather than the
     * pre-create empty Optional.
     */
    @CacheEvict(value = "userStatus", key = "#uid")
    public User getOrCreate(String uid, java.util.function.Supplier<User> factory)
            throws ExecutionException, InterruptedException, TimeoutException {
        com.google.cloud.firestore.DocumentReference ref = getCollection().document(uid);
        return firestore.runTransaction(tx -> {
            com.google.cloud.firestore.DocumentSnapshot snap = tx.get(ref).get();
            if (snap.exists()) {
                return snap.toObject(User.class);
            }
            User fresh = factory.get();
            fresh.touch();
            tx.set(ref, fresh);
            return fresh;
        }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
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

