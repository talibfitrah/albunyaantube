package com.albunyaan.tube.user;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public User createAdmin(String email, String rawPassword, String displayName) {
        return createUserWithRole(email, rawPassword, displayName, RoleCode.ADMIN);
    }

    @Transactional
    public User createModerator(String email, String rawPassword, String displayName) {
        return createUserWithRole(email, rawPassword, displayName, RoleCode.MODERATOR);
    }

    @Transactional
    public User updateModeratorStatus(UUID userId, UserStatus status) {
        var user = userRepository
            .findByIdWithRoles(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));

        if (!user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet()).contains(RoleCode.MODERATOR)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not a moderator: " + userId);
        }

        user.updateStatus(status);
        return userRepository.save(user);
    }

    public List<User> listModerators() {
        return userRepository.findAllByRolesCode(RoleCode.MODERATOR);
    }

    public User findById(UUID id) {
        return userRepository
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id));
    }

    private User createUserWithRole(String email, String rawPassword, String displayName, RoleCode roleCode) {
        userRepository
            .findByEmailIgnoreCase(email)
            .ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists with email: " + email);
            });

        var user = new User(email.toLowerCase(), passwordEncoder.encode(rawPassword), displayName);
        var role = roleRepository
            .findByCode(roleCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, roleCode + " role missing"));
        user.assignRole(role);
        return userRepository.save(user);
    }
}
