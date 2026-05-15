# Gossipsub OOM Decode-Bound Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound repeated-field counts and reject empty `publish` entries in the pubsub RPC decode pipeline *before* protobuf materialisation, so a remote attacker cannot OOM the JVM with a length-valid gossipsub frame full of empty publish entries.

**Architecture:** Add a new `RpcCountFrameDecoder` Netty handler between `LimitedProtobufVarint32FrameDecoder` and `ProtobufDecoder` in `AbstractRouter.initChannelWithHandler`. It delegates to a pure `RpcMessageCountValidator` that walks the inbound `ByteBuf` once with `CodedInputStream`, counts repeated-field tags, and drops the buffer if any configured limit is exceeded. Limits are exposed via a new `PubsubRpcLimits` data class and a `protected open val rpcLimits` hook on `AbstractRouter`, which `GossipRouter` overrides to project from `GossipParams`. `GossipRouter.validateMessageListLimits` stays as defence-in-depth.

**Tech Stack:** Kotlin 1.6, JDK 11, Netty 4.x, protobuf-java (`com.google.protobuf.CodedInputStream`), JUnit 5, AssertJ.

**Design doc:** `docs/superpowers/specs/2026-05-15-gossipsub-oom-decode-bound-design.md`

---

## File Structure

**Create:**
- `libp2p/src/main/kotlin/io/libp2p/pubsub/PubsubRpcLimits.kt` — immutable limits data class with `NONE` sentinel.
- `libp2p/src/main/kotlin/io/libp2p/pubsub/RpcMessageCountValidator.kt` — pure validator. No Netty dependency.
- `libp2p/src/main/kotlin/io/libp2p/pubsub/RpcCountFrameDecoder.kt` — thin Netty `ByteToMessageDecoder` wrapper.
- `libp2p/src/test/kotlin/io/libp2p/pubsub/RpcMessageCountValidatorTest.kt` — unit tests for the validator.
- `libp2p/src/test/kotlin/io/libp2p/pubsub/RpcCountFrameDecoderTest.kt` — Netty `EmbeddedChannel` integration tests.

**Modify:**
- `libp2p/src/main/kotlin/io/libp2p/pubsub/AbstractRouter.kt` — add `rpcLimits` open property; insert handler in `initChannelWithHandler`.
- `libp2p/src/main/kotlin/io/libp2p/pubsub/gossip/GossipRouter.kt` — override `rpcLimits` to project from `params`.

---

## Task 1: `PubsubRpcLimits` data class

**Files:**
- Create: `libp2p/src/main/kotlin/io/libp2p/pubsub/PubsubRpcLimits.kt`

- [ ] **Step 1: Create the file**

```kotlin
package io.libp2p.pubsub

/**
 * Per-router limits on repeated-field counts inside an inbound pubsub RPC. Enforced
 * at decode time by [RpcMessageCountValidator] to prevent allocation amplification
 * before [pubsub.pb.Rpc.RPC] is materialised.
 *
 * A null field means "no limit" — same semantics as the corresponding nullable
 * fields on `GossipParams`.
 */
data class PubsubRpcLimits(
    val maxPublishedMessages: Int?,
    val maxTopicsPerPublishedMessage: Int?,
    val maxSubscriptions: Int?,
    val maxIHaveMessageIds: Int?,
    val maxIWantMessageIds: Int?,
    val maxGraftMessages: Int?,
    val maxPruneMessages: Int?,
    val maxPeersPerPruneMessage: Int?,
    val maxIDontWantMessages: Int? = null,
    val maxIDontWantMessageIds: Int? = null,
    val rejectEmptyPublishEntries: Boolean = true,
) {
    companion object {
        val NONE = PubsubRpcLimits(
            maxPublishedMessages = null,
            maxTopicsPerPublishedMessage = null,
            maxSubscriptions = null,
            maxIHaveMessageIds = null,
            maxIWantMessageIds = null,
            maxGraftMessages = null,
            maxPruneMessages = null,
            maxPeersPerPruneMessage = null,
            maxIDontWantMessages = null,
            maxIDontWantMessageIds = null,
            rejectEmptyPublishEntries = false,
        )
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :libp2p:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add libp2p/src/main/kotlin/io/libp2p/pubsub/PubsubRpcLimits.kt
git commit -m "feat(pubsub): introduce PubsubRpcLimits for decode-time count caps"
```

