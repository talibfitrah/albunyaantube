package com.albunyaan.tube.repository;

import com.albunyaan.tube.model.AuditLog;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * FIREBASE-MIGRATE-04: Audit Log Repository (Firestore)
 */
@Repository
public class AuditLogRepository {

    private static final String COLLECTION_NAME = "audit_logs";
    private final Firestore firestore;

    public AuditLogRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    public AuditLog save(AuditLog auditLog) throws ExecutionException, InterruptedException {
        if (auditLog.getId() == null) {
            DocumentReference docRef = getCollection().document();
            auditLog.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(auditLog.getId())
                .set(auditLog);

        result.get();
        return auditLog;
    }

    public List<AuditLog> findAll(int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(AuditLog.class);
    }

    public List<AuditLog> findByActor(String actorUid, int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("actorUid", actorUid)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(AuditLog.class);
    }

    public List<AuditLog> findByEntityType(String entityType, int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("entityType", entityType)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(AuditLog.class);
    }

    public List<AuditLog> findByAction(String action, int limit) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("action", action)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit)
                .get();

        return query.get().toObjects(AuditLog.class);
    }
}

