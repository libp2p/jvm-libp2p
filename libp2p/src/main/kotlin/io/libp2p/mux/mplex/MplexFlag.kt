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
package io.libp2p.mux.mplex

import io.libp2p.mux.mplex.MplexFlag.Type.*

/**
 * Contains all the permissible values for flags in the <code>mplex</code> protocol.
 */
enum class MplexFlag(
    val value: Int,
    val type: Type
) {
    NewStream(0, OPEN),
    MessageReceiver(1, DATA),
    MessageInitiator(2, DATA),
    CloseReceiver(3, CLOSE),
    CloseInitiator(4, CLOSE),
    ResetReceiver(5, RESET),
    ResetInitiator(6, RESET);

    enum class Type {
        OPEN,
        DATA,
        CLOSE,
        RESET
    }

    val isInitiator get() = value % 2 == 0

    private val initiatorString get() = when (isInitiator) {
        true -> "init"
        false -> "resp"
    }

    override fun toString(): String = "$type($initiatorString)"

    companion object {
        private val valueToFlag = MplexFlag.values().associateBy { it.value }

        fun getByValue(flagValue: Int): MplexFlag =
            valueToFlag[flagValue] ?: throw IllegalArgumentException("Invalid Mplex stream tag: $flagValue")

        fun getByType(type: Type, initiator: Boolean): MplexFlag =
            when (type) {
                OPEN -> NewStream
                DATA -> if (initiator) MessageInitiator else MessageReceiver
                CLOSE -> if (initiator) CloseInitiator else CloseReceiver
                RESET -> if (initiator) ResetInitiator else ResetReceiver
            }
    }
}
