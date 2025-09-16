import org.jetbrains.kotlin.cli.jvm.compiler.findMainClass

plugins {
    id("application")
    id("kotlin")
}

application {
    mainClass = "io.libp2p.interop.InteropTestAgentKt"
}

dependencies {
    implementation(project(":libp2p"))
    implementation("redis.clients:jedis:6.1.0")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j2-impl")
}
