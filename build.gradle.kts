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
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

// Parallel build execution
tasks.withType<Test> {
    // If GRADLE_MAX_TEST_FORKS is not set, use half the available processors
    maxParallelForks = (System.getenv("GRADLE_MAX_TEST_FORKS")?.toInt() ?:
    Runtime.getRuntime().availableProcessors().div(2))
}

kotlinter {
    allowWildcardImports = false
}