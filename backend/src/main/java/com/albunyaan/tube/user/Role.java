package com.albunyaan.tube.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "role")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, unique = true)
    private RoleCode code;

    protected Role() {
        // JPA
    }

    public Role(RoleCode code) {
        this.code = code;
    }

    public UUID getId() {
        return id;
    }

    public RoleCode getCode() {
        return code;
    }
}
