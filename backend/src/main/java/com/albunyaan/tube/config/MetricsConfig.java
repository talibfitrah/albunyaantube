package com.albunyaan.tube.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics configuration for monitoring application performance.
 * Integrates with Spring Boot Actuator and Prometheus for observability.
 */
@Configuration
public class MetricsConfig {

    /**
     * Customize the meter registry with common tags
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                .commonTags(
                        "application", "albunyaan-tube",
                        "service", "backend-api"
                );
    }
}
