package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.marshalPublicKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.etc.types.toProtobuf
import org.slf4j.LoggerFactory
import pubsub.pb.Rpc

val SignPrefix = "libp2p-pubsub:".toByteArray()

private val logger = LoggerFactory.getLogger("io.libp2p.pubsub.PubsubCrypto")

fun pubsubSign(msg: Rpc.Message, key: PrivKey): Rpc.Message {
    if (msg.hasKey() || msg.hasSignature()) throw IllegalArgumentException("Message to sign should not contain 'key' or 'signature' fields")
    val signature = key.sign(SignPrefix + msg.toByteArray())
    return Rpc.Message.newBuilder(msg)
        .setSignature(signature.toProtobuf())
        .setKey(marshalPublicKey(key.publicKey()).toProtobuf())
        .build()
}

/**
 * Strict-sign validation for a pubsub [Rpc.Message]:
 *
 *  - `from`, `key` and `signature` MUST all be present and non-empty.
 *  - `signature` MUST verify under `key` over the SSZ-style serialised message with
 *    `signature` and `key` cleared.
 *  - `PeerId.fromPubKey(key)` MUST equal `PeerId(from)`, otherwise an attacker holding
 *    any valid private key could publish messages whose `from` claims a different peer.
 *
 * Returns `false` on any validation failure. Never throws.
 */
fun pubsubValidate(msg: Rpc.Message): Boolean {
    if (!msg.hasFrom() || msg.from.isEmpty) {
        logger.debug("Rejecting pubsub message with missing/empty 'from'")
        return false
    }
    if (!msg.hasKey() || msg.key.isEmpty) {
        logger.debug("Rejecting pubsub message with missing/empty 'key'")
        return false
    }
    if (!msg.hasSignature() || msg.signature.isEmpty) {
        logger.debug("Rejecting pubsub message with missing/empty 'signature'")
        return false
    }

    val publicKey = try {
        unmarshalPublicKey(msg.key.toByteArray())
    } catch (e: Exception) {
        logger.debug("Rejecting pubsub message with un-parseable 'key': {}", e.toString())
        return false
    }

    val keyDerivedPeerId = PeerId.fromPubKey(publicKey)
    val claimedFrom = try {
        PeerId(msg.from.toByteArray())
    } catch (e: Exception) {
        logger.debug("Rejecting pubsub message with un-parseable 'from': {}", e.toString())
        return false
    }
    if (keyDerivedPeerId != claimedFrom) {
        logger.debug(
            "Rejecting pubsub message: from={} does not match PeerId(key)={}",
            claimedFrom,
            keyDerivedPeerId
        )
        return false
    }

    val msgToSign = Rpc.Message.newBuilder(msg)
        .clearSignature()
        .clearKey()
        .build()
    return publicKey.verify(
        SignPrefix + msgToSign.toByteArray(),
        msg.signature.toByteArray()
    )
}
