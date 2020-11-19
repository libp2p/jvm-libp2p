package io.libp2p.etc.types

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class MutableBiMultiMapTest {

    internal val map = MutableBiMultiMapImpl<String, Int>()

    @Test
    fun `sanity test`() {
        assertThat(map.keyToValue).isEmpty()
        assertThat(map.valueToKeys).isEmpty()

        map["1a"] = 1
        map["1b"] = 1
        map["1c"] = 1
        map["2a"] = 2

        assertThat(map["1a"]).isEqualTo(1)
        assertThat(map["1b"]).isEqualTo(1)
        assertThat(map["1c"]).isEqualTo(1)
        assertThat(map["2a"]).isEqualTo(2)
        assertThat(map["1d"]).isNull()
        assertThat(map.size()).isEqualTo(4)

        assertThat(map.getKeys(1)).containsExactlyInAnyOrder("1a", "1b", "1c")
        assertThat(map.getKeys(2)).containsExactlyInAnyOrder("2a")
        assertThat(map.getKeys(3)).isEmpty()

        map -= "1b"
        assertThat(map["1b"]).isNull()
        assertThat(map.getKeys(1)).containsExactlyInAnyOrder("1a", "1c")
        assertThat(map.size()).isEqualTo(3)

        map["1b"] = 11
        assertThat(map["1b"]).isEqualTo(11)
        assertThat(map.getKeys(1)).containsExactlyInAnyOrder("1a", "1c")
        assertThat(map.getKeys(11)).containsExactlyInAnyOrder("1b")
        assertThat(map.getKeys(2)).containsExactlyInAnyOrder("2a")
        assertThat(map.size()).isEqualTo(4)

        map.removeAllByValue(11)
        assertThat(map["1b"]).isNull()
        assertThat(map.getKeys(11)).isEmpty()
        assertThat(map.size()).isEqualTo(3)

        map.removeAllByValue(1)
        assertThat(map["1a"]).isNull()
        assertThat(map["1c"]).isNull()
        assertThat(map.getKeys(1)).isEmpty()
        assertThat(map.size()).isEqualTo(1)

        map.removeAllByValue(2)
        assertThat(map["2a"]).isNull()
        assertThat(map.getKeys(2)).isEmpty()
        assertThat(map.size()).isEqualTo(0)

        assertThat(map.keyToValue).isEmpty()
        assertThat(map.valueToKeys).isEmpty()
    }

    @Test
    fun `test that removing all keys for the same value clears the map`() {
        assertThat(map.keyToValue).isEmpty()
        assertThat(map.valueToKeys).isEmpty()

        map["1a"] = 1
        map["1b"] = 1
        map["1c"] = 1

        assertThat(map.size()).isEqualTo(3)

        map -= "1a"
        map -= "1b"
        map -= "1c"

        assertThat(map.size()).isEqualTo(0)
        assertThat(map.keyToValue).isEmpty()
        assertThat(map.valueToKeys).isEmpty()
    }

    @Test
    fun `test overwriting key with new value`() {
        map["a"] = 1
        map["a"] = 2

        assertThat(map.size()).isEqualTo(1)
        assertThat(map["a"]).isEqualTo(2)
        assertThat(map.getKeys(1)).isEmpty()
        assertThat(map.getKeys(2)).containsExactlyInAnyOrder("a")

        map.removeAllByValue(1)

        assertThat(map.size()).isEqualTo(1)
        assertThat(map["a"]).isEqualTo(2)
        assertThat(map.getKeys(1)).isEmpty()
        assertThat(map.getKeys(2)).containsExactlyInAnyOrder("a")

        map.removeAllByValue(2)

        assertThat(map.size()).isEqualTo(0)
        assertThat(map.keyToValue).isEmpty()
        assertThat(map.valueToKeys).isEmpty()
    }

    @Test
    fun `test remove non existing key`() {
        assertThatCode {
            map -= "a"
        }.doesNotThrowAnyException()

        assertThat(map.size()).isEqualTo(0)
        assertThat(map.keyToValue).isEmpty()
        assertThat(map.valueToKeys).isEmpty()
    }

    @Test
    fun `test remove non existing value`() {
        assertThatCode {
            map.removeAllByValue(1)
        }.doesNotThrowAnyException()

        assertThat(map.size()).isEqualTo(0)
        assertThat(map.keyToValue).isEmpty()
        assertThat(map.valueToKeys).isEmpty()
    }
}
