
plugins {
    id("com.google.protobuf").version("0.9.2")
}

val bouncyCastleVersion = "1.70"

dependencies {
    api("io.netty:netty-all:4.1.87.Final")
    api("com.google.protobuf:protobuf-java:3.21.12")

    implementation("commons-codec:commons-codec:1.15")
    implementation("tech.pegasys:noise-java:22.1.0")

    implementation("org.bouncycastle:bcprov-jdk15on:$bouncyCastleVersion")
    implementation("org.bouncycastle:bcpkix-jdk15on:$bouncyCastleVersion")
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

