import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URL

// To publish the release artifact to CloudSmith repo run the following :
// ./gradlew publish -PcloudsmithUser=<user> -PcloudsmithApiKey=<api-key>

description = "a minimal implementation of libp2p for the jvm"

plugins {
    val kotlinVersion = "1.6.21"

    id("org.jetbrains.kotlin.jvm") version kotlinVersion apply false

    id("com.github.ben-manes.versions").version("0.44.0")
    id("idea")
    id("io.gitlab.arturbosch.detekt").version("1.22.0")
    id("java")
    id("maven-publish")
    id("org.jetbrains.dokka").version("1.7.20")
    id("org.jmailen.kotlinter").version("3.10.0")
    id("java-test-fixtures")
    id("io.spring.dependency-management").version("1.1.0")

    id("org.jetbrains.kotlin.android") version kotlinVersion apply false
    id("com.android.application") version "7.4.2" apply false
}

fun Project.getBooleanPropertyOrFalse(propName: String) =
    (this.properties[propName] as? String)?.toBoolean() ?: false

configure(
    allprojects
        .filterNot {
            it.getBooleanPropertyOrFalse("libp2p.gradle.custom")
        }
) {
    group = "io.libp2p"
    version = "develop"

    apply(plugin = "kotlin")
    apply(plugin = "idea")
    apply(plugin = "java")

    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "org.jmailen.kotlinter")
    apply(plugin = "java-test-fixtures")
    apply(plugin = "io.spring.dependency-management")
    apply(from = "$rootDir/versions.gradle")

    dependencies {

        implementation(kotlin("stdlib-jdk8"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

        implementation("com.google.guava:guava")
        implementation("org.slf4j:slf4j-api")
        implementation("com.github.multiformats:java-multibase:v1.1.1")

        testFixturesImplementation("com.google.guava:guava")
        testFixturesImplementation("org.slf4j:slf4j-api")

        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.junit.jupiter:junit-jupiter-params")
        testImplementation("io.mockk:mockk")
        testImplementation("org.assertj:assertj-core")
        testRuntimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
        kotlinOptions {
            languageVersion = "1.6"
            allWarningsAsErrors = true
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
                    username = findProperty("cloudsmithUser") as String?
                    password = findProperty("cloudsmithApiKey") as String?
                }
            }
        }
        if (hasProperty("mavenArtifactId")) {
            publications {
                register("mavenJava", MavenPublication::class) {
                    from(components["java"])
                    artifact(sourcesJar.get())
                    artifact(dokkaJar.get())
                    groupId = "io.libp2p"
                    artifactId = project.property("mavenArtifactId") as String
                }
            }
        }
    }

    detekt {
        config = files("$rootDir/detekt/config.yml")
        buildUponDefaultConfig = true
    }
}
