package io.libp2p.core.crypto

import java.security.MessageDigest

/**
 * The digests below are provided by the JDK rather than BouncyCastle: HotSpot intrinsifies
 * `sun.security.provider` SHA implementations (SHA-NI / AVX2 / NEON), which on typical server
 * CPUs hashes an order of magnitude faster than BouncyCastle's pure-Java digests. The output is
 * identical. The [MessageDigest] instances are reused per thread as creating one involves a
 * provider lookup and a few allocations.
 */
private class ThreadLocalDigest(algorithm: String) {
    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance(algorithm) }

    /** Returns a reset digest instance owned by the calling thread. */
    fun get(): MessageDigest = digest.get().also { it.reset() }
}

private val sha1Pool = ThreadLocalDigest("SHA-1")
private val sha256Pool = ThreadLocalDigest("SHA-256")
private val sha512Pool = ThreadLocalDigest("SHA-512")

fun sha1(data: ByteArray): ByteArray = sha1Pool.get().digest(data)
fun sha256(data: ByteArray): ByteArray = sha256Pool.get().digest(data)
fun sha512(data: ByteArray): ByteArray = sha512Pool.get().digest(data)

/**
 * Returns a reset SHA-256 [MessageDigest] owned by the calling thread, for callers which
 * feed the data incrementally. The instance must not be retained across calls.
 */
internal fun sha256Digest(): MessageDigest = sha256Pool.get()
