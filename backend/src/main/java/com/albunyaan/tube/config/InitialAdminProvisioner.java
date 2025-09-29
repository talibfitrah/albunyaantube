package com.albunyaan.tube.config;

import com.albunyaan.tube.user.Role;
import com.albunyaan.tube.user.RoleCode;
import com.albunyaan.tube.user.RoleRepository;
import com.albunyaan.tube.user.User;
import com.albunyaan.tube.user.UserRepository;
import com.albunyaan.tube.user.UserStatus;
import java.util.Locale;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class InitialAdminProvisioner implements CommandLineRunner {

    private final InitialAdminProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public InitialAdminProvisioner(
        InitialAdminProperties properties,
        UserRepository userRepository,
        RoleRepository roleRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        var configuredEmail = properties.getEmail();
        if (!StringUtils.hasText(configuredEmail)) {
            return;
        }

        var email = configuredEmail.trim().toLowerCase(Locale.ROOT);
        var rawPassword = properties.getPassword();
        if (!StringUtils.hasText(rawPassword)) {
            throw new IllegalStateException("app.security.initial-admin.password must not be blank");
        }

        var displayName = StringUtils.hasText(properties.getDisplayName())
            ? properties.getDisplayName()
            : "Administrator";
        var existingUser = userRepository.findByEmailIgnoreCase(email);
        var encodedPassword = passwordEncoder.encode(rawPassword);

        User admin = existingUser.orElseGet(() -> new User(email, encodedPassword, displayName));

        if (existingUser.isPresent()) {
            if (properties.isResetPasswordOnStartup() || !passwordEncoder.matches(rawPassword, admin.getPasswordHash())) {
                admin.updatePasswordHash(encodedPassword);
            }
        }

        if (!StringUtils.hasText(admin.getDisplayName())) {
            admin.updateDisplayName(displayName);
        }

        if (admin.getStatus() != UserStatus.ACTIVE) {
            admin.activate();
        }

        var adminRole = roleRepository
            .findByCode(RoleCode.ADMIN)
            .orElseGet(() -> roleRepository.save(new Role(RoleCode.ADMIN)));

        if (admin.getRoles().stream().noneMatch(role -> role.getCode() == RoleCode.ADMIN)) {
            admin.assignRole(adminRole);
        }

        userRepository.save(admin);
    }
}