---

## Task 2: `RpcMessageCountValidator` — failing tests first

**Files:**
- Test: `libp2p/src/test/kotlin/io/libp2p/pubsub/RpcMessageCountValidatorTest.kt`

We write the test class first with one test for each limit. Implementation comes in Task 3.

- [ ] **Step 1: Create test file with the empty-publish test only**

```kotlin
package io.libp2p.pubsub

import com.google.protobuf.CodedOutputStream
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class RpcMessageCountValidatorTest {

    private val unlimited = PubsubRpcLimits.NONE.copy(rejectEmptyPublishEntries = true)

    private fun bytesOf(rpc: Rpc.RPC) = Unpooled.wrappedBuffer(rpc.toByteArray())

    @Test
    fun `rejects RPC containing an empty publish entry`() {
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.getDefaultInstance()) // empty Message
            .build()

        val result = RpcMessageCountValidator.validate(bytesOf(rpc), unlimited)

        assertThat(result).isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (class missing)**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.RpcMessageCountValidatorTest"`
Expected: compilation error — `RpcMessageCountValidator` unresolved.

---

## Task 3: `RpcMessageCountValidator` — minimal implementation

**Files:**
- Create: `libp2p/src/main/kotlin/io/libp2p/pubsub/RpcMessageCountValidator.kt`

- [ ] **Step 1: Create the validator**

