package io.libp2p.pubsub

import pubsub.pb.Rpc

/**
 * Validates pubsub messages
 */
interface PubsubMessageValidator {

    /**
     * Validates the whole message with control items
     * @throws InvalidMessageException when the message is not valid
     */
    fun validate(msg: Rpc.RPC) {
        msg.publishList.forEach { validate(it) }
    }

    /**
     * Validates a single publish. Basically this is just a signature validation
     * @throws InvalidMessageException when the message is not valid
     */
    fun validate(msg: Rpc.Message)

    companion object {
        /**
         * Creates a validator which does nothing (all messages assumed as valid)
         */
        fun nopValidator() = object : PubsubMessageValidator {
            override fun validate(msg: Rpc.Message) {
                // NOP
            }
        }

        /**
         * Creates a validator which validates only message signatures
         */
        fun signatureValidator() = object : PubsubMessageValidator {
            override fun validate(msg: Rpc.Message) {
                if (!pubsubValidate(msg)) {
                    throw InvalidMessageException(msg.toString())
                }
            }
        }
    }
}
