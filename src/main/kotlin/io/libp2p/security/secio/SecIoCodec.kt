package io.libp2p.security.secio

import io.libp2p.etc.types.hasCauseOfType
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.security.CantDecryptInboundException
import io.libp2p.security.InvalidMacException
import io.libp2p.security.SecureChannelError
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
import java.io.IOException

class SecIoCodec(val local: SecioParams, val remote: SecioParams) : MessageToMessageCodec<ByteBuf, ByteBuf>() {
    private val log = LogManager.getLogger(SecIoCodec::class.java)

    private val localCipher = createCipher(local)
    private val remoteCipher = createCipher(remote)
    private var abruptlyClosing = false

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
        if (abruptlyClosing) {
            // if abrupt close was initiated by our node we shouldn't try decoding anything else
            return
        }
        try {
            val (cipherBytes, macBytes) = textAndMac(msg)

            val macArr = updateMac(remote, cipherBytes)

            if (!macBytes.contentEquals(macArr))
                throw InvalidMacException()

            val clearText = processBytes(remoteCipher, cipherBytes)
            out.add(clearText.toByteBuf())
        } catch (e: Exception) {
            throw CantDecryptInboundException("Error decrypting inbound message", e)
        }
    } // decode

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause.hasCauseOfType(IOException::class)) {
            // Trace level because having clients unexpectedly disconnect is extremely common
            log.trace("IOException in SecIO channel", cause)
        } else if (cause.hasCauseOfType(SecureChannelError::class)) {
            log.debug("Invalid SecIO content", cause)
            closeAbruptly(ctx)
        } else {
            log.error("Unexpected error in SecIO channel", cause)
        }
    } // exceptionCaught

    private fun closeAbruptly(ctx: ChannelHandlerContext) {
        abruptlyClosing = true
        ctx.close()
    }

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
