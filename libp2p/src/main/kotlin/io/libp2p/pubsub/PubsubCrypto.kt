package io.libp2p.pubsub

import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.marshalPublicKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.etc.types.toProtobuf
import pubsub.pb.Rpc

val SignPrefix = "libp2p-pubsub:".toByteArray()

fun pubsubSign(msg: Rpc.Message, key: PrivKey): Rpc.Message {
    if (msg.hasKey() || msg.hasSignature()) throw IllegalArgumentException("Message to sign should not contain 'key' or 'signature' fields")
    val signature = key.sign(SignPrefix + msg.toByteArray())
    return Rpc.Message.newBuilder(msg)
        .setSignature(signature.toProtobuf())
        .setKey(marshalPublicKey(key.publicKey()).toProtobuf())
        .build()
}

fun pubsubValidate(msg: Rpc.Message): Boolean {
    val msgToSign = Rpc.Message.newBuilder(msg)
        .clearSignature()
        .clearKey()
        .build()
    return unmarshalPublicKey(msg.key.toByteArray()).verify(
        SignPrefix + msgToSign.toByteArray(),
        msg.signature.toByteArray()
    )
}
