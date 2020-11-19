package io.libp2p.etc.types

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MutableBiMultiMapTest {

    @Test
    fun `sanity test`() {
        val m = MutableBiMultiMapImpl<String, Int>()
        assertThat(m.keyToValue).isEmpty()
        assertThat(m.valueToKeys).isEmpty()

        m["1a"] = 1
        m["1b"] = 1
        m["1c"] = 1
        m["2a"] = 2

        assertThat(m["1a"]).isEqualTo(1)
        assertThat(m["1b"]).isEqualTo(1)
        assertThat(m["1c"]).isEqualTo(1)
        assertThat(m["2a"]).isEqualTo(2)
        assertThat(m["1d"]).isNull()
        assertThat(m.size()).isEqualTo(4)

        assertThat(m.getKeys(1)).containsExactlyInAnyOrder("1a", "1b", "1c")
        assertThat(m.getKeys(2)).containsExactlyInAnyOrder("2a")
        assertThat(m.getKeys(3)).isEmpty()

        m -= "1b"
        assertThat(m["1b"]).isNull()
        assertThat(m.getKeys(1)).containsExactlyInAnyOrder("1a", "1c")
        assertThat(m.size()).isEqualTo(3)

        m["1b"] = 11
        assertThat(m["1b"]).isEqualTo(11)
        assertThat(m.getKeys(1)).containsExactlyInAnyOrder("1a", "1c")
        assertThat(m.getKeys(11)).containsExactlyInAnyOrder("1b")
        assertThat(m.getKeys(2)).containsExactlyInAnyOrder("2a")
        assertThat(m.size()).isEqualTo(4)

        m.removeAllByValue(11)
        assertThat(m["1b"]).isNull()
        assertThat(m.getKeys(11)).isEmpty()
        assertThat(m.size()).isEqualTo(3)

        m.removeAllByValue(1)
        assertThat(m["1a"]).isNull()
        assertThat(m["1c"]).isNull()
        assertThat(m.getKeys(1)).isEmpty()
        assertThat(m.size()).isEqualTo(1)

        m.removeAllByValue(2)
        assertThat(m["2a"]).isNull()
        assertThat(m.getKeys(2)).isEmpty()
        assertThat(m.size()).isEqualTo(0)

        assertThat(m.keyToValue).isEmpty()
        assertThat(m.valueToKeys).isEmpty()
    }

    @Test
    fun `test that removing all keys for the same value clears the map`() {
        val m = MutableBiMultiMapImpl<String, Int>()
        assertThat(m.keyToValue).isEmpty()
        assertThat(m.valueToKeys).isEmpty()

        m["1a"] = 1
        m["1b"] = 1
        m["1c"] = 1

        assertThat(m.size()).isEqualTo(3)

        m -= "1a"
        m -= "1b"
        m -= "1c"

        assertThat(m.size()).isEqualTo(0)
        assertThat(m.keyToValue).isEmpty()
        assertThat(m.valueToKeys).isEmpty()
    }
}