```kotlin
package io.libp2p.pubsub

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import io.netty.buffer.ByteBuf
import java.io.IOException

/**
 * Walks an inbound pubsub RPC [ByteBuf] without materialising any [pubsub.pb.Rpc]
 * message and rejects it if its repeated-field counts violate [PubsubRpcLimits].
 *
 * Field numbers mirror `libp2p/src/main/proto/rpc.proto`. The walker uses
 * [CodedInputStream] to read tags / lengths and to skip bodies, so no
 * `Rpc$Message` / builder is allocated for rejected frames.
 */
object RpcMessageCountValidator {

    sealed interface Result {
        object Accepted : Result
        data class Rejected(val reason: String) : Result
    }

    // pubsub.RPC field numbers
    private const val RPC_SUBSCRIPTIONS = 1
    private const val RPC_PUBLISH = 2
    private const val RPC_CONTROL = 3

    // pubsub.Message field numbers
    private const val MESSAGE_TOPIC_IDS = 4

    // pubsub.ControlMessage field numbers
    private const val CTRL_IHAVE = 1
    private const val CTRL_IWANT = 2
    private const val CTRL_GRAFT = 3
    private const val CTRL_PRUNE = 4
    private const val CTRL_IDONTWANT = 5

    // pubsub.ControlIHave / ControlIWant / ControlIDontWant repeated bytes field numbers
    private const val IHAVE_MESSAGE_IDS = 2
    private const val IWANT_MESSAGE_IDS = 1
    private const val IDONTWANT_MESSAGE_IDS = 1

    // pubsub.ControlPrune.peers
    private const val PRUNE_PEERS = 2

    fun validate(buf: ByteBuf, limits: PubsubRpcLimits): Result {
        val input = CodedInputStream.newInstance(buf.nioBuffer())
        return try {
            validateRpc(input, limits)
        } catch (e: IOException) {
            Result.Rejected("malformed: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            Result.Rejected("malformed: truncated")
        }
    }

    private fun validateRpc(input: CodedInputStream, limits: PubsubRpcLimits): Result {
        var publishCount = 0
        var subscriptionCount = 0

        while (!input.isAtEnd) {
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            when {
                fieldNumber == RPC_SUBSCRIPTIONS &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    subscriptionCount++
                    limits.maxSubscriptions?.let {
                        if (subscriptionCount > it) return Result.Rejected("subscriptions count > $it")
                    }
                    input.skipField(tag)
                }
                fieldNumber == RPC_PUBLISH &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    if (length == 0 && limits.rejectEmptyPublishEntries) {
                        return Result.Rejected("empty publish entry")
                    }
                    publishCount++
                    limits.maxPublishedMessages?.let {
                        if (publishCount > it) return Result.Rejected("publish count > $it")
                    }
                    val oldLimit = input.pushLimit(length)
                    val maxTopics = limits.maxTopicsPerPublishedMessage
                    if (maxTopics != null) {
                        val res = validatePublish(input, maxTopics)
                        if (res is Result.Rejected) return res
                    } else {
                        input.skipMessage()
                    }
                    input.popLimit(oldLimit)
                }
                fieldNumber == RPC_CONTROL &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val res = validateControl(input, limits)
                    if (res is Result.Rejected) return res
                    input.popLimit(oldLimit)
                }
                else -> input.skipField(tag)
            }
        }
        return Result.Accepted
    }

    private fun validatePublish(input: CodedInputStream, maxTopics: Int): Result {
        var topicCount = 0
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (WireFormat.getTagFieldNumber(tag) == MESSAGE_TOPIC_IDS &&
                WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
            ) {
                topicCount++
                if (topicCount > maxTopics) return Result.Rejected("topicIDs per publish > $maxTopics")
                input.skipField(tag)
            } else {
                input.skipField(tag)
            }
        }
        return Result.Accepted
    }

    private fun validateControl(input: CodedInputStream, limits: PubsubRpcLimits): Result {
        var ihaveMsgIds = 0
        var iwantMsgIds = 0
        var graftCount = 0
        var pruneCount = 0
        var idontwantCount = 0
        var idontwantMsgIds = 0

        while (!input.isAtEnd) {
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                input.skipField(tag); continue
            }
            when (fieldNumber) {
                CTRL_IHAVE -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IHAVE_MESSAGE_IDS)
                    ihaveMsgIds += count
                    limits.maxIHaveMessageIds?.let {
                        if (ihaveMsgIds > it) return Result.Rejected("ihave messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_IWANT -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IWANT_MESSAGE_IDS)
                    iwantMsgIds += count
                    limits.maxIWantMessageIds?.let {
                        if (iwantMsgIds > it) return Result.Rejected("iwant messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_GRAFT -> {
                    graftCount++
                    limits.maxGraftMessages?.let {
                        if (graftCount > it) return Result.Rejected("graft count > $it")
                    }
                    input.skipField(tag)
                }
                CTRL_PRUNE -> {
                    pruneCount++
                    limits.maxPruneMessages?.let {
                        if (pruneCount > it) return Result.Rejected("prune count > $it")
                    }
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val maxPeers = limits.maxPeersPerPruneMessage
                    if (maxPeers != null) {
                        val peerCount = countRepeatedMessages(input, PRUNE_PEERS)
                        if (peerCount > maxPeers) return Result.Rejected("peers per prune > $maxPeers")
                    } else {
                        input.skipMessage()
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_IDONTWANT -> {
                    idontwantCount++
                    limits.maxIDontWantMessages?.let {
                        if (idontwantCount > it) return Result.Rejected("idontwant count > $it")
                    }
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IDONTWANT_MESSAGE_IDS)
                    idontwantMsgIds += count
                    limits.maxIDontWantMessageIds?.let {
                        if (idontwantMsgIds > it) return Result.Rejected("idontwant messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                else -> input.skipField(tag)
            }
        }
        return Result.Accepted
    }

    /**
     * Counts occurrences of a length-delimited repeated field inside a sub-message
     * region; skips all bodies. The [CodedInputStream] is already bounded by a
     * pushLimit on the caller side.
     */
    private fun countRepeatedBytes(input: CodedInputStream, fieldNumber: Int): Int {
        var count = 0
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (WireFormat.getTagFieldNumber(tag) == fieldNumber &&
                WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
            ) {
                count++
            }
            input.skipField(tag)
        }
        return count
    }

    private fun countRepeatedMessages(input: CodedInputStream, fieldNumber: Int): Int =
        countRepeatedBytes(input, fieldNumber)
}
```

