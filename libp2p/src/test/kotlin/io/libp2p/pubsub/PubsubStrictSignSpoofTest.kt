package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the strict-sign origin spoofing vulnerability.
 *
 * Pre-fix behaviour:
 *   - `pubsubValidate` only verified that `msg.signature` verified under `msg.key`,
 *     never that `PeerId.fromPubKey(msg.key) == msg.from`. A peer with any valid key
 *     could therefore publish a signed message whose `from` was set to a different peer.
 *   - `PublisherImpl.publishExt` accepted any caller-supplied `from` and signed it.
 *   - `AbstractRouter` inserted messages into `seenMessages` BEFORE validation, so a
 *     forged message that ultimately failed validation still occupied the
 *     `from || seqno` slot and suppressed a legitimate follow-up message from the
 *     impersonated peer.
 *
 * Post-fix behaviour exercised here:
 *   - `pubsubValidate` rejects messages whose `from` does not match
 *     `PeerId.fromPubKey(msg.key)`, and rejects missing/empty `from`/`key`/`signature`.
 *   - `PublisherImpl.publishExt` throws `IllegalArgumentException` when the caller
 *     supplies a `from` that doesn't match the signing key.
 *   - The seen-cache no longer retains an entry for a message that failed validation,
 *     so a legitimate follow-up with the same `from || seqno` is delivered.
 */
class PubsubStrictSignSpoofTest {

