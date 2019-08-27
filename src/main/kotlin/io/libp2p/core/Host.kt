package io.libp2p.core

import io.libp2p.core.crypto.PrivKey
import java.util.concurrent.CompletableFuture

/**
 * The Host is the libp2p entrypoint. It is tightly coupled with all its inner components right now; in the near future
 * it should use some kind of dependency injection to wire itself.
 */
class Host(
    val privKey: PrivKey,
    val network: Network,
    val addressBook: AddressBook
) {

    val peerId = PeerId.fromPubKey(privKey.publicKey())

    fun start(): CompletableFuture<Unit> {
        return network.start()
    }

    fun stop(): CompletableFuture<Unit> {
        return network.close()
    }
}
