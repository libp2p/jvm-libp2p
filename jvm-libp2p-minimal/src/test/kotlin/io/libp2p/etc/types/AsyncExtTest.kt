package io.libp2p.etc.types

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AsyncExtTest {

    @Test
    fun testThenApplyAll() {
        val futs = List(3) { CompletableFuture<Int>() }
        val allFut = futs.thenApplyAll { it.sum() }
        assert(!allFut.isDone)
        futs[0].complete(1)
        assert(!allFut.isDone)
        futs[1].complete(2)
        assert(!allFut.isDone)
        futs[2].complete(3)
        assert(allFut.isDone)
        assert(allFut.get() == 6)
    }

    @Test
    fun testAnyCompleteWithCompletedExceptionally() {
        val anyComplete = anyComplete<Int>(completedExceptionally(RuntimeException("test")))

        Assertions.assertTrue(anyComplete.isCompletedExceptionally)
    }
}
