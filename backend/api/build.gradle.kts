buildscript {
    // Boot 3.5.16 is the final 3.5 OSS release. Patch its buildpack tool's
    // transitive dependencies as well as the application's managed runtime.
    configurations.classpath {
        resolutionStrategy.force(
            "com.fasterxml.jackson:jackson-bom:2.21.6",
            "com.fasterxml.jackson.core:jackson-core:2.21.6",
            "com.fasterxml.jackson.core:jackson-databind:2.21.6",
            "com.fasterxml.jackson.module:jackson-module-parameter-names:2.21.6",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.apache.httpcomponents.client5:httpclient5:5.6.3",
            "org.apache.httpcomponents.core5:httpcore5:5.4.3",
            "org.apache.httpcomponents.core5:httpcore5-h2:5.4.3",
        )
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

// Security fixes released after the final Boot 3.5 BOM, without a Boot major upgrade.
extra["jackson-bom.version"] = "2.21.6"
extra["log4j2.version"] = "2.25.5"
extra["tomcat.version"] = "10.1.59"
extra["postgresql.version"] = "42.7.12"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(project(":engine"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
