/*
 * Copyright 2019 BLK Technologies Limited (web3labs.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.libp2p.core

open class Libp2pException : RuntimeException {

    constructor(message: String, ex: Exception?) : super(message, ex) {}
    constructor(message: String) : super(message) {}
    constructor(ex: Exception) : super(ex) {}
    constructor() : super("") {}
}

/**
 * Is thrown when any operation is attempted on a closed stream
 */
class ConnectionClosedException(message: String) : Libp2pException(message) {
    constructor() : this("Connection is closed")
}

/**
 * Indicates library malfunction
 */
class InternalErrorException(message: String) : Libp2pException(message)

/**
 * Indicates peer misbehavior, like malformed messages or protocol violation
 */
class BadPeerException(message: String, ex: Exception?) : Libp2pException(message, ex) {
    constructor(message: String) : this(message, null)
}

class BadKeyTypeException : Exception("Invalid or unsupported key type")

/**
 * Indicates that the protocol is not registered at local or remote side
 */
open class NoSuchProtocolException(message: String) : Libp2pException(message)

/**
 * Indicates that the protocol is not registered at local configuration
 */
class NoSuchLocalProtocolException(message: String) : NoSuchProtocolException(message)
/**
 * Indicates that the protocol is not known by the remote party
 */
class NoSuchRemoteProtocolException(message: String) : NoSuchProtocolException(message)

/**
 * Indicates that connecting a [io.libp2p.core.multiformats.Multiaddr] is not possible since
 * the desired transport is not supported
 */
open class TransportNotSupportedException(message: String) : Libp2pException(message)

/**
 * Indicates the message received from a remote party violates protocol
 */
open class ProtocolViolationException(message: String) : Libp2pException(message)

/**
 * When trying to write a message to a peer within [io.libp2p.etc.util.P2PServiceSemiDuplex]
 * but there is no yet outbound stream created.
 */
open class SemiDuplexNoOutboundStreamException(message: String) : Libp2pException(message)
