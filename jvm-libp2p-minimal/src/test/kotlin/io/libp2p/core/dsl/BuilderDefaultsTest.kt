package io.libp2p.core.dsl

import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS

class BuilderDefaultsTest {
    @Test
    fun defaultStarts() {
        val host = host { }
        host.start().get(5, SECONDS)
    }

    @Test
    fun noDefaultsFails() {
        assertThrows(IllegalStateException::class.java) {
            host(Builder.Defaults.None) { }
        }
    }

    @Test
    fun noneWithIdentityFails() {
        assertThrows(HostConfigurationException::class.java) {
            host(Builder.Defaults.None) {
                identity { random() }
            }
        }
    }

    @Test
    fun noneWithTransportFails() {
        assertThrows(HostConfigurationException::class.java) {
            host(Builder.Defaults.None) {
                identity { random() }
                transports { +::TcpTransport }
            }
        }
    }

    @Test
    fun noneWithTransportAndSecFails() {
        assertThrows(HostConfigurationException::class.java) {
            host(Builder.Defaults.None) {
                identity { random() }
                transports { +::TcpTransport }
                secureChannels { +::SecIoSecureChannel }
            }
        }
    }

    @Test
    fun noneWithTransportAndSecMuxStarts() {
        val host = host(Builder.Defaults.None) {
            identity { random() }
            transports { +::TcpTransport }
            secureChannels { +::SecIoSecureChannel }
            muxers { + StreamMuxerProtocol.Mplex }
        }

        host.start().get(5, SECONDS)
    }
}
