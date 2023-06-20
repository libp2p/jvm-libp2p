
plugins {
    id("com.google.protobuf").version("0.9.2")
    id("me.champeau.jmh").version("0.6.8")
}

dependencies {
    api("io.netty:netty-common")
    api("io.netty:netty-buffer")
    api("io.netty:netty-transport")
    implementation("io.netty:netty-handler")
    implementation("io.netty:netty-codec-http")
    implementation("io.netty:netty-transport-classes-epoll:4.1.90.Final")
    implementation("io.netty.incubator:netty-incubator-codec-native-quic")

    api("com.google.protobuf:protobuf-java")

    implementation("tech.pegasys:noise-java")

    implementation("org.bouncycastle:bcprov-jdk15on")
    implementation("org.bouncycastle:bcpkix-jdk15on")
    implementation("org.bouncycastle:bctls-jdk15on")

    testImplementation(project(":tools:schedulers"))

    testImplementation("io.netty.incubator:netty-incubator-codec-native-quic::linux-x86_64")
    testImplementation("io.netty.incubator:netty-incubator-codec-native-quic::linux-aarch_64")
    testImplementation("io.netty.incubator:netty-incubator-codec-native-quic::osx-x86_64")
    testImplementation("io.netty.incubator:netty-incubator-codec-native-quic::osx-aarch_64")
    testImplementation("io.netty.incubator:netty-incubator-codec-native-quic::windows-x86_64")

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

