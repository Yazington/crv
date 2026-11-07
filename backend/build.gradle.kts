import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("net.ltgt.errorprone") version "5.2.0"
    id("net.ltgt.nullaway") version "3.3.0"
    id("com.diffplug.spotless") version "8.11.0"
}

group = "io.github.yazington"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.2")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.0")
    implementation("org.jspecify:jspecify:1.0.1")
    // The assistant: DeepSeek on Fireworks through its OpenAI-compatible API (requirements.md, decision 14).
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")

    errorprone("com.google.errorprone:error_prone_core:2.52.0")
    errorprone("com.uber.nullaway:nullaway:0.14.3")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Null checks follow Spring Framework 7's recommended setup (docs/code-standards.md).
nullaway {
    error()
    onlyNullMarked = true
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        option("NullAway:CustomContractAnnotations", "org.springframework.lang.Contract")
        // JPA fills these fields by reflection after its no-argument constructor runs.
        option(
            "NullAway:ExcludedFieldAnnotations",
            "jakarta.persistence.Id,jakarta.persistence.Column,jakarta.persistence.ManyToOne," +
                "jakarta.persistence.OneToMany",
        )
    }
}

spotless {
    java {
        palantirJavaFormat("2.102.0")
    }
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}