- [ ] **Step 2: Run the existing failing test**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.RpcMessageCountValidatorTest"`
Expected: PASS — the empty-publish test now succeeds.

- [ ] **Step 3: Commit**

```bash
git add libp2p/src/main/kotlin/io/libp2p/pubsub/RpcMessageCountValidator.kt \
        libp2p/src/test/kotlin/io/libp2p/pubsub/RpcMessageCountValidatorTest.kt
git commit -m "feat(pubsub): add RpcMessageCountValidator pre-decode walker"
```

---

## Task 4: Expand validator unit-test coverage

**Files:**
- Modify: `libp2p/src/test/kotlin/io/libp2p/pubsub/RpcMessageCountValidatorTest.kt`

- [ ] **Step 1: Add helper builders and the full test set**

Add to the test class (replacing the existing minimal helper with a richer set):

```kotlin
import com.google.protobuf.ByteString

private fun message(topics: Int = 0): Rpc.Message {
    val b = Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x"))
    repeat(topics) { b.addTopicIDs("t$it") }
    return b.build()
}

private fun subOpt(topic: String) =
    Rpc.RPC.SubOpts.newBuilder().setTopicid(topic).setSubscribe(true).build()

private fun ihave(ids: Int) = Rpc.ControlIHave.newBuilder()
    .setTopicID("t")
    .also { repeat(ids) { i -> it.addMessageIDs(ByteString.copyFromUtf8("m$i")) } }
    .build()

private fun iwant(ids: Int) = Rpc.ControlIWant.newBuilder()
    .also { repeat(ids) { i -> it.addMessageIDs(ByteString.copyFromUtf8("m$i")) } }
    .build()

private fun idontwant(ids: Int) = Rpc.ControlIDontWant.newBuilder()
    .also { repeat(ids) { i -> it.addMessageIDs(ByteString.copyFromUtf8("m$i")) } }
    .build()

private fun pruneWithPeers(peers: Int) = Rpc.ControlPrune.newBuilder()
    .setTopicID("t")
    .also { repeat(peers) { i -> it.addPeers(Rpc.PeerInfo.newBuilder().setPeerID(ByteString.copyFromUtf8("p$i"))) } }
    .build()
```

Append the test methods:

