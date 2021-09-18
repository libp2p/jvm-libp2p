package io.libp2p.etc.types

import com.google.common.util.concurrent.AtomicDouble
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class DelegatesTest {

    val max = AtomicDouble(10.0)
    val maxVal = AtomicDouble(15.0)
    val min = AtomicDouble(5.0)
    val minVal = AtomicDouble(0.0)

    val cappedValueDelegateUpdates = mutableListOf<Double>()
    val cappedDoubleUpdates = mutableListOf<Double>()

    var cappedValueDelegate: Double by CappedValueDelegate(
        0.0,
        { min.get() },
        { minVal.get() },
        { max.get() },
        { maxVal.get() },
        { cappedValueDelegateUpdates += it }
    )

    var cappedInt: Int by cappedVar(10, 5, 20)
    var cappedDouble: Double by cappedDouble(0.0, 1.0, { -> max.get() }, { cappedDoubleUpdates += it })
    var blackhole: Double = 0.0

    @Test
    fun cappedVarTest() {
        Assertions.assertEquals(10, cappedInt)
        cappedInt--
        Assertions.assertEquals(9, cappedInt)
        cappedInt -= 100
        Assertions.assertEquals(5, cappedInt)
        cappedInt = 19
        Assertions.assertEquals(19, cappedInt)
        cappedInt++
        Assertions.assertEquals(20, cappedInt)
        cappedInt++
        Assertions.assertEquals(20, cappedInt)
    }

    @Test
    fun cappedDoubleTest() {
        assertThat(cappedDouble).isEqualTo(0.0)

        cappedDouble = 5.0
        assertThat(cappedDouble).isEqualTo(5.0)

        cappedDouble = .5
        assertThat(cappedDouble).isEqualTo(0.0)

        cappedDouble = 10.5
        assertThat(cappedDouble).isEqualTo(10.0)

        cappedDouble--
        assertThat(cappedDouble).isEqualTo(9.0)

        max.set(5.0)
        assertThat(cappedDouble).isEqualTo(5.0)
    }

    @Test
    fun cappedValueDelegateTest() {
        assertThat(cappedValueDelegate).isEqualTo(0.0)

        cappedValueDelegate = 5.0
        assertThat(cappedValueDelegate).isEqualTo(5.0)

        cappedValueDelegate = 4.0
        assertThat(cappedValueDelegate).isEqualTo(0.0)

        cappedValueDelegate = 10.0
        assertThat(cappedValueDelegate).isEqualTo(10.0)

        cappedValueDelegate = 11.0
        assertThat(cappedValueDelegate).isEqualTo(15.0)

        // Check maxVal update is respected
        cappedValueDelegate = 11.0
        maxVal.set(13.0)
        assertThat(cappedValueDelegate).isEqualTo(13.0)
        maxVal.set(15.0)

        // Check max update is respected
        cappedValueDelegate = 10.0
        max.set(9.0)
        assertThat(cappedValueDelegate).isEqualTo(15.0)
        max.set(10.0)

        // Check minVal update is respected
        cappedValueDelegate = 4.0
        minVal.set(-1.0)
        assertThat(cappedValueDelegate).isEqualTo(-1.0)
        minVal.set(0.0)

        // Check min update is respected
        cappedValueDelegate = 6.0
        min.set(7.0)
        assertThat(cappedValueDelegate).isEqualTo(0.0)
    }

    @Test
    fun `test cappedDouble update callback`() {
        cappedDouble = 5.0
        assertThat(cappedDoubleUpdates).containsExactly(5.0)

        cappedDouble = 5.0
        assertThat(cappedDoubleUpdates).containsExactly(5.0)

        cappedDouble = 4.0
        assertThat(cappedDoubleUpdates).containsExactly(5.0, 4.0)

        max.set(3.0)
        blackhole = cappedDouble
        assertThat(cappedDoubleUpdates).containsExactly(5.0, 4.0, 3.0)

        max.set(5.0)
        blackhole = cappedDouble
        assertThat(cappedDoubleUpdates).containsExactly(5.0, 4.0, 3.0)
    }

    @Test
    fun `test cappedValueDelegate update callback`() {
        cappedValueDelegate = 8.0
        assertThat(cappedValueDelegateUpdates).containsExactly(8.0)

        cappedValueDelegate = 8.0
        assertThat(cappedValueDelegateUpdates).containsExactly(8.0)

        cappedValueDelegate = 7.0
        assertThat(cappedValueDelegateUpdates).containsExactly(8.0, 7.0)

        max.set(6.0)
        blackhole = cappedValueDelegate
        assertThat(cappedValueDelegateUpdates).containsExactly(8.0, 7.0, 15.0)

        max.set(7.0)
        blackhole = cappedValueDelegate
        assertThat(cappedValueDelegateUpdates).containsExactly(8.0, 7.0, 15.0)
    }
}
