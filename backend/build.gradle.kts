import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("java")
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    id("io.gatling.gradle") version "3.10.3"
}

group = "com.albunyaan"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

defaultTasks("bootRun")

repositories {
    mavenCentral()
    // Required for Firebase and Google Cloud dependencies
    google()
    // Required for NewPipeExtractor
    maven { url = uri("https://jitpack.io") }
}

dependencies {
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
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.8")

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
    useJUnitPlatform()
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.bootJar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

