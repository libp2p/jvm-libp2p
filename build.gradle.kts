import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "io.libp2p"
version = "0.0.1-SNAPSHOT"
description = "a minimal implementation of libp2p for the jvm"

plugins {
    kotlin("jvm") version "1.3.31"
    id("org.jmailen.kotlinter") version "1.26.0"

    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    compile("io.netty:netty-all:4.1.36.Final")
    compile("com.google.guava:guava:27.1-jre")
    compile("org.bouncycastle:bcprov-jdk15on:1.61")
    compile("org.bouncycastle:bcpkix-jdk15on:1.61")

    testCompile("org.junit.jupiter:junit-jupiter-api:5.4.2")
    testCompile("org.junit.jupiter:junit-jupiter-params:5.4.2")
    testRuntime("org.junit.jupiter:junit-jupiter-engine:5.4.2")

}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

// Parallel build execution
tasks.test {
    useJUnitPlatform()

    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
    }

    // If GRADLE_MAX_TEST_FORKS is not set, use half the available processors
    maxParallelForks = (System.getenv("GRADLE_MAX_TEST_FORKS")?.toInt() ?:
    Runtime.getRuntime().availableProcessors().div(2))
}

kotlinter {
    allowWildcardImports = false
}