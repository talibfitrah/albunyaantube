import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.time.Duration

plugins {
    id("java")
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    id("io.gatling.gradle") version "3.10.3"
    id("org.openapi.generator") version "7.10.0"
}

group = "com.albunyaan"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
    // Required for Firebase and Google Cloud dependencies
    google()
    // Required for NewPipeExtractor
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Dependency constraints - P0-T1: Enforce specific versions for transitive dependencies
    constraints {
        implementation("io.netty:netty-common:4.1.128.Final") {
            because("Enforce Netty version from Firebase/Spring Boot to prevent version conflicts and CVEs in 4.1.109")
        }
        implementation("io.netty:netty-handler:4.1.128.Final") {
            because("Enforce Netty version from Firebase/Spring Boot to prevent version conflicts and CVEs in 4.1.109")
        }
        implementation("io.netty:netty-transport:4.1.128.Final") {
            because("Enforce Netty version from Firebase/Spring Boot to prevent version conflicts and CVEs in 4.1.109")
        }
        implementation("io.netty:netty-codec:4.1.128.Final") {
            because("Enforce Netty version from Firebase/Spring Boot to prevent version conflicts and CVEs in 4.1.109")
        }
        implementation("io.netty:netty-buffer:4.1.128.Final") {
            because("Enforce Netty version from Firebase/Spring Boot to prevent version conflicts and CVEs in 4.1.109")
        }
        implementation("io.netty:netty-resolver:4.1.128.Final") {
            because("Enforce Netty version from Firebase/Spring Boot to prevent version conflicts and CVEs in 4.1.109")
        }
    }

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Caching - BACKEND-PERF-01: Caffeine for dev, Redis for prod
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("io.lettuce:lettuce-core")

    // Firebase Admin SDK for Authentication and Firestore (replaces PostgreSQL)
    implementation("com.google.firebase:firebase-admin:9.2.0")

    // NewPipeExtractor for YouTube content extraction (no API key required)
    // Pinned to specific commit for reproducible builds (dev-SNAPSHOT is non-deterministic)
    // Commit from 2025-01-15: includes YouTube InnerTube API fixes
    // To update: check https://github.com/TeamNewPipe/NewPipeExtractor/commits/dev
    implementation("com.github.TeamNewPipe:NewPipeExtractor:a0607b2c49e757d368e9dfac241792d42d575236")

    // OkHttp for NewPipe's HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Embedded Redis - BACKEND-PERF-01: Enable for testing
    testImplementation("com.github.kstyrc:embedded-redis:0.6")
}

gatling {
    logLevel = "INFO"
}

tasks.test {
    useJUnitPlatform {
        // AGENTS.md: Exclude integration tests by default (require Firebase emulator)
        // Run with -Pintegration=true to include integration tests
        if (!project.hasProperty("integration")) {
            excludeTags("integration")
        }
    }

    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // AGENTS.md: Enforce 300-second (5-minute) timeout for all tests
    timeout.set(Duration.ofSeconds(300))

    // Set test timeouts to prevent hanging
    systemProperty("junit.jupiter.execution.timeout.default", "30s")
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "30s")
}

tasks.bootJar {
    duplicatesStrategy = DuplicatesStrategy.WARN
}

// ==============================================================================
// OpenAPI Code Generation Tasks (P1-T2)
// ==============================================================================

// Task to generate Kotlin DTOs for Android
tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateKotlinDtos") {
    group = "openapi"
    description = "Generate Kotlin DTOs from OpenAPI spec for Android app"

    inputSpec.set("${rootProject.projectDir}/../docs/architecture/api-specification.yaml")
    outputDir.set("${rootProject.projectDir}/../android/.openapi-generator")
    generatorName.set("kotlin")

    // Generate models only (no HTTP client)
    globalProperties.set(mapOf(
        "models" to "",
        "modelDocs" to "false"
    ))

    configOptions.set(mapOf(
        "packageName" to "com.albunyaan.tube.data.model.api",
        "modelPackage" to "com.albunyaan.tube.data.model.api",
        "dateLibrary" to "java8",
        "serializationLibrary" to "moshi",
        "enumPropertyNaming" to "UPPERCASE",
        "sourceFolder" to "src/main/kotlin"
    ))

    // Clean previous generation
    doFirst {
        delete("${rootProject.projectDir}/../android/.openapi-generator")
        delete("${rootProject.projectDir}/../android/app/src/main/java/com/albunyaan/tube/data/model/api")
    }

    // Move generated files to correct location
    doLast {
        copy {
            from("${rootProject.projectDir}/../android/.openapi-generator/src/main/kotlin/com/albunyaan/tube/data/model/api")
            into("${rootProject.projectDir}/../android/app/src/main/java/com/albunyaan/tube/data/model/api")
        }
        delete("${rootProject.projectDir}/../android/.openapi-generator")
    }
}

// Canonical task to generate all client DTOs (TypeScript + Kotlin)
tasks.register("generateApiDtos") {
    group = "openapi"
    description = "Generate all client DTOs (TypeScript + Kotlin) from OpenAPI spec"

    dependsOn("generateKotlinDtos")

    // Frontend TypeScript generation is handled by npm script
    doLast {
        println("✅ Kotlin DTOs generated to: android/app/src/main/java/com/albunyaan/tube/data/model/api/models")
        println("⚠️  Generate TypeScript DTOs with: cd frontend && npm run generate:api")
    }
}
