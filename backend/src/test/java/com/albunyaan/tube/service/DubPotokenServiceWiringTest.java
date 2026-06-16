package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for a PROD-OUTAGE class of bug: {@link DubPotokenService} has TWO constructors (the
 * {@code @Value} injection one + a package-private test seam). Without {@code @Autowired} on the
 * injection constructor, Spring cannot choose one, falls back to a (non-existent) no-arg constructor, and
 * the whole app crash-loops with "No default constructor found".
 *
 * <p>The plain {@code DubPotokenServiceTest} can NOT catch this — it invokes the test constructor
 * directly, bypassing Spring's bean factory. This test instantiates the service as a real Spring bean so
 * the constructor-selection path is exercised.
 */
@SpringJUnitConfig(classes = {DubPotokenService.class, DubPotokenServiceWiringTest.PropsConfig.class})
class DubPotokenServiceWiringTest {

    @Configuration
    static class PropsConfig {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }

    @Autowired
    DubPotokenService service;

    @Test
    void springInstantiatesTheServiceViaTheAutowiredConstructor() {
        assertThat(service).isNotNull();
    }
}
