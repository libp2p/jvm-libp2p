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
        val cipherText = processBytes(localCipher, msg.toByteArray())
        val macArr = updateMac(local, cipherText)

        out.add(
            Unpooled.wrappedBuffer(
                Unpooled.wrappedBuffer(cipherText),
                Unpooled.wrappedBuffer(macArr)
            )
        )
    } // encode

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val (cipherBytes, macBytes) = textAndMac(msg)

        val macArr = updateMac(remote, cipherBytes)

        if (!macBytes.contentEquals(macArr))
            throw MacMismatch()

        val clearText = processBytes(remoteCipher, cipherBytes)
        out.add(clearText.toByteBuf())
    } // decode

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error(cause.message)
        if (cause is SecioError) {
            ctx.channel().close()
        }
    } // exceptionCaught

    private fun textAndMac(msg: ByteBuf): Pair<ByteArray, ByteArray> {
        val macBytes = msg.toByteArray(from = msg.readableBytes() - remote.mac.macSize)
        val cipherBytes = msg.toByteArray(to = msg.readableBytes() - remote.mac.macSize)

        return Pair(cipherBytes, macBytes)
    } // textAndMac

    private fun processBytes(cipher: StreamCipher, bytesIn: ByteArray): ByteArray {
        val bytesOut = ByteArray(bytesIn.size)
        cipher.processBytes(bytesIn, 0, bytesIn.size, bytesOut, 0)
        return bytesOut
    } // processBytes

    private fun updateMac(secioParams: SecioParams, bytes: ByteArray): ByteArray {
        with(secioParams.mac) {
            reset()
            update(bytes, 0, bytes.size)

            val macArr = ByteArray(macSize)
            doFinal(macArr, 0)

            return macArr
        } // with
    } // updateMac
}