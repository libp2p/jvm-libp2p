plugins {
    id("java")
    id("com.bmuschko.docker-java-application") version "9.4.0"
}

dependencies {
    implementation(project(":libp2p"))
    implementation("redis.clients:jedis:6.1.0")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")
}

docker {
    javaApplication {
        baseImage.set("openjdk:11-jdk")
        ports.set(listOf(4041))
    }
}

val composeFileSpec: CopySpec = copySpec {
    from("src/test/resources")
    include("compose.yaml")
}

val copyAssets = tasks.register<Copy>("copyAssets") {
    into(layout.buildDirectory.dir("docker"))
    with(composeFileSpec)
}

tasks.dockerCreateDockerfile {
    dependsOn(copyAssets)
}