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

import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.handler.timeout.ReadTimeoutHandler
import java.util.concurrent.TimeUnit

/**
 * An [ChannelInitializer] that is responsible for setting up the channel encoders and decoders for using mplex.
 */
class MplexChannelInitializer : ChannelInitializer<Channel>() {

    override fun initChannel(ch: Channel) {
        val prehandlers = listOf(
            ReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            MplexFrameCodec()
        )

        prehandlers.forEach { ch.pipeline().addLast(it) }
        ch.pipeline().addLast(MplexFrameHandler())
    }

    companion object {
        private const val TIMEOUT_MILLIS: Long = 10_000
    }

}