package io.libp2p.core.types

fun Boolean.whenTrue(run: () -> Unit): Boolean {
    run()
    return this
}