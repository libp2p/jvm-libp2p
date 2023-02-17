
dependencies {
    api("io.netty:netty-all:4.1.87.Final")
    api("com.google.protobuf:protobuf-java:3.21.12")
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

