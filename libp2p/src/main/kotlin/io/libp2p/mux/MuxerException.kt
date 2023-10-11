package io.libp2p.mux

import io.libp2p.core.Libp2pException
import io.libp2p.etc.util.netty.mux.MuxId

open class MuxerException(message: String, ex: Exception?) : Libp2pException(message, ex)

open class ReadMuxerException(message: String, ex: Exception?) : MuxerException(message, ex)
open class WriteMuxerException(message: String, ex: Exception?) : MuxerException(message, ex)

class UnknownStreamIdMuxerException(muxId: MuxId) : ReadMuxerException("Stream with id $muxId not found", null)

class InvalidFrameMuxerException(message: String) : ReadMuxerException(message, null)

class WriteBufferOverflowMuxerException(message: String) : WriteMuxerException(message, null)
class ClosedForWritingMuxerException(muxId: MuxId) : WriteMuxerException("Couldn't write, stream was closed for writing: $muxId", null)
