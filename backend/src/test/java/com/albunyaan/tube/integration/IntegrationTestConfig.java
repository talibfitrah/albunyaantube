package com.albunyaan.tube.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * BACKEND-TEST-01: Integration Test Configuration
 *
 * Provides beans needed by integration tests that are not
 * part of the main application context.
 */
@TestConfiguration
public class IntegrationTestConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
