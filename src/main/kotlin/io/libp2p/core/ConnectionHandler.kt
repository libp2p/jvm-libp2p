package io.libp2p.core

import java.util.function.Function

abstract class ConnectionHandler: Function<Connection, StreamHandler>