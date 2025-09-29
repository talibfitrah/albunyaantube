package com.albunyaan.tube.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.albunyaan.tube.support.IntegrationTestSupport;
import com.albunyaan.tube.user.RoleCode;
import com.albunyaan.tube.user.UserRepository;
import com.albunyaan.tube.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class InitialAdminProvisionerTest extends IntegrationTestSupport {

    @Autowired
    private InitialAdminProvisioner provisioner;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void resetAdminUser() {
        var admin = userRepository.findByEmailIgnoreCase("admin@albunyaan.tube").orElseThrow();
        admin.updatePasswordHash(passwordEncoder.encode("Obsolete!321"));
        admin.suspend();
        admin.getRoles().clear();
        userRepository.save(admin);
    }

    @Test
    void reactivatesAndRepairsDefaultAdminAccount() throws Exception {
        provisioner.run();

        var admin = userRepository.findByEmailIgnoreCase("admin@albunyaan.tube").orElseThrow();

        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(passwordEncoder.matches("ChangeMe!123", admin.getPasswordHash())).isTrue();
        assertThat(admin.getRoles()).extracting(role -> role.getCode()).contains(RoleCode.ADMIN);
    }
}
