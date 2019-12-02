package io.libp2p.core.multiformats

import com.google.common.io.BaseEncoding
import io.libp2p.etc.types.toByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class MultihashTest {

    companion object {
        @JvmStatic
        fun params() = listOf(
            Arguments.of(Multihash.Descriptor(Multihash.Digest.Identity), -1, "foo", "0003666f6f"),
            Arguments.of(Multihash.Descriptor(Multihash.Digest.Identity), 24, "foo", "0003666f6f"),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.Identity),
                -1,
                "foofoofoofoofoofoofoofoofoofoofoofoofoofoofoofoo",
                "0030666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f666f6f"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA1, 160),
                -1,
                "foo",
                "11140beec7b5ea3f0fdbc95d0dd47f3c5bc275da8a33"
            ),
            Arguments.of(Multihash.Descriptor(Multihash.Digest.SHA1, 160), 80, "foo", "110a0beec7b5ea3f0fdbc95d"),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA2, 256),
                -1,
                "foo",
                "12202c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA2, 256),
                128,
                "foo",
                "12102c26b46b68ffc68ff99b453c1d304134"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA2, 512),
                -1,
                "foo",
                "1340f7fbba6e0636f890e56fbbf3283e524c6fa3204ae298382d624741d0dc6638326e282c41be5e4254d8820772c5518a2c5a8c0c7f7eda19594a7eb539453e1ed7"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA2, 512),
                256,
                "foo",
                "1320f7fbba6e0636f890e56fbbf3283e524c6fa3204ae298382d624741d0dc663832"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 512),
                256,
                "foo",
                "14204bca2b137edc580fe50a88983ef860ebaca36c857b1f492839d6d7392452a63c"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 512),
                128,
                "foo",
                "14104bca2b137edc580fe50a88983ef860eb"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 512),
                -1,
                "foo",
                "14404bca2b137edc580fe50a88983ef860ebaca36c857b1f492839d6d7392452a63c82cbebc68e3b70a2a1480b4bb5d437a7cba6ecf9d89f9ff3ccd14cd6146ea7e7"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 224),
                -1,
                "beep boop",
                "171c0da73a89549018df311c0a63250e008f7be357f93ba4e582aaea32b8"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 224),
                128,
                "beep boop",
                "17100da73a89549018df311c0a63250e008f"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 256),
                -1,
                "beep boop",
                "1620828705da60284b39de02e3599d1f39e6c1df001f5dbf63c9ec2d2c91a95a427f"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 256),
                128,
                "beep boop",
                "1610828705da60284b39de02e3599d1f39e6"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 384),
                -1,
                "beep boop",
                "153075a9cff1bcfbe8a7025aa225dd558fb002769d4bf3b67d2aaf180459172208bea989804aefccf060b583e629e5f41e8d"
            ),
            Arguments.of(
                Multihash.Descriptor(Multihash.Digest.SHA3, 384),
                128,
                "beep boop",
                "151075a9cff1bcfbe8a7025aa225dd558fb0"
            )
        )
    }

    @ParameterizedTest
    @MethodSource("params")
    fun `Multihash digest and of`(desc: Multihash.Descriptor, length: Int, content: String, expected: String) {
        val hex = BaseEncoding.base16()
        val mh = Multihash.digest(desc, content.toByteArray().toByteBuf(), if (length == -1) null else length).bytes
        val decodedMh = hex.decode(expected.toUpperCase()).toByteBuf()
        assertEquals(decodedMh, mh)
        with(Multihash.of(mh)) {
            assertEquals(desc, this.desc)
            if (length != -1) assertEquals(length, this.lengthBits)
        }
    }
}