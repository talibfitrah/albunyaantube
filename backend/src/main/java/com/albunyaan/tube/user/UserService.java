package com.albunyaan.tube.user;

import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        var user = new User(email.toLowerCase(), passwordEncoder.encode(rawPassword), displayName);
        var adminRole = roleRepository
            .findByCode(RoleCode.ADMIN)
            .orElseThrow(() -> new IllegalStateException("ADMIN role missing"));
        user.getRoles().add(adminRole);
        return userRepository.save(user);
    }

    public User findById(UUID id) {
        return userRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));
    }
}
