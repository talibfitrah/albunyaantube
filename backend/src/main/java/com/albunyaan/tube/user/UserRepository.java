package com.albunyaan.tube.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface UserRepository extends JpaRepository<User, UUID> {
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByIdWithRoles(UUID id);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByIdAndStatus(UUID id, UserStatus status);

    @EntityGraph(attributePaths = "roles")
    List<User> findAllByRolesCode(RoleCode roleCode);
}
