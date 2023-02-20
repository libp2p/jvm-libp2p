package io.libp2p.simulate.connection

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.etc.types.lazyVar
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.TcpTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class LoopbackTransport(
    upgrader: ConnectionUpgrader,
    val net: LoopbackNetwork,
    val simExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    val localIp: String = "127.0.0.1"
) : TcpTransport(upgrader) {
    val listeners = mutableMapOf<Int, ConnectionHandler>()
    val portManager = PortManager()

    override fun handles(addr: Multiaddr): Boolean = addr.getFirstComponent(Protocol.TCP) != null
    override fun initialize() {}
    override fun close(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Unit> {
        val port = addr.getFirstComponent(Protocol.TCP)?.stringValue?.toInt()
            ?: throw IllegalArgumentException("No TCP component in listen addr: $addr")
        portManager.acquire(port)
        listeners[port] = connHandler
        return CompletableFuture.completedFuture(Unit)
    }

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        val port = addr.getFirstComponent(Protocol.TCP)?.stringValue?.toInt()
            ?: throw IllegalArgumentException("No TCP component in listen addr: $addr")
        portManager.free(port)
        listeners.remove(port)
        return CompletableFuture.completedFuture(Unit)
    }

    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Connection> = TODO()
//    {
//        val remoteIp = addr.getStringComponent(Protocol.IP4) ?: throw IllegalArgumentException("No IP4 component in dial addr: $addr")
//        val remotePort = addr.getStringComponent(Protocol.TCP)?.toInt() ?: throw IllegalArgumentException("No TCP component in dial addr: $addr")
//        val remoteTransport = net.ipTransportMap[remoteIp] ?: return completedExceptionally(ConnectException("No peers listening on IP $remoteIp"))
//        val remoteHandler = remoteTransport.listeners[remotePort] ?: return completedExceptionally(ConnectException("Remote peer $remoteIp not listening on port $remotePort"))
//
//        val localPort = portManager.acquire()
//
//        // TODO left from migration from version 0.1 to 0.5
//        val (localChannelHandler, connFut) =
//            createConnectionHandler(connHandler, true)
//        val (remoteChannelHandler, _) =
//            remoteTransport.createConnectionHandler(remoteHandler, false)
//
//        val localSimChannel = StreamSimChannel(
//            "L:$localIp:$localPort=>R:$remoteIp:$remotePort",
//            localChannelHandler
//        ).also {
//            it.executor = simExecutor
//        }
//        val remoteSimChannel = StreamSimChannel(
//            "L:$remoteIp:$remotePort<=R:$localIp:$localPort",
//            remoteChannelHandler
//        ).also {
//            it.executor = simExecutor
//        }
//
//        val simConnection = StreamSimChannel.interConnect(localSimChannel, remoteSimChannel)
//        // TODO add simConnection to network
//        return connFut
//    }
}

class PortManager(var startDialPort: Int = 20000) {
    private var portCounter by lazyVar { startDialPort }
    private val busyPorts = mutableSetOf<Int>()

    @Synchronized
    fun acquire(port: Int) {
        if (!busyPorts.add(port)) {
            throw IllegalArgumentException("Can't acquire busy port $port")
        }
    }

    @Synchronized
    fun acquire(): Int {
        var lim = 65536
        while (busyPorts.contains(portCounter)) {
            portCounter++
            portCounter = if (portCounter > 65535) startDialPort else portCounter
            check(lim-- != 0) { "All ports are busy" }
        }
        busyPorts += portCounter
        return portCounter
    }

    @Synchronized
    fun free(port: Int) {
        busyPorts -= port
    }
}
