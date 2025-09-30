package com.albunyaan.tube.audit;

import com.albunyaan.tube.common.AuditableEntity;
import com.albunyaan.tube.common.JsonMapConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_entry")
public class AuditEntry extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 64)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 64)
    private AuditResourceType resourceType;

    @Column(name = "resource_id", nullable = false, length = 128)
    private String resourceId;

    @Column(name = "resource_slug", length = 128)
    private String resourceSlug;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "actor_display_name")
    private String actorDisplayName;

    @Column(name = "actor_status")
    private String actorStatus;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "admin_audit_entry_actor_roles", joinColumns = @JoinColumn(name = "audit_entry_id"))
    @Column(name = "role", nullable = false)
    private Set<String> actorRoles = new LinkedHashSet<>();

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "details", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> details;

    @Column(name = "trace_id")
    private String traceId;

    protected AuditEntry() {
        // JPA
    }

    public AuditEntry(
        AuditAction action,
        AuditResourceType resourceType,
        String resourceId,
        String resourceSlug,
        UUID actorId,
        String actorEmail,
        String actorDisplayName,
        String actorStatus,
        Set<String> actorRoles,
        Map<String, Object> details,
        String traceId
    ) {
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceSlug = resourceSlug;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.actorDisplayName = actorDisplayName;
        this.actorStatus = actorStatus;
        if (actorRoles != null) {
            this.actorRoles.addAll(actorRoles);
        }
        this.details = details;
        this.traceId = traceId;
    }

    public UUID getId() {
        return id;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditResourceType getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getResourceSlug() {
        return resourceSlug;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public String getActorDisplayName() {
        return actorDisplayName;
    }

    public String getActorStatus() {
        return actorStatus;
    }

    public Set<String> getActorRoles() {
        return Set.copyOf(actorRoles);
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public String getTraceId() {
        return traceId;
    }
}
