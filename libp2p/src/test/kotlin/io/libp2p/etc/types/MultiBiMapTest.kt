package io.libp2p.etc.types

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class MultiBiMapTest {

    val map = mutableMultiBiMap<String, Int>()

    @Test
    fun `empty map test`() {
        checkEmpty()
        assertThat(map.getByFirst("any")).isEmpty()
        assertThat(map.getBySecond(111)).isEmpty()
    }

    private fun checkEmpty() {
        assertThat(map.valuesFirst()).isEmpty()
        assertThat(map.valuesSecond()).isEmpty()
        assertThat(map.asFirstToSecondMap()).isEmpty()
        assertThat(map.asSecondToFirstMap()).isEmpty()
    }

    @Test
    fun `add one test`() {
        map.add("a", 1)

        assertThat(map.valuesFirst()).containsExactlyInAnyOrder("a")
        assertThat(map.valuesSecond()).containsExactlyInAnyOrder(1)
        assertThat(map.asFirstToSecondMap()).isEqualTo(mapOf("a" to setOf(1)))
        assertThat(map.asSecondToFirstMap()).isEqualTo(mapOf(1 to setOf("a")))
        assertThat(map.getByFirst("b")).isEmpty()
        assertThat(map.getByFirst("a")).isEqualTo(setOf(1))
        assertThat(map.getBySecond(2)).isEmpty()
        assertThat(map.getBySecond(1)).isEqualTo(setOf("a"))
    }

    @Test
    fun `add-remove one test`() {
        map.add("a", 1)
        map.remove("a", 1)

        checkEmpty()
        assertThat(map.getByFirst("a")).isEmpty()
        assertThat(map.getBySecond(1)).isEmpty()
    }

    @Test
    fun `add-remove two test`() {
        map.add("a", 1)
        map.add("a", 2)
        map.remove("a", 1)
        map.remove("a", 2)

        checkEmpty()
        assertThat(map.getByFirst("a")).isEmpty()
        assertThat(map.getBySecond(1)).isEmpty()
        assertThat(map.getBySecond(2)).isEmpty()
    }

    @Test
    fun `add two remove by first test`() {
        map.add("a", 1)
        map.add("a", 2)
        map.removeAllByFirst("a")

        checkEmpty()
        assertThat(map.getByFirst("a")).isEmpty()
        assertThat(map.getBySecond(1)).isEmpty()
        assertThat(map.getBySecond(2)).isEmpty()
    }

    @Test
    fun `add two remove by second test`() {
        map.add("a", 1)
        map.add("b", 1)
        map.removeAllBySecond(1)

        checkEmpty()
        assertThat(map.getByFirst("a")).isEmpty()
        assertThat(map.getBySecond(1)).isEmpty()
        assertThat(map.getBySecond(2)).isEmpty()
    }

    @Test
    fun `add two test`() {
        map.add("a", 1)
        map.add("a", 2)

        assertThat(map.valuesFirst()).containsExactlyInAnyOrder("a")
        assertThat(map.valuesSecond()).containsExactlyInAnyOrder(1, 2)
        assertThat(map.asFirstToSecondMap()).isEqualTo(mapOf("a" to setOf(1, 2)))
        assertThat(map.asSecondToFirstMap()).isEqualTo(mapOf(1 to setOf("a"), 2 to setOf("a")))
        assertThat(map.getByFirst("b")).isEmpty()
        assertThat(map.getByFirst("a")).isEqualTo(setOf(1, 2))
        assertThat(map.getBySecond(1)).isEqualTo(setOf("a"))
        assertThat(map.getBySecond(2)).isEqualTo(setOf("a"))
        assertThat(map.getBySecond(3)).isEmpty()
    }

    @Test
    fun `add two remove one test`() {
        map.add("a", 1)
        map.add("a", 2)
        map.remove("a", 1)

        assertThat(map.valuesFirst()).containsExactlyInAnyOrder("a")
        assertThat(map.valuesSecond()).containsExactlyInAnyOrder(2)
        assertThat(map.asFirstToSecondMap()).isEqualTo(mapOf("a" to setOf(2)))
        assertThat(map.asSecondToFirstMap()).isEqualTo(mapOf(2 to setOf("a")))
        assertThat(map.getByFirst("b")).isEmpty()
        assertThat(map.getByFirst("a")).isEqualTo(setOf(2))
        assertThat(map.getBySecond(1)).isEmpty()
        assertThat(map.getBySecond(2)).isEqualTo(setOf("a"))
    }

    @Test
    fun `remove missing values does nothing`() {
        map.add("a", 1)

        assertDoesNotThrow { map.remove("a", 2) }
        assertDoesNotThrow { map.remove("b", 1) }
        assertDoesNotThrow { map.removeAllByFirst("b") }
        assertDoesNotThrow { map.removeAllBySecond(2) }

        assertThat(map.valuesFirst()).containsExactlyInAnyOrder("a")
        assertThat(map.valuesSecond()).containsExactlyInAnyOrder(1)
        assertThat(map.asFirstToSecondMap()).isEqualTo(mapOf("a" to setOf(1)))
        assertThat(map.asSecondToFirstMap()).isEqualTo(mapOf(1 to setOf("a")))
        assertThat(map.getByFirst("b")).isEmpty()
        assertThat(map.getByFirst("a")).isEqualTo(setOf(1))
        assertThat(map.getBySecond(2)).isEmpty()
        assertThat(map.getBySecond(1)).isEqualTo(setOf("a"))
    }

    @Test
    fun `add four remove one test`() {
        map.add("a", 1)
        map.add("a", 2)
        map.add("b", 1)
        map.add("b", 2)
        map.remove("a", 1)

        assertThat(map.valuesFirst()).containsExactlyInAnyOrder("a", "b")
        assertThat(map.valuesSecond()).containsExactlyInAnyOrder(1, 2)
        assertThat(map.asFirstToSecondMap()).isEqualTo(mapOf("a" to setOf(2), "b" to setOf(1, 2)))
        assertThat(map.asSecondToFirstMap()).isEqualTo(mapOf(1 to setOf("b"), 2 to setOf("a", "b")))
        assertThat(map.getByFirst("a")).isEqualTo(setOf(2))
        assertThat(map.getByFirst("b")).isEqualTo(setOf(1, 2))
        assertThat(map.getBySecond(1)).isEqualTo(setOf("b"))
        assertThat(map.getBySecond(2)).isEqualTo(setOf("a", "b"))
    }

    @Test
    fun `add four remove all by first test`() {
        map.add("a", 1)
        map.add("a", 2)
        map.add("b", 1)
        map.add("b", 2)
        map.removeAllByFirst("a")

        assertThat(map.valuesFirst()).containsExactlyInAnyOrder("b")
        assertThat(map.valuesSecond()).containsExactlyInAnyOrder(1, 2)
        assertThat(map.asFirstToSecondMap()).isEqualTo(mapOf("b" to setOf(1, 2)))
        assertThat(map.asSecondToFirstMap()).isEqualTo(mapOf(1 to setOf("b"), 2 to setOf("b")))
        assertThat(map.getByFirst("a")).isEmpty()
        assertThat(map.getByFirst("b")).isEqualTo(setOf(1, 2))
        assertThat(map.getBySecond(1)).isEqualTo(setOf("b"))
        assertThat(map.getBySecond(2)).isEqualTo(setOf("b"))
    }

    @Test
    fun `add four remove all by second test`() {
        map.add("a", 1)
        map.add("a", 2)
        map.add("b", 1)
        map.add("b", 2)
        map.removeAllBySecond(1)

        assertThat(map.valuesFirst()).containsExactlyInAnyOrder("a", "b")
        assertThat(map.valuesSecond()).containsExactlyInAnyOrder(2)
        assertThat(map.asFirstToSecondMap()).isEqualTo(mapOf("a" to setOf(2), "b" to setOf(2)))
        assertThat(map.asSecondToFirstMap()).isEqualTo(mapOf(2 to setOf("a", "b")))
        assertThat(map.getByFirst("a")).isEqualTo(setOf(2))
        assertThat(map.getByFirst("b")).isEqualTo(setOf(2))
        assertThat(map.getBySecond(1)).isEmpty()
        assertThat(map.getBySecond(2)).isEqualTo(setOf("a", "b"))
    }
}