    /**
     * Test-only router that uses [SIGNATURE_ROUTER_VALIDATOR] so we can drive the
     * receive path through strict-sign validation while keeping the deterministic
     * test harness from [DeterministicFuzz].
     */
    private class StrictSignFloodRouter(executor: ScheduledExecutorService) : AbstractRouter(
        protocol = PubsubProtocol.Floodsub,
        executor = executor,
        subscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),
        maxMsgSize = Int.MAX_VALUE,
        messageFactory = { DefaultPubsubMessage(it) },
        seenMessages = LRUSeenCache(SimpleSeenCache(), 1000),
        messageValidator = SIGNATURE_ROUTER_VALIDATOR
    ) {
        override fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit> {
            val peers = msg.topics.flatMap { getTopicPeers(it) }.distinct()
            peers.forEach { submitPublishMessage(it, msg) }
            flushAllPending()
            return CompletableFuture.completedFuture(null)
        }

        override fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {
            val peers = msgs.flatMap { it.topics }.distinct().flatMap { getTopicPeers(it) }.distinct()
            msgs.forEach { msg ->
                peers.filter { it != receivedFrom }.forEach { submitPublishMessage(it, msg) }
            }
            flushAllPending()
        }

        override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {}
        override fun processExtensions(msg: Rpc.RPC, receivedFrom: PeerHandler) {}
    }

    @Test
    fun `pubsubValidate rejects a forged from that does not match the signing key`() {
        val (attackerPriv, _) = generateKeyPair(KeyType.ED25519)
        val (_, victimPub) = generateKeyPair(KeyType.ED25519)
        val spoofedFrom = PeerId.fromPubKey(victimPub).bytes

        val forged = pubsubSign(
            Rpc.Message.newBuilder()
                .addTopicIDs("topic")
                .setData("payload".toByteArray(StandardCharsets.UTF_8).toProtobuf())
                .setFrom(spoofedFrom.toProtobuf())
                .setSeqno(1L.toBytesBigEndian().toProtobuf())
                .build(),
            attackerPriv
        )

        // The signature itself verifies under msg.key (attacker's key) — that's the trap.
        val signingPeer = PeerId.fromPubKey(unmarshalPublicKey(forged.key.toByteArray()))
        assertThat(signingPeer).isNotEqualTo(PeerId(spoofedFrom))

        // pubsubValidate MUST reject because msg.from doesn't match PeerId(msg.key).
        assertThat(pubsubValidate(forged)).isFalse()
    }

    @Test
    fun `pubsubValidate accepts a properly signed message whose from matches the key`() {
        val (priv, _) = generateKeyPair(KeyType.ED25519)
        val from = PeerId.fromPubKey(priv.publicKey()).bytes

        val msg = pubsubSign(
            Rpc.Message.newBuilder()
                .addTopicIDs("topic")
                .setData("payload".toByteArray(StandardCharsets.UTF_8).toProtobuf())
                .setFrom(from.toProtobuf())
                .setSeqno(1L.toBytesBigEndian().toProtobuf())
                .build(),
            priv
        )

        assertThat(pubsubValidate(msg)).isTrue()
    }

    @Test
    fun `pubsubValidate rejects messages with missing signature or key or from`() {
        val (priv, _) = generateKeyPair(KeyType.ED25519)
        val from = PeerId.fromPubKey(priv.publicKey()).bytes

        val signed = pubsubSign(
            Rpc.Message.newBuilder()
                .addTopicIDs("topic")
                .setData("payload".toByteArray(StandardCharsets.UTF_8).toProtobuf())
                .setFrom(from.toProtobuf())
                .setSeqno(1L.toBytesBigEndian().toProtobuf())
                .build(),
            priv
        )

        assertThat(pubsubValidate(Rpc.Message.newBuilder(signed).clearSignature().build())).isFalse()
        assertThat(pubsubValidate(Rpc.Message.newBuilder(signed).clearKey().build())).isFalse()
        assertThat(pubsubValidate(Rpc.Message.newBuilder(signed).clearFrom().build())).isFalse()
    }

    @Test
    fun `publishExt rejects a caller-supplied from that does not match the signing key`() {
        val fuzz = DeterministicFuzz()
        val router = fuzz.createTestRouter { executor, _, _ -> StrictSignFloodRouter(executor) }
        val api = createPubsubApi(router.router)

        val (publisherPriv, publisherPub) = generateKeyPair(KeyType.ED25519)
        val (_, otherPub) = generateKeyPair(KeyType.ED25519)
        val spoofedFrom = PeerId.fromPubKey(otherPub).bytes

        // Sanity: spoofedFrom and the publisher's own PeerId differ
        assertThat(PeerId(spoofedFrom)).isNotEqualTo(PeerId.fromPubKey(publisherPub))

        val publisher = api.createPublisher(publisherPriv, 1)

        assertThatThrownBy {
            publisher.publishExt(
                "payload".toByteArray(StandardCharsets.UTF_8).toByteBuf(),
                spoofedFrom,
                42L,
                Topic("topic")
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `publishExt accepts a caller-supplied from that matches the signing key`() {
        val fuzz = DeterministicFuzz()
        val router = fuzz.createTestRouter { executor, _, _ -> StrictSignFloodRouter(executor) }
        val api = createPubsubApi(router.router)

        val (publisherPriv, publisherPub) = generateKeyPair(KeyType.ED25519)
        val ownFrom = PeerId.fromPubKey(publisherPub).bytes

        val publisher = api.createPublisher(publisherPriv, 1)
        // Must not throw.
        val fut = publisher.publishExt(
            "payload".toByteArray(StandardCharsets.UTF_8).toByteBuf(),
            ownFrom,
            42L,
            Topic("topic")
        )
        fuzz.timeController.addTime(Duration.ofMillis(100))
        assertThat(fut.isCompletedExceptionally).isFalse()
    }

    /**
     * End-to-end seen-cache poisoning regression test.
     *
     * Two routers connected via the test channel:
     *   - subscriber: uses strict-sign validator; we observe its delivered messages.
     *   - attacker:   the same kind of router. We bypass its publisher API and inject
     *                 a hand-crafted forged signed [Rpc.Message] directly onto the wire
     *                 via [MockRouter.sendToSingle].
     *
     * After the forged message is rejected by the subscriber, we publish a legitimate
     * message from a "victim" identity using the SAME `from || seqno` (same message id
     * under the default scheme) — but via the attacker's connection, so we don't have
     * to wire up three nodes. The test asserts that the legitimate message IS still
     * delivered, proving the seen-cache was not poisoned by the rejected forgery.
     */
    @Test
    fun `forged from-spoof is dropped and legitimate follow-up with same from-seqno is delivered`() {
        val fuzz = DeterministicFuzz()
        val subscriberRouter = fuzz.createTestRouter { executor, _, _ -> StrictSignFloodRouter(executor) }
        val wireRouter = fuzz.createMockRouter() // attacker side: lets us push raw RPCs
        val wireMock = wireRouter.router as MockRouter

        wireRouter.connectSemiDuplex(subscriberRouter)

        val topic = "control-plane"
        val fixedSeqId = 0x1337L

        // TestRouter pre-installs an initHandler that pushes delivered (validated) messages
        // onto `inboundMessages`. We use that as the "what got delivered" oracle.
        val receivedMessages = subscriberRouter.inboundMessages

        subscriberRouter.router.subscribe(topic)
        wireRouter.router.subscribe(topic)

        fuzz.timeController.addTime(Duration.ofSeconds(5))

        // Identities
        val (attackerPriv, attackerPub) = generateKeyPair(KeyType.ED25519)
        val (victimPriv, victimPub) = generateKeyPair(KeyType.ED25519)
        val attackerFrom = PeerId.fromPubKey(attackerPub).bytes
        val victimFrom = PeerId.fromPubKey(victimPub).bytes
        assertThat(attackerFrom).isNotEqualTo(victimFrom)

        // 1) Forged signed RPC: signed by attacker but with victim in `from`.
        val forgedSignedMessage = pubsubSign(
            Rpc.Message.newBuilder()
                .addTopicIDs(topic)
                .setData("attacker-command".toByteArray(StandardCharsets.UTF_8).toProtobuf())
                .setFrom(victimFrom.toProtobuf())
                .setSeqno(fixedSeqId.toBytesBigEndian().toProtobuf())
                .build(),
            attackerPriv
        )
        // Sanity: post-fix pubsubValidate must reject this.
        assertThat(pubsubValidate(forgedSignedMessage)).isFalse()

        val forgedRpc = Rpc.RPC.newBuilder().addPublish(forgedSignedMessage).build()
        val forgedSent = CompletableFuture<Unit>()
        wireMock.runOnEventThread {
            wireMock.sendToSingle(forgedRpc)
            forgedSent.complete(Unit)
        }
        forgedSent.get(5, TimeUnit.SECONDS)
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        // Subscriber must NOT have delivered the forged message.
        assertThat(receivedMessages).isEmpty()

        // 2) Legitimate message from the actual victim with the same from || seqno.
        val legitimateSignedMessage = pubsubSign(
            Rpc.Message.newBuilder()
                .addTopicIDs(topic)
                .setData("real-victim-command".toByteArray(StandardCharsets.UTF_8).toProtobuf())
                .setFrom(victimFrom.toProtobuf())
                .setSeqno(fixedSeqId.toBytesBigEndian().toProtobuf())
                .build(),
            victimPriv
        )
        assertThat(pubsubValidate(legitimateSignedMessage)).isTrue()

        val legitRpc = Rpc.RPC.newBuilder().addPublish(legitimateSignedMessage).build()
        val legitSent = CompletableFuture<Unit>()
        wireMock.runOnEventThread {
            wireMock.sendToSingle(legitRpc)
            legitSent.complete(Unit)
        }
        legitSent.get(5, TimeUnit.SECONDS)
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        // Subscriber MUST deliver the legitimate follow-up — the rejected forgery must
        // not have poisoned the seen-cache.
        assertThat(receivedMessages).hasSize(1)
        val delivered = receivedMessages.first()
        assertThat(delivered.protobufMessage.from.toByteArray()).isEqualTo(victimFrom)
        assertThat(delivered.protobufMessage.data.toByteArray().toString(StandardCharsets.UTF_8))
            .isEqualTo("real-victim-command")
    }
}
