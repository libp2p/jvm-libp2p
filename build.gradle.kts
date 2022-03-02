import com.google.protobuf.gradle.proto
import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths

// To publish the release artifact to CloudSmith repo run the following :
// ./gradlew publish -PcloudsmithUser=<user> -PcloudsmithApiKey=<api-key>

group = "io.libp2p"
version = "0.7.0-RELEASE"
description = "a minimal implementation of libp2p for the jvm"

plugins {
    kotlin("jvm").version("1.6.10")

    id("com.github.ben-manes.versions").version("0.41.0")
    id("com.google.protobuf").version("0.8.18")
    id("idea")
    id("io.gitlab.arturbosch.detekt").version("1.20.0-RC1")
    id("java")
    id("maven-publish")
    id("org.jetbrains.dokka").version("1.6.10")
    id("org.jmailen.kotlinter").version("3.8.0")
}

repositories {
    mavenCentral()
    maven("https://artifacts.consensys.net/public/maven/maven/")
}


val log4j2Version = "2.17.1"

dependencies {
    api("io.netty:netty-all:4.1.69.Final")
    api("com.google.protobuf:protobuf-java:3.19.2")

    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
    implementation("tech.pegasys:noise-java:22.1.0")

    implementation("com.google.guava:guava:31.0.1-jre")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    implementation("commons-codec:commons-codec:1.15")

    implementation("org.apache.logging.log4j:log4j-api:${log4j2Version}")
    implementation("org.apache.logging.log4j:log4j-core:${log4j2Version}")
    implementation("javax.xml.bind:jaxb-api:2.3.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    testImplementation("io.mockk:mockk:1.12.2")
    testRuntimeOnly("org.mockito:mockito-core:4.2.0")
    testImplementation("org.mockito:mockito-junit-jupiter:4.2.0")
    testImplementation("org.assertj:assertj-core:3.22.0")

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
        artifact = "com.google.protobuf:protoc:3.19.2"
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
        freeCompilerArgs = listOf("-Xjvm-default=enable")
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

// Interop Tests
fun findOnPath(executable: String): Boolean {
    return System.getenv("PATH").split(File.pathSeparator)
        .map { Paths.get(it) }
        .any { Files.exists(it.resolve(executable)) }
}
val goOnPath = findOnPath("go")
val nodeOnPath = findOnPath("node")
val rustOnPath = findOnPath("cargo")

val externalsDir = File(sourceSets.test.get().resources.sourceDirectories.singleFile, "../external")
val goPingServer = File(externalsDir, "go/ping-server")
val goPingClient = File(externalsDir, "go/ping-client")
val jsPinger = File(externalsDir, "js/pinger")
val rustPingServer = File(externalsDir, "rust/ping-server")
val rustPingClient = File(externalsDir, "rust/ping-client")

val goTargets = listOf(goPingServer, goPingClient).map { target ->
    val name = "go-build-${target.name}"
    task(name, Exec::class) {
        workingDir = target
        commandLine = "go build".split(" ")
    }
    name
}

val rustTargets = listOf(rustPingServer, rustPingClient).map { target ->
    val name = "rust-build-${target.name}"
    task(name, Exec::class) {
        workingDir = target
        commandLine = "cargo build".split(" ")
    }
    name
}

task("npm-install-pinger", Exec::class) {
    workingDir = jsPinger
    commandLine = "npm install".split(" ")
}

task("interopTest", Test::class) {
    group = "Verification"
    description = "Runs the interoperation tests."

    val dependencies = ArrayList<String>()
    if (goOnPath) dependencies.addAll(goTargets)
    if (nodeOnPath) dependencies.add("npm-install-pinger")
    if (rustOnPath) dependencies.addAll(rustTargets)
    dependsOn(dependencies)

    useJUnitPlatform {
        includeTags("interop")
    }

    testLogging {
        events("PASSED", "FAILED", "SKIPPED")
    }

    environment("ENABLE_JS_INTEROP", nodeOnPath)
    environment("JS_PINGER", jsPinger.toString())
    environment("ENABLE_GO_INTEROP", goOnPath)
    environment("GO_PING_SERVER", goPingServer.toString())
    environment("GO_PING_CLIENT", goPingClient.toString())
    environment("ENABLE_RUST_INTEROP", rustOnPath)
    environment("RUST_PING_SERVER", rustPingServer.toString())
    environment("RUST_PING_CLIENT", rustPingClient.toString())
}
// End Interop Tests

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
    languageVersion = "1.4"
}

detekt {
    baseline = file("$projectDir/config/baseline.xml")
}