package io.libp2p.core.crypto

import io.libp2p.etc.types.toHex
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jcajce.provider.digest.SHA1
import org.bouncycastle.jcajce.provider.digest.SHA256
import org.bouncycastle.jcajce.provider.digest.SHA512
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HashTest {

    @Test
    fun `sha256 matches the FIPS 180 test vectors`() {
        assertThat(sha256(ByteArray(0)).toHex())
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(sha256("abc".toByteArray()).toHex())
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".toByteArray()).toHex())
            .isEqualTo("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    }

    @Test
    fun `sha1 and sha512 match the FIPS 180 test vectors`() {
        assertThat(sha1("abc".toByteArray()).toHex())
            .isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d")
        assertThat(sha512("abc".toByteArray()).toHex())
            .isEqualTo(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                    "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
            )
    }

    @Test
    fun `digests agree with BouncyCastle on inputs of various sizes`() {
        val random = Random(42)
        for (size in listOf(0, 1, 55, 56, 63, 64, 65, 1000, 4096, 65536, 300_000)) {
            val data = ByteArray(size).also { random.nextBytes(it) }
            assertThat(sha256(data)).`as`("sha256 of $size bytes").isEqualTo(SHA256.Digest().digest(data))
            assertThat(sha1(data)).`as`("sha1 of $size bytes").isEqualTo(SHA1.Digest().digest(data))
            assertThat(sha512(data)).`as`("sha512 of $size bytes").isEqualTo(SHA512.Digest().digest(data))
        }
    }

    @Test
    fun `repeated and concurrent calls do not leak state between invocations`() {
        val random = Random(7)
        val inputs = (0 until 64).map { ByteArray(1 + random.nextInt(5000)).also { random.nextBytes(it) } }
        val expected = inputs.map { SHA256.Digest().digest(it) }

        // same thread, back to back
        inputs.forEachIndexed { i, data -> assertThat(sha256(data)).isEqualTo(expected[i]) }

        // many threads hammering the function concurrently
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 64).map { t ->
                executor.submit<Boolean> {
                    (0 until 200).all { i ->
                        val idx = (i + t) % inputs.size
                        sha256(inputs[idx]).contentEquals(expected[idx])
                    }
                }
            }
            assertThat(futures.map { it.get(30, TimeUnit.SECONDS) }).containsOnly(true)
        } finally {
            executor.shutdownNow()
        }
    }
}
