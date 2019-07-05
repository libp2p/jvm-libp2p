package io.libp2p.core

import io.netty.channel.Channel

class Stream(val ch: Channel, val conn: Connection)