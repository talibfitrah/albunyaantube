package com.albunyaan.tube.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "roles")
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdWithRoles(@Param("id") UUID id);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findByIdAndStatus(UUID id, UserStatus status);

    @EntityGraph(attributePaths = "roles")
    List<User> findAllByRolesCode(RoleCode roleCode);
}
