package io.libp2p.security.secio

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import org.apache.logging.log4j.LogManager
import org.bouncycastle.crypto.StreamCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV

class SecIoCodec(val local: SecioParams, val remote: SecioParams) : MessageToMessageCodec<ByteBuf, ByteBuf>() {
    private val log = LogManager.getLogger(SecIoCodec::class.java)

    private val localCipher = createCipher(local)
    private val remoteCipher = createCipher(remote)

    companion object {
        fun createCipher(params: SecioParams): StreamCipher {
            val aesEngine = AESEngine().apply {
                init(true, KeyParameter(params.keys.cipherKey))
            }
            return SICBlockCipher(aesEngine).apply {
                init(true, ParametersWithIV(null, params.keys.iv))
            }
        }
    }

    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val msgByteArr = msg.toByteArray()
        val outByteArr = ByteArray(msgByteArr.size)
        localCipher.processBytes(msgByteArr, 0, msgByteArr.size, outByteArr, 0)

        local.mac.reset()
        local.mac.update(outByteArr, 0, outByteArr.size)
        val macArr = ByteArray(local.mac.macSize)
        local.mac.doFinal(macArr, 0)
        out.add(
            Unpooled.wrappedBuffer(
                Unpooled.wrappedBuffer(outByteArr),
                Unpooled.wrappedBuffer(macArr)
            )
        )
    }

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val macBytes = msg.toByteArray(from = msg.readableBytes() - remote.mac.macSize)
        val cipherBytes = msg.toByteArray(to = msg.readableBytes() - remote.mac.macSize)
        remote.mac.reset()
        remote.mac.update(cipherBytes, 0, cipherBytes.size)
        val macArr = ByteArray(remote.mac.macSize)
        remote.mac.doFinal(macArr, 0)
        if (!macBytes.contentEquals(macArr)) throw MacMismatch()
        val plainBytes = ByteArray(cipherBytes.size)
        remoteCipher.processBytes(cipherBytes, 0, cipherBytes.size, plainBytes, 0)
        out.add(plainBytes.toByteBuf())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error(cause.message)
        if (cause is SecioError) {
            ctx.channel().close()
        }
    }
}