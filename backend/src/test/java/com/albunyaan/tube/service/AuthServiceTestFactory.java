package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.cache.CacheManager;

import static org.mockito.Mockito.mock;

/**
 * Package-private factory for AuthService unit tests.
 * Stubs all constructor dependencies except the ones under test.
 */
class AuthServiceTestFactory {

    static AuthService with(FirebaseAuth firebaseAuth, AuditLogService auditLogService) {
        return new AuthService(
                firebaseAuth,
                mock(UserRepository.class),
                auditLogService,
                mock(AuditLogRepository.class),
                mock(Firestore.class),
                mock(CacheManager.class),
                mock(FirestoreTimeoutProperties.class),
                mock(MailService.class)
        );
    }
}
