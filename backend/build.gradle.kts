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
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Firebase Admin SDK for Authentication and Firestore (includes Firestore dependency)
    implementation("com.google.firebase:firebase-admin:9.2.0")

    // YouTube Data API client
    implementation("com.google.apis:google-api-services-youtube:v3-rev20240916-2.0.0")
    implementation("com.google.api-client:google-api-client:2.2.0")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1")
    implementation("com.google.http-client:google-http-client-jackson2:1.43.3")

    runtimeOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("it.ozimov:embedded-redis:0.7.3") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
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
