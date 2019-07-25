package io.libp2p.pubsub

import pubsub.pb.Rpc

interface PubsubMessageValidator {

    fun validate(msg: Rpc.Message) {}
}