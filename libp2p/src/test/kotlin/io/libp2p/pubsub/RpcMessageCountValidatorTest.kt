package io.libp2p.pubsub

import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class RpcMessageCountValidatorTest {

    private val unlimited = PubsubRpcLimits.NONE.copy(rejectEmptyPublishEntries = true)

    private fun bytesOf(rpc: Rpc.RPC) = Unpooled.wrappedBuffer(rpc.toByteArray())

    @Test
    fun `rejects RPC containing an empty publish entry`() {
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.getDefaultInstance()) // empty Message
            .build()

        val result = RpcMessageCountValidator.validate(bytesOf(rpc), unlimited)

        assertThat(result).isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }
}
