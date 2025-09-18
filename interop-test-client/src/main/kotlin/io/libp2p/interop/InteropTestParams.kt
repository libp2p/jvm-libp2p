package io.libp2p.interop

import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.stream.Collectors

class InteropTestParams(
    val transport: String?,
    val muxer: String?,
    val security: String?,
    val isDialer: Boolean,
    val ip: String?,
    val redisAddress: String?,
    val testTimeoutInSeconds: Int
) {

    data class Builder(
        var transport: String? = "",
        var muxer: String? = "",
        var security: String? = "",
        var isDialer: Boolean = false,
        var ip: String? = "",
        var redisAddress: String? = "",
        var testTimeoutInSeconds: Int = 180
    ) {
        fun transport(transport: String) = apply { this.transport = transport }
        fun muxer(muxer: String) = apply { this.muxer = muxer }
        fun security(security: String) = apply { this.security = security }
        fun isDialer(isDialer: Boolean) = apply { this.isDialer = isDialer }
        fun ip(ip: String) = apply { this.ip = ip }
        fun redisAddress(redisAddress: String) = apply { this.redisAddress = redisAddress }
        fun testTimeoutInSeconds(testTimeoutInSeconds: Int) =
            apply { this.testTimeoutInSeconds = testTimeoutInSeconds }

        fun build(): InteropTestParams {
            checkNonEmptyParam("transport", transport)
            checkNonEmptyParam("muxer", muxer)
            if (transport != QUIC_V1) {
                checkNonEmptyParam("security", security)
            }

            if (redisAddress == null || redisAddress!!.isBlank()) {
                redisAddress = "redis:6379"
            }

            if (ip == null || ip!!.isBlank()) {
                ip = "0.0.0.0"
            }
            if (!isDialer && ip.equals("0.0.0.0")) {
                ip = getLocalIPAddress()
            }

            return InteropTestParams(
                transport,
                muxer,
                security,
                isDialer,
                ip,
                redisAddress,
                testTimeoutInSeconds
            )
        }

        private fun checkNonEmptyParam(paramName: String, paramValue: String?) {
            if (paramValue == null) {
                throw IllegalArgumentException("Parameter '$paramName' must be non-empty")
            }
        }

        fun fromEnvironmentVariables(): Builder {
            return Builder(
                transport = System.getenv("transport"),
                muxer = System.getenv("muxer"),
                security = System.getenv("security"),
                isDialer = System.getenv("is_dialer")?.toBooleanStrictOrNull() ?: false,
                ip = System.getenv("ip"),
                redisAddress = System.getenv("redis_addr"),
                testTimeoutInSeconds = System.getenv("test_timeout_seconds")?.toInt() ?: 180
            )
        }

        private fun getLocalIPAddress(): String {
            val interfaces =
                NetworkInterface.networkInterfaces().collect(Collectors.toList())
            for (inter in interfaces) {
                for (addr in inter.interfaceAddresses) {
                    val address = addr.address
                    if (!address.isLoopbackAddress && address !is Inet6Address) return address.hostAddress
                }
            }
            throw IllegalStateException("Unable to determine local IPAddress")
        }
    }

    override fun toString(): String {
        return "InteropTestParams(transport=$transport, muxer=$muxer, security=$security, isDialer=$isDialer, ip=$ip, redisAddress=$redisAddress, testTimeoutInSeconds=$testTimeoutInSeconds)"
    }
}
