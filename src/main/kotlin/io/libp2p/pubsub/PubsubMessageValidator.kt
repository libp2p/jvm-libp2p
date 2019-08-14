package io.libp2p.pubsub

import pubsub.pb.Rpc

interface PubsubMessageValidator {

    fun validate(msg: Rpc.RPC) {
        msg.publishList.forEach { validate(it) }
    }

    fun validate(msg: Rpc.Message)

    companion object {
        fun nopValidator() = object : PubsubMessageValidator {
            override fun validate(msg: Rpc.Message) {
                // NOP
            }
        }

        fun signatureValidator() = object : PubsubMessageValidator {
            override fun validate(msg: Rpc.Message) {
                if (!pubsubValidate(msg)) {
                    throw InvalidMessageException(msg.toString())
                }
            }
        }
    }
}