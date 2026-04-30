package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.ReportStatus;
import com.albunyaan.tube.model.ReportTargetType;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Repository
public class ContentReportRepository {

    private static final String COLLECTION_REPORTS = "contentReports";
    private static final String COLLECTION_NOTIFICATIONS = "adminNotifications";
    private static final int PAGE_SIZE_DEFAULT = 20;

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;

    public ContentReportRepository(Firestore firestore, FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
    }

    private CollectionReference reports() {
        return firestore.collection(COLLECTION_REPORTS);
    }

    public ContentReport save(ContentReport report)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (report.getId() == null) {
            DocumentReference docRef = reports().document();
            report.setId(docRef.getId());
        }
        ApiFuture<WriteResult> future = reports().document(report.getId()).set(report);
        future.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return report;
    }

    public void writeAdminNotification(String reportId, ReportTargetType targetType, String targetId)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "NEW_REPORT");
        notification.put("reportId", reportId);
        notification.put("targetType", targetType.name());
        notification.put("targetId", targetId);
        notification.put("createdAt", Timestamp.now());
        ApiFuture<DocumentReference> future = firestore.collection(COLLECTION_NOTIFICATIONS).add(notification);
        future.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    public Optional<ContentReport> findById(String id)
            throws ExecutionException, InterruptedException, TimeoutException {
        DocumentSnapshot doc = reports().document(id)
                .get().get(timeoutProperties.getRead(), TimeUnit.SECONDS);
        return doc.exists() ? Optional.ofNullable(doc.toObject(ContentReport.class)) : Optional.empty();
    }

    public List<ContentReport> findByStatus(ReportStatus status, int page, int size)
            throws ExecutionException, InterruptedException, TimeoutException {
        int effectiveSize = size > 0 ? size : PAGE_SIZE_DEFAULT;
        ApiFuture<QuerySnapshot> query = reports()
                .whereEqualTo("status", status.name())
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .offset(page * effectiveSize)
                .limit(effectiveSize)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ContentReport.class);
    }

    public List<ContentReport> findByStatusAndTargetType(ReportStatus status, ReportTargetType targetType,
                                                          int page, int size)
            throws ExecutionException, InterruptedException, TimeoutException {
        int effectiveSize = size > 0 ? size : PAGE_SIZE_DEFAULT;
        ApiFuture<QuerySnapshot> query = reports()
                .whereEqualTo("status", status.name())
                .whereEqualTo("targetType", targetType.name())
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .offset(page * effectiveSize)
                .limit(effectiveSize)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ContentReport.class);
    }

    public List<ContentReport> findAll(int page, int size)
            throws ExecutionException, InterruptedException, TimeoutException {
        int effectiveSize = size > 0 ? size : PAGE_SIZE_DEFAULT;
        ApiFuture<QuerySnapshot> query = reports()
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .offset(page * effectiveSize)
                .limit(effectiveSize)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ContentReport.class);
    }

    public List<ContentReport> findAllByTargetType(ReportTargetType targetType, int page, int size)
            throws ExecutionException, InterruptedException, TimeoutException {
        int effectiveSize = size > 0 ? size : PAGE_SIZE_DEFAULT;
        ApiFuture<QuerySnapshot> query = reports()
                .whereEqualTo("targetType", targetType.name())
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .offset(page * effectiveSize)
                .limit(effectiveSize)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).toObjects(ContentReport.class);
    }

    public ContentReport update(ContentReport report)
            throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<WriteResult> future = reports().document(report.getId()).set(report);
        future.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        return report;
    }

    public long countByStatus(ReportStatus status)
            throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = reports()
                .whereEqualTo("status", status.name())
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).size();
    }

    public long countCreatedAfter(Timestamp since)
            throws ExecutionException, InterruptedException, TimeoutException {
        ApiFuture<QuerySnapshot> query = reports()
                .whereGreaterThanOrEqualTo("createdAt", since)
                .get();
        return query.get(timeoutProperties.getBulkQuery(), TimeUnit.SECONDS).size();
    }
}
