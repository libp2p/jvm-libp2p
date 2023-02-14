import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

// To publish the release artifact to CloudSmith repo run the following :
// ./gradlew publish -PcloudsmithUser=<user> -PcloudsmithApiKey=<api-key>

group = "io.libp2p"
version = "develop"
description = "a minimal implementation of libp2p for the jvm"

plugins {
    kotlin("jvm").version("1.6.21")

    id("com.github.ben-manes.versions").version("0.44.0")
    id("com.google.protobuf").version("0.9.2")
    id("idea")
    id("io.gitlab.arturbosch.detekt").version("1.22.0")
    id("java")
    id("maven-publish")
    id("org.jetbrains.dokka").version("1.7.20")
    id("org.jmailen.kotlinter").version("3.10.0")
    id("java-test-fixtures")
    id("me.champeau.jmh").version("0.6.8")
}

repositories {
    mavenCentral()
    maven("https://artifacts.consensys.net/public/maven/maven/")
}

val guavaVersion = "31.1-jre"
val bouncyCastleVersion = "1.70"
val log4j2Version = "2.19.0"
val junitVersion = "5.9.2"
val mockitoVersion = "5.1.1"
val jmhVersion = "1.36"

dependencies {
    api("io.netty:netty-all:4.1.87.Final")
    api("com.google.protobuf:protobuf-java:3.21.12")

    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("tech.pegasys:noise-java:22.1.0")

    implementation("com.google.guava:guava:$guavaVersion")
    implementation("org.bouncycastle:bcprov-jdk15on:$bouncyCastleVersion")
    implementation("org.bouncycastle:bcpkix-jdk15on:$bouncyCastleVersion")
    implementation("commons-codec:commons-codec:1.15")

    implementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    implementation("org.apache.logging.log4j:log4j-core:$log4j2Version")
    implementation("javax.xml.bind:jaxb-api:2.3.1")

    testFixturesImplementation("org.apache.logging.log4j:log4j-api:$log4j2Version")
    testFixturesImplementation("com.google.guava:guava:$guavaVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testImplementation("io.mockk:mockk:1.13.3")
    testRuntimeOnly("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testImplementation("org.assertj:assertj-core:3.24.2")

    jmhImplementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.21.12"
    }

    tasks["clean"].doFirst { delete(generatedFilesBaseDir) }

    idea {
        module {
            sourceDirs.add(file("$generatedFilesBaseDir/main/java"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
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

    useJUnitPlatform {
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
    disabledRules = arrayOf("no-wildcard-imports", "enum-entry-name-case")
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

tasks.dokkaHtml.configure {
    outputDirectory.set(buildDir.resolve("dokka"))
    dokkaSourceSets {
        configureEach {
            jdkVersion.set(11)
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