```kotlin
@Test
fun `accepts non-empty publish when allowed`() {
    val rpc = Rpc.RPC.newBuilder().addPublish(message(topics = 1)).build()
    val limits = PubsubRpcLimits.NONE
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isEqualTo(RpcMessageCountValidator.Result.Accepted)
}

@Test
fun `rejects when publish count exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .apply { repeat(3) { addPublish(message(topics = 1)) } }
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxPublishedMessages = 2)
    val result = RpcMessageCountValidator.validate(bytesOf(rpc), limits)
    assertThat(result).isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when subscriptions count exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .apply { repeat(3) { addSubscriptions(subOpt("t$it")) } }
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxSubscriptions = 2)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when topicIDs per publish exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder().addPublish(message(topics = 5)).build()
    val limits = PubsubRpcLimits.NONE.copy(maxTopicsPerPublishedMessage = 4)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when ihave messageIDs total exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .setControl(
            Rpc.ControlMessage.newBuilder()
                .addIhave(ihave(ids = 4))
                .addIhave(ihave(ids = 4))
        )
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxIHaveMessageIds = 7)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when iwant messageIDs total exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .setControl(Rpc.ControlMessage.newBuilder().addIwant(iwant(ids = 10)))
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxIWantMessageIds = 9)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when graft count exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .setControl(
            Rpc.ControlMessage.newBuilder()
                .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("a"))
                .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("b"))
                .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("c"))
        )
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxGraftMessages = 2)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when prune count exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .setControl(
            Rpc.ControlMessage.newBuilder()
                .addPrune(pruneWithPeers(peers = 0))
                .addPrune(pruneWithPeers(peers = 0))
        )
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxPruneMessages = 1)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when peers per prune exceeds limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .setControl(Rpc.ControlMessage.newBuilder().addPrune(pruneWithPeers(peers = 17)))
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxPeersPerPruneMessage = 16)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `rejects when idontwant messageIDs exceed limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .setControl(Rpc.ControlMessage.newBuilder().addIdontwant(idontwant(ids = 5)))
        .build()
    val limits = PubsubRpcLimits.NONE.copy(maxIDontWantMessageIds = 4)
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `accepts well-formed RPC under every configured limit`() {
    val rpc = Rpc.RPC.newBuilder()
        .addSubscriptions(subOpt("t"))
        .addPublish(message(topics = 1))
        .setControl(
            Rpc.ControlMessage.newBuilder()
                .addIhave(ihave(ids = 2))
                .addIwant(iwant(ids = 2))
                .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("t"))
                .addPrune(pruneWithPeers(peers = 1))
                .addIdontwant(idontwant(ids = 1))
        )
        .build()
    val limits = PubsubRpcLimits(
        maxPublishedMessages = 10,
        maxTopicsPerPublishedMessage = 4,
        maxSubscriptions = 10,
        maxIHaveMessageIds = 10,
        maxIWantMessageIds = 10,
        maxGraftMessages = 10,
        maxPruneMessages = 10,
        maxPeersPerPruneMessage = 10,
        maxIDontWantMessages = 10,
        maxIDontWantMessageIds = 10,
        rejectEmptyPublishEntries = true,
    )
    assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
        .isEqualTo(RpcMessageCountValidator.Result.Accepted)
}

@Test
fun `rejects truncated input as malformed`() {
    val rpc = Rpc.RPC.newBuilder().addPublish(message(topics = 1)).build()
    val full = rpc.toByteArray()
    val truncated = full.copyOfRange(0, full.size - 1)
    val result = RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(truncated), unlimited)
    assertThat(result).isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
}

@Test
fun `accepts attack payload-style frame after empty-publish rejection`() {
    // 1000 empty publish entries, would expand to 1000 Rpc.Message objects.
    val attack = ByteArray(2 * 1000) { if (it % 2 == 0) 0x12.toByte() else 0x00.toByte() }
    val result = RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(attack), unlimited)
    assertThat(result).isEqualTo(RpcMessageCountValidator.Result.Rejected("empty publish entry"))
}
```

- [ ] **Step 2: Run the full validator test class**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.RpcMessageCountValidatorTest"`
Expected: all 13 tests pass.

- [ ] **Step 3: Commit**

```bash
git add libp2p/src/test/kotlin/io/libp2p/pubsub/RpcMessageCountValidatorTest.kt
git commit -m "test(pubsub): cover every PubsubRpcLimits field in validator tests"
```

---

## Task 5: `RpcCountFrameDecoder` Netty handler — failing test first

**Files:**
- Create: `libp2p/src/test/kotlin/io/libp2p/pubsub/RpcCountFrameDecoderTest.kt`

- [ ] **Step 1: Write the failing pipeline test**

```kotlin
package io.libp2p.pubsub

import com.google.protobuf.ByteString
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class RpcCountFrameDecoderTest {

    private val limits = PubsubRpcLimits.NONE.copy(
        maxPublishedMessages = 2,
        rejectEmptyPublishEntries = true,
    )

    private fun pipeline() = EmbeddedChannel(
        RpcCountFrameDecoder(limits),
        ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
    )

    @Test
    fun `forwards an accepted RPC unchanged`() {
        val ch = pipeline()
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x")))
            .build()

        ch.writeInbound(Unpooled.wrappedBuffer(rpc.toByteArray()))

        val received: Rpc.RPC? = ch.readInbound()
        assertThat(received).isEqualTo(rpc)
    }

    @Test
    fun `drops an RPC containing an empty publish entry`() {
        val ch = pipeline()
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.getDefaultInstance())
            .build()

        ch.writeInbound(Unpooled.wrappedBuffer(rpc.toByteArray()))

        val received: Any? = ch.readInbound()
        assertThat(received).isNull()
    }

    @Test
    fun `drops an RPC whose publish count exceeds limits`() {
        val ch = pipeline()
        val rpc = Rpc.RPC.newBuilder()
            .apply {
                repeat(3) {
                    addPublish(Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x$it")))
                }
            }
            .build()

        ch.writeInbound(Unpooled.wrappedBuffer(rpc.toByteArray()))

        val received: Any? = ch.readInbound()
        assertThat(received).isNull()
    }
}
```

