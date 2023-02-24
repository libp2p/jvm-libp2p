package io.libp2p.simulate.util.gradle

fun main(args: Array<String>) {
    System.err.println("This is the default gradle main class executing with args: ${args.contentToString()}")
    System.err.println("ERROR: No main class was specified.")
    System.err.println("Use the command syntax below:")
    System.err.println("gradle :tools:simulator:run -PmainClass=<your.main.Class> [--args=\"you args\"]")
    System.exit(-1)
}
