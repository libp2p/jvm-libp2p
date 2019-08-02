package io.libp2p.pubsub

import pubsub.pb.Rpc

interface PubsubMessageValidator {

    fun validate(msg: Rpc.RPC) {
        msg.publishList.forEach { validate(it) }
    }

    fun validate(msg: Rpc.Message) {}
}