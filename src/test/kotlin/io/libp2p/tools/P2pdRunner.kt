package io.libp2p.tools

import io.libp2p.tools.p2pd.DaemonLauncher
import java.io.File

/**
 * Tries to find and starts Libp2p Daemon
 */
class P2pdRunner(val execNames: List<String> = listOf("p2pd", "p2pd.exe"), val execSearchPaths: List<String> = listOf()) {
    val predefinedSearchPaths = listOf(
        File(System.getProperty("user.home"), "go/bin").absoluteFile.canonicalPath
    )

    fun findP2pdExe(): String? =
        (predefinedSearchPaths + execSearchPaths)
            .flatMap { path -> execNames.map { File(path, it) } }
            .firstOrNull() { it.canExecute() }
            ?.absoluteFile?.canonicalPath

    fun launcher() = findP2pdExe()?.let { DaemonLauncher(it) }
}
