
plugins {
    id("com.google.protobuf").version("0.9.2")
}

dependencies {
    api("io.netty:netty-all")
    api("com.google.protobuf:protobuf-java")

    implementation("commons-codec:commons-codec")
    implementation("tech.pegasys:noise-java")

    implementation("org.bouncycastle:bcprov-jdk15on")
    implementation("org.bouncycastle:bcpkix-jdk15on")
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

