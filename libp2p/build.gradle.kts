plugins {
    id("com.google.protobuf").version("0.9.4")
    id("me.champeau.jmh").version("0.7.1")
}

// https://docs.gradle.org/current/userguide/java_testing.html#ex-disable-publishing-of-test-fixtures-variants
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

dependencies {
    api("io.netty:netty-common")
    api("io.netty:netty-buffer")
    api("io.netty:netty-transport")
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-codec-http")

    api("com.google.protobuf:protobuf-java")

    implementation("com.github.multiformats:java-multibase")
    implementation("tech.pegasys:noise-java")

    implementation("org.bouncycastle:bcprov-jdk18on")
    implementation("org.bouncycastle:bcpkix-jdk18on")
    implementation("org.bouncycastle:bctls-jdk18on")

    testImplementation(project(":tools:schedulers"))

    testFixturesApi("org.apache.logging.log4j:log4j-core")
    testFixturesImplementation(project(":tools:schedulers"))
    testFixturesImplementation("io.netty:netty-transport-classes-epoll")
    testFixturesImplementation("io.netty:netty-handler")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")

    jmhImplementation(project(":tools:schedulers"))
    jmhImplementation("org.openjdk.jmh:jmh-core")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc"
    }

    tasks["clean"].doFirst { delete(generatedFilesBaseDir) }

    idea {
        module {
            sourceDirs.add(file("$generatedFilesBaseDir/main/java"))
        }
    }
}