- [ ] **Step 2: Run test to confirm it fails (class missing)**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.RpcCountFrameDecoderTest"`
Expected: compilation error — `RpcCountFrameDecoder` unresolved.

---

## Task 6: `RpcCountFrameDecoder` implementation

**Files:**
- Create: `libp2p/src/main/kotlin/io/libp2p/pubsub/RpcCountFrameDecoder.kt`

- [ ] **Step 1: Create the handler**

```kotlin
package io.libp2p.pubsub

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import org.slf4j.LoggerFactory

/**
 * Pre-decode count cap for inbound pubsub RPC frames. Sits between
 * [io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder] (byte-size
 * cap) and [io.netty.handler.codec.protobuf.ProtobufDecoder] (materialisation).
 *
 * For each frame, delegates to [RpcMessageCountValidator]. Accepted frames are
 * forwarded unchanged as a `ByteBuf` to the next handler. Rejected frames are
 * dropped with a debug log; no `Rpc$Message` is allocated for them.
 *
 * When [limits] is [PubsubRpcLimits.NONE] the handler is a pass-through.
 */
class RpcCountFrameDecoder(private val limits: PubsubRpcLimits) : ByteToMessageDecoder() {

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (msg.readableBytes() == 0) return
        val readable = msg.readableBytes()

        if (limits === PubsubRpcLimits.NONE) {
            out.add(msg.readRetainedSlice(readable))
            return
        }

        when (val result = RpcMessageCountValidator.validate(msg, limits)) {
            RpcMessageCountValidator.Result.Accepted -> {
                out.add(msg.readRetainedSlice(readable))
            }
            is RpcMessageCountValidator.Result.Rejected -> {
                logger.debug("Dropping pubsub RPC frame: {}", result.reason)
                msg.skipBytes(readable)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RpcCountFrameDecoder::class.java)
    }
}
```

- [ ] **Step 2: Run the previously-failing handler tests**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.RpcCountFrameDecoderTest"`
Expected: all 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add libp2p/src/main/kotlin/io/libp2p/pubsub/RpcCountFrameDecoder.kt \
        libp2p/src/test/kotlin/io/libp2p/pubsub/RpcCountFrameDecoderTest.kt
git commit -m "feat(pubsub): add RpcCountFrameDecoder netty handler"
```

---

## Task 7: Wire `rpcLimits` into `AbstractRouter`

**Files:**
- Modify: `libp2p/src/main/kotlin/io/libp2p/pubsub/AbstractRouter.kt:112-121`

- [ ] **Step 1: Add the open property near `validateMessageListLimits`**

Below the existing `internal open fun validateMessageListLimits(...)` (around line 82), insert:

```kotlin
/**
 * Per-router caps on repeated-field counts inside inbound RPCs. Enforced before
 * protobuf materialisation by an [RpcCountFrameDecoder] inserted into the stream
 * pipeline. Defaults to [PubsubRpcLimits.NONE] (no pre-decode cap). Subclasses
 * with configured limits (e.g. [io.libp2p.pubsub.gossip.GossipRouter]) override.
 */
protected open val rpcLimits: PubsubRpcLimits
    get() = PubsubRpcLimits.NONE
