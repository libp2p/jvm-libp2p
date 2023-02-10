import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

// To publish the release artifact to CloudSmith repo run the following :
// ./gradlew publish -PcloudsmithUser=<user> -PcloudsmithApiKey=<api-key>

group = "io.libp2p"
version = "develop"
description = "a minimal implementation of libp2p for the jvm"

plugins {
    kotlin("jvm").version("1.6.10")

    id("com.github.ben-manes.versions").version("0.44.0")
    id("com.google.protobuf").version("0.9.1")
    id("idea")
    id("io.gitlab.arturbosch.detekt").version("1.20.0-RC1")
    id("java")
    id("maven-publish")
    id("org.jetbrains.dokka").version("1.6.10")
    id("org.jmailen.kotlinter").version("3.8.0")
    id("java-test-fixtures")
}

repositories {
    mavenCentral()
    maven("https://artifacts.consensys.net/public/maven/maven/")
}


val log4j2Version = "2.19.0"

sourceSets.create("jmh") {
    compileClasspath += sourceSets["main"].runtimeClasspath
    compileClasspath += sourceSets["testFixtures"].runtimeClasspath
    runtimeClasspath += sourceSets["main"].runtimeClasspath
    runtimeClasspath += sourceSets["testFixtures"].runtimeClasspath
}

dependencies {
    api("io.netty:netty-all:4.1.69.Final")
    api("com.google.protobuf:protobuf-java:3.21.9")

    implementation("tech.pegasys:noise-java:22.1.0")

    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.1")

    testImplementation("io.mockk:mockk:1.12.2")
    testImplementation("org.assertj:assertj-core:3.23.1")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.35")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.35")
}

task<JavaExec>("jmh") {
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["jmh"].compileClasspath + sourceSets["jmh"].runtimeClasspath
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.21.9"
    }

    tasks.get("clean").doFirst({ delete(generatedFilesBaseDir) })

    idea {
        module {
            sourceDirs.add(file("${generatedFilesBaseDir}/main/java"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
}
tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Parallel build execution
tasks.test {
    description = "Runs the unit tests."

    useJUnitPlatform{
        excludeTags("interop")
    }

    testLogging {
        events("FAILED")
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
    }

    // disabling the parallel test runs for the time being due to port collisions
    // If GRADLE_MAX_TEST_FORKS is not set, use half the available processors
//    maxParallelForks = (System.getenv("GRADLE_MAX_TEST_FORKS")?.toInt() ?:
//    Runtime.getRuntime().availableProcessors().div(2))
}

kotlinter {
    disabledRules = arrayOf("no-wildcard-imports")
}

val sourcesJar by tasks.registering(Jar::class) {
    classifier = "sources"
    from(sourceSets.main.get().allSource)
}

tasks.dokkaHtml.configure {
    outputDirectory.set(buildDir.resolve("dokka"))
    dokkaSourceSets {
        configureEach {
            jdkVersion.set(8)
            reportUndocumented.set(false)
            externalDocumentationLink {
                url.set(URL("https://netty.io/4.1/api/"))
            }
        }
    }
}

val dokkaJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    group = JavaBasePlugin.DOCUMENTATION_GROUP
    val dokkaJavadocTask = tasks.getByName("dokkaJavadoc")
    dependsOn(dokkaJavadocTask)
    archiveClassifier.set("javadoc")
    from(dokkaJavadocTask.outputs)
}

publishing {
    repositories {
        maven {
            name = "cloudsmith"
            url = uri("https://api-g.cloudsmith.io/maven/libp2p/jvm-libp2p")
            credentials {
                username = findProperty("cloudsmithUser")
                password = findProperty("cloudsmithApiKey")
            }
        }
    }
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
            artifact(sourcesJar.get())
            artifact(dokkaJar.get())
            groupId = "io.libp2p"
            artifactId = project.name
        }
    }
}

fun findProperty(s: String) = project.findProperty(s) as String?

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    languageVersion = "1.6"
    allWarningsAsErrors = true
}

detekt {
    config = files("$projectDir/detekt/config.yml")
    buildUponDefaultConfig = true
}