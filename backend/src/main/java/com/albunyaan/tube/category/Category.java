package com.albunyaan.tube.category;

import com.albunyaan.tube.common.AuditableEntity;
import com.albunyaan.tube.common.LocaleMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "category")
public class Category extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Convert(converter = LocaleMapConverter.class)
    @Column(name = "name", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> name = new HashMap<>();

    protected Category() {
        // JPA
    }

    public Category(String slug, Map<String, String> name) {
        this.slug = slug;
        this.name.putAll(name);
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public Map<String, String> getName() {
        return Collections.unmodifiableMap(name);
    }
}
