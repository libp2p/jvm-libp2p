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

class ConnectionClosedException(message: String) : Libp2pException(message) {
    constructor(): this("Connection is closed")
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