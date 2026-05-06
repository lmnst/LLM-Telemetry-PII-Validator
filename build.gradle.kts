plugins {
    java
    application
}

group = "com.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("com.example.downloader.cli.Main")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        // Default: exclude integration + chaos (fast unit + property tests only).
        // `-PintegrationTests` opts in to integration tests; `-PchaosTests` opts in to chaos.
        if (!project.hasProperty("integrationTests")) excludeTags("integration")
        if (!project.hasProperty("chaosTests")) excludeTags("chaos")
    }
    jvmArgs("-ea")
}
