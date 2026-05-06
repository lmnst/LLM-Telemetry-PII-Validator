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
    implementation("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
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

tasks.javadoc {
    // Only document the public surface; package-private types are implementation detail.
    options.memberLevel = org.gradle.external.javadoc.JavadocMemberLevel.PUBLIC
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}