```

- [ ] **Step 2: Insert the handler in `initChannelWithHandler`**

Locate `protected open fun initChannelWithHandler(...)` (line 112) and change the body to:

```kotlin
protected open fun initChannelWithHandler(streamHandler: StreamHandler, handler: ChannelHandler?) {
    with(streamHandler.stream) {
        pushHandler(LimitedProtobufVarint32FrameDecoder(maxMsgSize))
        pushHandler(ProtobufVarint32LengthFieldPrepender())
        pushHandler(RpcCountFrameDecoder(rpcLimits))
        pushHandler(ProtobufDecoder(Rpc.RPC.getDefaultInstance()))
        pushHandler(ProtobufEncoder())
        handler?.also { pushHandler(it) }
        pushHandler(streamHandler)
    }
}
```

- [ ] **Step 3: Build and run pubsub tests**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.*"`
Expected: all existing pubsub tests still pass. The new handler is a pass-through for `FloodsubRouter` (which inherits `PubsubRpcLimits.NONE`).

- [ ] **Step 4: Commit**

```bash
git add libp2p/src/main/kotlin/io/libp2p/pubsub/AbstractRouter.kt
git commit -m "feat(pubsub): wire RpcCountFrameDecoder into AbstractRouter pipeline"
```

---

## Task 8: `GossipRouter` projects `params` into `rpcLimits`

**Files:**
- Modify: `libp2p/src/main/kotlin/io/libp2p/pubsub/gossip/GossipRouter.kt` (near the existing `validateMessageListLimits` override at line 259)

- [ ] **Step 1: Add the override**

Above (or below — colocate with `validateMessageListLimits`) the `validateMessageListLimits` override, add:

```kotlin
import io.libp2p.pubsub.PubsubRpcLimits

// …inside GossipRouter…

override val rpcLimits: PubsubRpcLimits by lazy {
    PubsubRpcLimits(
        maxPublishedMessages = params.maxPublishedMessages,
        maxTopicsPerPublishedMessage = params.maxTopicsPerPublishedMessage,
        maxSubscriptions = params.maxSubscriptions,
        maxIHaveMessageIds = params.maxIHaveLength,
        maxIWantMessageIds = params.maxIWantMessageIds,
        maxGraftMessages = params.maxGraftMessages,
        maxPruneMessages = params.maxPruneMessages,
        maxPeersPerPruneMessage = params.maxPeersAcceptedInPruneMsg,
        maxIDontWantMessageIds = params.maxIDontWantMessageIds,
        rejectEmptyPublishEntries = true,
    )
}
```

(`maxIHaveLength` and `maxPeersAcceptedInPruneMsg` are non-nullable `Int` on `GossipParams`; that's fine — they widen to `Int?`. `params.maxIDontWantMessages` may not exist; if it doesn't, leave `maxIDontWantMessages` defaulted to `null` and only set `maxIDontWantMessageIds`.)

- [ ] **Step 2: Run gossip and pubsub tests**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.gossip.*" --tests "io.libp2p.pubsub.*"`
Expected: all pass.

- [ ] **Step 3: Commit**

```bash
git add libp2p/src/main/kotlin/io/libp2p/pubsub/gossip/GossipRouter.kt
git commit -m "feat(gossip): derive RpcCountFrameDecoder limits from GossipParams"
```

---

## Task 9: End-to-end regression test for the attack frame

**Files:**
- Create: `libp2p/src/test/kotlin/io/libp2p/pubsub/RpcCountFrameDecoderAttackTest.kt`

This exercises the *full* pubsub stream pipeline (`LimitedProtobufVarint32FrameDecoder` + `RpcCountFrameDecoder` + `ProtobufDecoder`) with a length-valid attack payload, and asserts the frame is dropped before any `Rpc.RPC` arrives downstream.

- [ ] **Step 1: Write the test**

