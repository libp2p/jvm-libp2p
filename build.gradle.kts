import com.google.protobuf.gradle.proto
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import com.jfrog.bintray.gradle.BintrayExtension
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

// To publish the release artifact to JFrog Bintray repo run the following :
// ./gradlew bintrayUpload -PbintrayUser=<user> -PbintrayApiKey=<api-key>

group = "io.libp2p"
version = "0.2.0-SNAPSHOT"
description = "a minimal implementation of libp2p for the jvm"

plugins {
    java
    idea
    kotlin("jvm") version "1.3.31"
    id("org.jmailen.kotlinter") version "1.26.0"
    id("com.google.protobuf") version "0.8.7"
    `build-scan`

    `maven-publish`
    id("com.jfrog.bintray") version "1.8.1"
    id("org.jetbrains.dokka") version "0.9.18"
}

repositories {
    jcenter()
    mavenCentral()
}

val log4j2Version = "2.11.2"

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.0-M1")
    compile("io.netty:netty-all:4.1.36.Final")
    compile("com.google.guava:guava:27.1-jre")
    compile("org.bouncycastle:bcprov-jdk15on:1.61")
    compile("org.bouncycastle:bcpkix-jdk15on:1.61")
    compile("com.google.protobuf:protobuf-java:3.6.1")
    compile("commons-codec:commons-codec:1.13")

    compile("org.apache.logging.log4j:log4j-api:${log4j2Version}")
    compile("org.apache.logging.log4j:log4j-core:${log4j2Version}")

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

val sourcesJar by tasks.registering(Jar::class) {
    classifier = "sources"
    from(sourceSets.main.get().allSource)
}

val dokka by tasks.getting(DokkaTask::class) {
    outputFormat = "html"
    outputDirectory = "$buildDir/dokka"
    jdkVersion = 8
    reportUndocumented = false
    externalDocumentationLink {
        url = URL("https://netty.io/4.1/api/")
    }
}

val dokkaJar by tasks.creating(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    description = "Assembles Kotlin docs with Dokka"
    archiveClassifier.set("javadoc")
    from(tasks.dokka)
    dependsOn(tasks.dokka)
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
            artifact(sourcesJar.get())
            artifact(dokkaJar)
        }
    }
}

fun findProperty(s: String) = project.findProperty(s) as String?

bintray {
    user = findProperty("bintrayUser")
    key = findProperty("bintrayApiKey")
    publish = true
    setPublications("mavenJava")
    setConfigurations("archives")
    pkg(delegateClosureOf<BintrayExtension.PackageConfig> {
        userOrg = "libp2p"
        repo = "jvm-libp2p"
        name = "io.libp2p"
        setLicenses("Apache-2.0", "MIT")
        vcsUrl = "https://github.com/libp2p/jvm-libp2p"
    })
}
