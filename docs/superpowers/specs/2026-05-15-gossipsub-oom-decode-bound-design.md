# Bound repeated-field counts in the pubsub RPC decode path

- **Status:** Draft
- **Date:** 2026-05-15
- **Branch:** `feature/fix-oom-issue`
- **Related:** [ethereum-bounty/teku#7](https://github.com/ethereum-bounty/teku/issues/7), [ethereum-bounty/teku#6](https://github.com/ethereum-bounty/teku/pull/6)

## Problem

An unauthenticated remote peer can crash a default-configuration Teku beacon node by
sending a single length-valid `libp2p-gossipsub` RPC frame whose body is millions of
empty repeated `publish` entries (`0x12 0x00`, two bytes each). For Teku on a
mainnet-preset network the frame size cap is
`maxCompressedLength(MAX_PAYLOAD_SIZE) + 1024 = 12,234,442` bytes, so a 12 MB frame is
**length-valid** and admitted by jvm-libp2p's `LimitedProtobufVarint32FrameDecoder`.
Netty's `ProtobufDecoder` then materialises the entire `Rpc.RPC` object graph — about
6.1 million `pubsub.pb.Rpc$Message` objects plus their transient builders, roughly
**730 MB of live heap per frame** — *before* `GossipRouter.validateMessageListLimits`
(the only count cap, at `GossipRouter.kt:259`) is ever consulted. A handful of
synchronised connections exhausts the heap; Teku's default uncaught-exception handler
treats `OutOfMemoryError` as fatal and `System.exit(1)`s, producing a deterministic
remote crash loop on default-configuration nodes.

The byte-size cap is enforced; the repeated-field count cap is structurally too late.
The fix must bound the repeated-field cardinality **before** the `Rpc.RPC` object
graph is constructed.

## Goals

- Reject RPC frames whose repeated-field counts exceed configured limits before any
  `Rpc$Message` / `Rpc$Message$Builder` is allocated.
- Reject zero-length `publish` entries, which are never valid application messages
  and constitute the cheapest amplification gadget.
- Keep `GossipRouter.validateMessageListLimits` in place as defence-in-depth.
- Apply the protection generically across all pubsub routers (Gossip, Floodsub, any
  future `AbstractRouter` subclass), driven by per-router-supplied limits.
- Preserve current behaviour for legitimate traffic: no parameter changes required by
  consumers; existing tests continue to pass.

## Non-goals

- Bounding the number of in-flight inbound `Rpc.RPC` objects per connection (the
  secondary defence-in-depth item in the issue). Deferred to a follow-up because it
  changes the per-connection executor semantics in `StreamHandler.channelRead`.
- Revisiting default values of `GossipParams.maxPublishedMessages` and the byte-size
  cap. Teku already sets sensible per-deployment values; library defaults are out of
  scope for this fix.
- Adding peer-score penalties for oversized RPCs. The existing score machinery already
  reacts to misbehaviour at the behaviour layer; the goal here is allocation
  prevention, not punishment.

## Architecture

A new Netty `ByteToMessageDecoder` — `RpcCountFrameDecoder` — is inserted between
`LimitedProtobufVarint32FrameDecoder` and `ProtobufDecoder` in the pubsub stream
pipeline (`AbstractRouter.initChannelWithHandler`, `AbstractRouter.kt:112-121`). It
consumes the already-length-bounded `ByteBuf` slice that `ProtobufDecoder` would
otherwise parse, walks it once with `com.google.protobuf.CodedInputStream` (zero
allocation of `Rpc$Message` / builders), counts occurrences of the same fields that
`GossipRouter.validateMessageListLimits` already checks, and either forwards the
unchanged `ByteBuf` downstream or drops it with a debug log.

```
LimitedProtobufVarint32FrameDecoder(maxMsgSize)   byte-size cap (existing)
        │
        ▼  ByteBuf  (length-valid frame)
RpcCountFrameDecoder(rpcLimits)                   tag-count cap (NEW)
        │
        ▼  ByteBuf  (count-valid frame; dropped frames never get here)
ProtobufDecoder(Rpc.RPC.getDefaultInstance())     materialises Rpc.RPC
        │
        ▼
StreamHandler → AbstractRouter.onInbound
        │
        ▼
GossipRouter.validateMessageListLimits            defence-in-depth (existing)
```

`ProtobufDecoder` is unchanged; for accepted frames the pipeline behaves exactly as
today. For rejected frames, no `Rpc$Message` allocation occurs.

## Components

### `PubsubRpcLimits` — new immutable data class

Colocated with `AbstractRouter` (`io.libp2p.pubsub` package). Captures every count
that `GossipRouter.validateMessageListLimits` currently checks, plus the
empty-publish-entry switch.

```kotlin
data class PubsubRpcLimits(
    val maxPublishedMessages: Int?,
    val maxTopicsPerPublishedMessage: Int?,
    val maxSubscriptions: Int?,
    val maxIHaveMessageIds: Int?,        // sum across all ihave entries
    val maxIWantMessageIds: Int?,        // sum across all iwant entries
    val maxGraftMessages: Int?,
    val maxPruneMessages: Int?,
    val maxPeersPerPruneMessage: Int?,
    val maxIDontWantMessages: Int? = null,
    val maxIDontWantMessageIds: Int? = null,
    val rejectEmptyPublishEntries: Boolean = true,
) {
    companion object {
        val NONE = PubsubRpcLimits(
            null, null, null, null, null, null, null, null,
            rejectEmptyPublishEntries = false,
        )
    }
}
```

A `null` field means "no limit" — same semantics as the corresponding `GossipParams`
nullable fields. `NONE` is a sentinel used by the default `AbstractRouter`
implementation to disable pre-scanning entirely.

### `AbstractRouter` — generic hook

Add an open property:

```kotlin
protected open val rpcLimits: PubsubRpcLimits
    get() = PubsubRpcLimits.NONE
```

`initChannelWithHandler` (`AbstractRouter.kt:112`) inserts the new handler after the
varint frame decoder and before `ProtobufDecoder`:

```kotlin
pushHandler(LimitedProtobufVarint32FrameDecoder(maxMsgSize))
pushHandler(ProtobufVarint32LengthFieldPrepender())
pushHandler(RpcCountFrameDecoder(rpcLimits))         // NEW
pushHandler(ProtobufDecoder(Rpc.RPC.getDefaultInstance()))
```

`FloodsubRouter` inherits the `NONE` default and is unaffected unless a deployment
chooses to set limits.

### `GossipRouter` — wire `params` into `rpcLimits`

Override `rpcLimits` to project from `GossipParams`, reusing the exact fields that
`validateMessageListLimits` reads today:

```kotlin
override val rpcLimits: PubsubRpcLimits by lazy {
    PubsubRpcLimits(
        maxPublishedMessages         = params.maxPublishedMessages,
        maxTopicsPerPublishedMessage = params.maxTopicsPerPublishedMessage,
        maxSubscriptions             = params.maxSubscriptions,
        maxIHaveMessageIds           = params.maxIHaveLength,
        maxIWantMessageIds           = params.maxIWantMessageIds,
        maxGraftMessages             = params.maxGraftMessages,
        maxPruneMessages             = params.maxPruneMessages,
        maxPeersPerPruneMessage      = params.maxPeersAcceptedInPruneMsg,
        rejectEmptyPublishEntries    = true,
    )
}
```

`validateMessageListLimits` stays unchanged and continues to run on the materialised
`Rpc.RPC` as defence-in-depth.

### `RpcCountFrameDecoder` — Netty integration

Thin `ByteToMessageDecoder` that delegates to a pure validator and forwards or drops
the buffer:

```kotlin
class RpcCountFrameDecoder(private val limits: PubsubRpcLimits) : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (limits === PubsubRpcLimits.NONE) {
            out.add(msg.retainedSlice(msg.readerIndex(), msg.readableBytes()))
            msg.skipBytes(msg.readableBytes())
            return
        }
        when (val result = RpcMessageCountValidator.validate(msg, limits)) {
            RpcMessageCountValidator.Result.Accepted -> {
                out.add(msg.retainedSlice(msg.readerIndex(), msg.readableBytes()))
                msg.skipBytes(msg.readableBytes())
            }
            is RpcMessageCountValidator.Result.Rejected -> {
                logger.debug("Dropping RPC frame: {}", result.reason)
                msg.skipBytes(msg.readableBytes())
            }
        }
    }
}
```

Exact buffer-handling details (slice vs retain semantics) follow Netty's
`ByteToMessageDecoder` conventions and will be confirmed during implementation against
the surrounding handlers — the design point is "forward unchanged on accept, drop on
reject".

### `RpcMessageCountValidator` — pure validator

A standalone object/class with no Netty dependency, fully unit-testable against raw
byte arrays:

```kotlin
object RpcMessageCountValidator {
    sealed interface Result {
        object Accepted : Result
        data class Rejected(val reason: String) : Result
    }

    fun validate(buf: ByteBuf, limits: PubsubRpcLimits): Result { /* … */ }
}
```

The walker uses `CodedInputStream.newInstance(buf.nioBuffer())` to read tags, lengths,
and skip bodies without allocating any generated message class. For the top-level
`Rpc.RPC` message:

- field 1 (`subscriptions`): increment subscription counter; `skipField`.
- field 2 (`publish`): read body length. If length is 0 and
  `rejectEmptyPublishEntries`, reject. Otherwise increment publish counter, and — if
  `maxTopicsPerPublishedMessage` is set — push limit, scan the publish sub-message for
  field 2 (`topicIDs`, repeated string) and count per-entry. Pop limit.
- field 3 (`control`): push limit; recurse to count `ihave`/`iwant`/`graft`/`prune`/
  `idontwant`, and within each `ihave`/`iwant` sum `messageIDs`, within each `prune`
  count `peers`. Pop limit.
- any other tag: `skipField`.

Counters are checked against limits after each increment and the validator short-
circuits with a structured `Rejected` reason on the first breach. Field numbers come
from `libp2p/src/main/proto/pubsub/rpc.proto` and will be referenced as named
constants in the validator.

Per-publish topic-id counts and per-prune peer counts are enforced in the same pass to
keep `validateMessageListLimits` and the pre-scan in sync.

## Data flow on the attack frame

A 12,234,442-byte frame containing 6,117,221 `0x12 0x00` publish entries:

1. `LimitedProtobufVarint32FrameDecoder` admits the frame (within `maxMsgSize`).
2. `RpcCountFrameDecoder.decode` invokes `RpcMessageCountValidator.validate`.
3. The validator reads the first `publish` tag, reads body length = 0, sees
   `rejectEmptyPublishEntries = true`, returns `Rejected("empty publish entry")`.
4. The handler logs a debug line and discards the buffer; `ProtobufDecoder` is never
   invoked.
5. No `Rpc$Message` or `Rpc$Message$Builder` is allocated. The only memory associated
   with the frame is the inbound `ByteBuf` (≈12 MB), released on normal Netty buffer
   lifecycle.

Even with `rejectEmptyPublishEntries` disabled, the validator short-circuits after
`maxPublishedMessages` (1000 for Teku) publish-tag reads — i.e. after ≈2 kB of body
walked — and rejects with `Rejected("publish count > maxPublishedMessages")`.

## Error handling

- Malformed protobuf during the pre-scan (truncated varint, negative length, etc.):
  the validator catches `IOException` / `InvalidProtocolBufferException` and returns
  `Rejected("malformed")`. `ProtobufDecoder` would have thrown for the same input;
  silently dropping here is symmetric with the existing post-decode `return` in
  `AbstractRouter.onInbound` (`AbstractRouter.kt:169-172`).
- Any other exception during validation: caught at the handler boundary; the buffer
  is dropped and a warning logged. Fail-closed by design.
- `validateMessageListLimits` remains in place and continues to drop frames that pass
  the pre-scan but fail the behaviour-layer check.

## Testing

### Unit tests on `RpcMessageCountValidator` (no Netty needed)

Hand-built byte arrays exercising each limit independently:

- `N + 1` `publish` entries → rejected with the publish reason.
- `N + 1` `subscriptions` entries → rejected.
- `N + 1` `graft` / `prune` / `idontwant` entries → rejected.
- `ihave` entries whose total `messageIDs` count exceeds `maxIHaveMessageIds` →
  rejected.
- `iwant` entries whose total `messageIDs` count exceeds `maxIWantMessageIds` →
  rejected.
- A single `publish` with more than `maxTopicsPerPublishedMessage` `topicIDs` →
  rejected.
- A single `prune` with more than `maxPeersPerPruneMessage` `peers` → rejected.
- One empty `publish` entry (`0x12 0x00`) with `rejectEmptyPublishEntries = true` →
  rejected.
- One empty `publish` entry with `rejectEmptyPublishEntries = false` → accepted.
- A well-formed RPC under every configured limit (subscriptions + publishes +
  control with all sub-fields) → accepted.
- Malformed input: truncated varint, length larger than remaining bytes, unknown
  top-level tag (forward-compatible — should be skipped, not rejected). Truncated
  inputs return `Rejected("malformed")`; unknown tags are ignored.

### Integration tests in the pipeline

An `EmbeddedChannel` containing `LimitedProtobufVarint32FrameDecoder`,
`RpcCountFrameDecoder`, `ProtobufDecoder`, and a downstream recorder handler:

- Attack frame (full 12 MB PoC vector) → recorder receives nothing; `ProtobufDecoder`
  is provably not invoked.
- Well-formed RPC under limits → recorder receives the same `Rpc.RPC` as today
  (regression check against the no-pre-scan baseline).

### Regression vector

A test resource containing a minimised version of the PoC frame (or a generator that
produces it deterministically) confirming rejection in bounded time and bounded
allocation. Allocation can be measured by a counting `Rpc$Message` factory in a test
double, or asserted by snapshotting heap state before/after the decode.

### Existing suite

All of `GossipRouterTest`, `PubsubRouterTest`, `GossipRpcPartsQueueTest`, and the
flood / gossip integration tests must pass with no parameter changes — `GossipParams`
defaults are unchanged; `rpcLimits` is derived, not configured.

## Migration / compatibility

- `FloodsubRouter` and any third-party `AbstractRouter` subclass inherit
  `PubsubRpcLimits.NONE` and behave exactly as before. The pre-scan handler is a
  pass-through for them.
- `GossipRouter` consumers (Teku, Nabu, Peergos) get the protection automatically
  because the limits are derived from the `GossipParams` they already configure.
- No public API breaks. `PubsubRpcLimits`, `RpcCountFrameDecoder`, and
  `RpcMessageCountValidator` are new additions; `validateMessageListLimits` is
  unchanged.

## Open questions

None blocking. Items deliberately deferred:

- In-flight RPC bound per connection (separate change).
- Default-limit tightening across `GossipParams` (deployment-level decision).