```kotlin
package io.libp2p.pubsub

import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class RpcCountFrameDecoderAttackTest {

    private val limits = PubsubRpcLimits.NONE.copy(
        maxPublishedMessages = 1000,
        rejectEmptyPublishEntries = true,
    )

    @Test
    fun `attack frame of empty publish entries is rejected before materialisation`() {
        val maxMsgSize = 12_234_442 // Teku mainnet-preset

        val ch = EmbeddedChannel(
            LimitedProtobufVarint32FrameDecoder(maxMsgSize),
            ProtobufVarint32LengthFieldPrepender(),
            RpcCountFrameDecoder(limits),
            ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
        )

        // 100k empty publish entries: large enough to demonstrate amplification
        // would be catastrophic, small enough to keep the test cheap.
        val entries = 100_000
        val body = ByteArray(entries * 2) { if (it % 2 == 0) 0x12.toByte() else 0x00.toByte() }

        // Write a length-prefixed frame manually: varint(length) || body.
        val framed = ByteBufAllocator.DEFAULT.buffer(body.size + 5)
        writeVarint32(framed, body.size)
        framed.writeBytes(body)

        ch.writeInbound(framed)

        val received: Any? = ch.readInbound()
        assertThat(received).isNull() // ProtobufDecoder never produced an Rpc.RPC
    }

    @Test
    fun `well-formed RPC under the same limits is still delivered`() {
        val maxMsgSize = 12_234_442
        val ch = EmbeddedChannel(
            LimitedProtobufVarint32FrameDecoder(maxMsgSize),
            ProtobufVarint32LengthFieldPrepender(),
            RpcCountFrameDecoder(limits),
            ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
        )

        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.newBuilder().setData(com.google.protobuf.ByteString.copyFromUtf8("ok")))
            .build()
        val body = rpc.toByteArray()

        val framed = ByteBufAllocator.DEFAULT.buffer(body.size + 5)
        writeVarint32(framed, body.size)
        framed.writeBytes(body)

        ch.writeInbound(framed)

        val received: Rpc.RPC? = ch.readInbound()
        assertThat(received).isEqualTo(rpc)
    }

    private fun writeVarint32(buf: io.netty.buffer.ByteBuf, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                buf.writeByte(v)
                return
            }
            buf.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }
}
```

- [ ] **Step 2: Run the attack regression test**

Run: `./gradlew :libp2p:test --tests "io.libp2p.pubsub.RpcCountFrameDecoderAttackTest"`
Expected: both tests pass; the attack frame produces no inbound `Rpc.RPC`.

- [ ] **Step 3: Commit**

```bash
git add libp2p/src/test/kotlin/io/libp2p/pubsub/RpcCountFrameDecoderAttackTest.kt
git commit -m "test(pubsub): regression vector for gossipsub publish-frame OOM"
```

---

## Task 10: Format and full verification

- [ ] **Step 1: Apply Spotless and run Detekt**

Run: `./gradlew spotlessApply detekt`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the full libp2p test suite**

Run: `./gradlew :libp2p:test`
Expected: all tests pass, no regressions.

- [ ] **Step 3: Run the full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit any formatting fixups**

```bash
git status
# if Spotless changed files:
git add -A
git commit -m "style: apply spotless formatting"
```

---

## Self-review notes

- **Spec coverage:** every section of the design doc maps to a task — `PubsubRpcLimits` (Task 1), `RpcMessageCountValidator` (Tasks 2-4), `RpcCountFrameDecoder` (Tasks 5-6), `AbstractRouter` hook (Task 7), `GossipRouter` wiring (Task 8), end-to-end attack regression (Task 9), format/full-suite (Task 10). The deferred "in-flight RPC bound" non-goal stays deferred.
- **Placeholders:** no `TODO`/`TBD`; every code step shows the actual code.
- **Type consistency:** `PubsubRpcLimits` field names, `RpcMessageCountValidator.Result` shape, and `RpcCountFrameDecoder` constructor signature are identical across tasks. Field-number constants are defined once inside the validator. `GossipParams` field names (`maxPublishedMessages`, `maxTopicsPerPublishedMessage`, `maxSubscriptions`, `maxIHaveLength`, `maxIWantMessageIds`, `maxGraftMessages`, `maxPruneMessages`, `maxPeersAcceptedInPruneMsg`, `maxIDontWantMessageIds`) match the grep evidence from `libp2p/src/main/kotlin/io/libp2p/pubsub/gossip/GossipParams.kt:160-248`.
- **Edge note on Task 8:** if any of the cited `GossipParams` fields don't exist or have different names in the working tree, the task instructs to widen / drop the corresponding limits to `null` rather than invent new fields.
