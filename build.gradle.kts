import com.google.protobuf.gradle.proto
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "io.libp2p"
version = "0.0.1-SNAPSHOT"
description = "a minimal implementation of libp2p for the jvm"

plugins {
    java
    idea
    kotlin("jvm") version "1.3.31"
    id("org.jmailen.kotlinter") version "1.26.0"
    id("com.google.protobuf") version "0.8.7"
    `build-scan`

    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

val log4j2Version = "2.11.2"

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.0-M1")
    compile("io.netty:netty-all:4.1.36.Final")
    compile("com.google.guava:guava:27.1-jre")
    compile("org.bouncycastle:bcprov-jdk15on:1.61")
    compile("org.bouncycastle:bcpkix-jdk15on:1.61")
    compile("com.github.multiformats:java-multiaddr:v1.3.1")
    compile("com.google.protobuf:protobuf-java:3.6.1")
    compile("com.google.protobuf:protobuf-java:3.6.1")

    compile("org.apache.logging.log4j:log4j-api:${log4j2Version}")
    compile("org.apache.logging.log4j:log4j-core:${log4j2Version}")

    compile("javax.xml.bind:jaxb-api:2.3.1")

    testCompile("org.junit.jupiter:junit-jupiter-api:5.4.2")
    testCompile("org.junit.jupiter:junit-jupiter-params:5.4.2")
    testRuntime("org.junit.jupiter:junit-jupiter-engine:5.4.2")

}

sourceSets {
    main {
        proto {
            srcDir("src/main/proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.0.0"
    }

    tasks.get("clean").doFirst({ delete(generatedFilesBaseDir) })

    idea {
        module {
            sourceDirs.add(file("${generatedFilesBaseDir}/main/java"))
        }
    }
}




tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions {
        freeCompilerArgs = listOf("-XXLanguage:+InlineClasses", "-Xjvm-default=enable")
    }
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

buildScan {
    termsOfServiceUrl = "https://gradle.com/terms-of-service"
    termsOfServiceAgree = "yes"
}

publishing {
    repositories {
        maven {
            // change to point to your repo, e.g. http://my.org/repo
            url = uri("$buildDir/repo")
        }
    }
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
        }
    }
}