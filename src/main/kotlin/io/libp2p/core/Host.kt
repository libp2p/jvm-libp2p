package io.libp2p.core

import io.libp2p.core.crypto.PrivKey

/**
 * The Host is the libp2p entrypoint. It is tightly coupled with all its inner components right now; in the near future
 * it should use some kind of dependency injection to wire itself.
 */
class Host(
    private val privKey: PrivKey,
    private val newtork: Network,
    private val addressBook: AddressBook
) {

    fun start() {
    }
}
