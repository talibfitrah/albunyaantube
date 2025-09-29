package com.albunyaan.tube.category;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<Category> findBySlug(String slug);

    Slice<Category> findAllByOrderBySlugAsc(Pageable pageable);

    Slice<Category> findAllBySlugGreaterThanOrderBySlugAsc(String slug, Pageable pageable);
}
