package com.albunyaan.tube.repository;

import com.albunyaan.tube.model.Category;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * FIREBASE-MIGRATE-03: Category Repository (Firestore)
 *
 * Handles CRUD operations for categories using Firestore.
 */
@Repository
public class CategoryRepository {

    private static final String COLLECTION_NAME = "categories";
    private final Firestore firestore;

    public CategoryRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference getCollection() {
        return firestore.collection(COLLECTION_NAME);
    }

    /**
     * Save or update a category
     */
    public Category save(Category category) throws ExecutionException, InterruptedException {
        category.touch();
        category.setTopLevel(category.getParentCategoryId() == null);

        if (category.getId() == null) {
            // Create new document with auto-generated ID
            DocumentReference docRef = getCollection().document();
            category.setId(docRef.getId());
        }

        ApiFuture<WriteResult> result = getCollection()
                .document(category.getId())
                .set(category);

        result.get(); // Wait for write to complete
        return category;
    }

    /**
     * Find category by ID
     */
    public Optional<Category> findById(String id) throws ExecutionException, InterruptedException {
        DocumentReference docRef = getCollection().document(id);
        Category category = docRef.get().get().toObject(Category.class);
        return Optional.ofNullable(category);
    }

    /**
     * Find all categories
     */
    public List<Category> findAll() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .orderBy("name", Query.Direction.ASCENDING)
                .get();

        return query.get().toObjects(Category.class);
    }

    /**
     * Find top-level categories (no parent)
     */
    public List<Category> findTopLevel() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("parentCategoryId", null)
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .get();

        return query.get().toObjects(Category.class);
    }

    /**
     * Find subcategories of a parent category
     */
    public List<Category> findByParentId(String parentId) throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection()
                .whereEqualTo("parentCategoryId", parentId)
                .orderBy("displayOrder", Query.Direction.ASCENDING)
                .get();

        return query.get().toObjects(Category.class);
    }

    /**
     * Delete category by ID
     */
    public void deleteById(String id) throws ExecutionException, InterruptedException {
        ApiFuture<WriteResult> result = getCollection().document(id).delete();
        result.get();
    }

    /**
     * Check if category exists
     */
    public boolean existsById(String id) throws ExecutionException, InterruptedException {
        DocumentReference docRef = getCollection().document(id);
        return docRef.get().get().exists();
    }

    /**
     * Count categories
     */
    public long count() throws ExecutionException, InterruptedException {
        ApiFuture<QuerySnapshot> query = getCollection().get();
        return query.get().size();
    }
}

