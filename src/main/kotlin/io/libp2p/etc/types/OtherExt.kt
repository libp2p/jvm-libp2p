package io.libp2p.etc.types

fun Boolean.whenTrue(run: () -> Unit): Boolean {
    run()
    return this
}