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
package io.libp2p.core.mplex

/**
 * Captures the permissible states of a [MultiplexStream]
 * @see [mplex documentation](https://github.com/libp2p/specs/tree/master/mplex)
 */
enum class MultiplexStreamState {

    /**
     * The default state of an established stream.
     */
    READY {
        override fun canSend(): Boolean = true
        override fun canReceive(): Boolean = true
    },
    RESET_LOCAL {
        override fun canSend(): Boolean = false
        override fun canReceive(): Boolean = false
    },
    RESET_REMOTE {
        override fun canSend(): Boolean = false
        override fun canReceive(): Boolean = false
    },
    CLOSED_LOCAL {
        override fun canSend(): Boolean = false
        override fun canReceive(): Boolean = true
    },
    CLOSED_REMOTE {
        override fun canSend(): Boolean = true
        override fun canReceive(): Boolean = false
    },
    CLOSED_BOTH_WAYS {
        override fun canSend(): Boolean = false
        override fun canReceive(): Boolean = false
    };

    /**
     * @return true if this peer can send a message through this stream.
     */
    abstract fun canSend(): Boolean

    /**
     * @return true if this peer can receive a message through this stream.
     */
    abstract fun canReceive(): Boolean
}