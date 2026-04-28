# Gossipsub Partial Messages — Design Document

Status: **Draft / MVP design**
Tracking issue: [libp2p/jvm-libp2p#435](https://github.com/libp2p/jvm-libp2p/issues/435)
Last updated: see `git log -- docs/partial-messages.md`

This document is the source of truth for the jvm-libp2p implementation of the
gossipsub partial-messages extension. It captures the scope, the jvm-libp2p ↔
client responsibility boundary, the public API, routing semantics, and the
implementation plan. It is a **living document** — append to the decision log
(§9) when we revise anything.

---

## 1. Scope and non-goals

### In scope (MVP)

- Full wire-level support for the `PartialMessagesExtension` RPC:
  - Per-topic negotiation via `SubOpts.requestsPartial` / `SubOpts.supportsSendingPartial`.
  - Inbound and outbound handling of `RPC.partial`.
  - Both metadata-only and payload-only variants in both directions.
- A Kotlin API that lets a client (Teku) plug in its own per-peer state,
  metadata encoding, group-ID generation, part-level validation, and publish
  decisions.
- Integration with the existing gossipsub routing rules:
  - Suppress full-message send to peers that requested partial on that topic.
  - Suppress IDONTWANT to peers we request partial from.
  - Replace IHAVE with an `onEmitGossip` callback for partial-capable peers
    in the lazy-push loop.
- Per-group lifecycle (TTL in heartbeats, DoS caps on peer-initiated groups).
- A side-channel `peerFeedback` API so the client can drive peer scoring
  explicitly instead of via callback return values.

### Non-goals (deferred, but documented for future)

- `interop-test-client` partial-messages support. Deferred; see §7 for notes.
- Partial-specific peer-scoring rules beyond what the Extensions handshake
  already enforces. Spec is silent; match go-libp2p (no scoring) for MVP.
- Topic-level "partial-only" mode. Spec explicitly defers this to a future
  extension.
- Reassembling a full `Message` and re-entering the normal gossip flow. MVP
  delivers parts upward to the application only; the application is free to
  never republish a reconstructed full message (matches Ethereum PeerDAS).
- New wire messages. Spec and go-libp2p use the single
  `PartialMessagesExtension` for both lazy-push and payload delivery — no
  `partialIHAVE` / `partialIWANT`.

---

## 2. Reference pins

The partial-messages spec is **Lifecycle 1A (Working Draft)** and may change.
When revising this document, update these pins.

| Source | Pin | Location |
|---|---|---|
| libp2p/specs | merge commit `6b6203ee` (PR #685, merged 2026-02-26) | `pubsub/gossipsub/partial-messages.md` |
| libp2p/go-libp2p-pubsub | `master` at time of MVP (note in decision log when pinned) | `extensions.go`, `partialmessages/partialmsgs.go`, `gossipsub.go`, `pubsub.go` |
| libp2p/test-plans gossipsub-interop | `master` | `gossipsub-interop/go-libp2p/experiment.go`, `main.go` |
| OffchainLabs/prysm | branch `prysm/partial-cells-current`, latest seen `e8480a86` (2026-03-31) | `beacon-chain/p2p/partialdatacolumnbroadcaster/`, `consensus-types/blocks/partialdatacolumn.go`, `proto/prysm/v1alpha1/partial_data_columns.proto` |

### Related in-flight spec work (watch)

- libp2p/specs#681 — Choke extension.
- libp2p/specs#699 — Topic table.
- libp2p/specs#706 — Gossipsub v1.4.
- libp2p/specs#654 — Message preamble.

None directly modify partial-messages, but v1.4 and message-preamble overlap
in motivation.

---

## 3. Responsibility boundary (jvm-libp2p ↔ client)

The one-line model:

> **jvm-libp2p is a transport + per-peer bookkeeper for opaque partial-message
> RPCs. The client (Teku) owns everything about what those bytes mean, when a
> group is "complete", and who gets what.**

| Concern | jvm-libp2p | Client (Teku) |
|---|---|---|
| v1.3 Control Extensions handshake | ✅ (done on this branch) | — |
| `SubOpts.requestsPartial` / `supportsSendingPartial` wire handling | ✅ | — |
| Per-peer partial-capability state (node-level and topic-level) | ✅ | — |
| Per-`(topic, groupID)` state container, TTL GC, DoS caps | ✅ | — |
| Routing: suppress full-msg send to partial-requesting peers | ✅ | — |
| Routing: suppress IDONTWANT to peers we request partial from | ✅ | — |
| Routing: replace IHAVE with `onEmitGossip` for partial peers | ✅ | — |
| Wire framing of `PartialMessagesExtension` in/out | ✅ | — |
| Spec MUST: omit `partialMessage` if peer supports-but-didn't-request | ✅ | — |
| `partsMetadata` encoding (bitmap / Bloom / whatever) | ❌ opaque | ✅ |
| `groupID` generation | ❌ opaque | ✅ |
| Merging incoming `partsMetadata` into local per-peer view | ❌ | ✅ |
| Deciding which parts to send to which peer | ❌ | ✅ (`PublishActionsFn`) |
| Reassembling a full message | ❌ never | ✅ |
| Validating individual parts (e.g. KZG) | ❌ | ✅ (inside `onIncomingRpc`) |
| Detecting "group complete" and delivering upward | ❌ | ✅ |
| Per-part peer scoring (spammy parts, etc.) | ❌ MVP | Future, in coordination |

Rationale for each line is grounded in go-libp2p's and Prysm's current
behaviour — see §9 and the research notes that produced this document.

---

## 4. Public API (jvm-libp2p surface)

### 4.1 Builder wiring

```kotlin
GossipRouterBuilder().apply {
    enabledGossipExtensions(GossipExtension.PARTIAL_MESSAGES)
    partialMessagesHandler = MyTekuPartialMessagesHandler()  // new
}
```

- The `GossipExtension.PARTIAL_MESSAGES` feature flag stays as the capability
  switch (already wired).
- `partialMessagesHandler: PartialMessagesHandler<*>?` is a new optional
  field on the builder. Null + flag enabled = build-time error.

### 4.2 Client-supplied handler

```kotlin
interface PartialMessagesHandler<PeerState> {

    /**
     * Called on every inbound PartialMessagesExtension RPC on the pubsub
     * event thread. MUST be fast and non-blocking: dispatch heavy work
     * (decoding, validation) to your own executor.
     *
     * Any of rpc.partialMessage and rpc.partsMetadata may be absent; all
     * four combinations are valid.
     */
    fun onIncomingRpc(
        from: PeerId,
        peerStates: Map<PeerId, PeerState>,
        rpc: Rpc.PartialMessagesExtension
    )

    /**
     * Called once per group during the gossipsub heartbeat, for gossip
     * targets that are partial-capable. The client typically responds by
     * calling publishPartial(...) for the same (topic, groupId).
     */
    fun onEmitGossip(
        topic: Topic,
        groupId: ByteArray,
        gossipPeers: Collection<PeerId>,
        peerStates: Map<PeerId, PeerState>
    )
}
```

Notes:
- `PeerState` is fully generic. The library stores it per
  `(topic, groupId, peerId)` and never interprets it.
- Both callbacks run on the pubsub event thread. Document prominently.

### 4.3 Publishing

```kotlin
fun interface PublishActionsFn<PeerState> {
    fun decide(
        peerStates: Map<PeerId, PeerState>,
        peerRequestsPartial: (PeerId) -> Boolean
    ): Sequence<Pair<PeerId, PublishAction<PeerState>>>
}

data class PublishAction<PeerState>(
    val partialMessage: ByteArray? = null,
    val partsMetadata: ByteArray? = null,
    val nextPeerState: PeerState? = null,   // library applies atomically
    val error: Throwable? = null
)

// Entry point on the Gossip facade
fun Gossip.publishPartial(
    topic: Topic,
    groupId: ByteArray,
    actions: PublishActionsFn<*>
): CompletableFuture<Unit>
```

Key API differences vs. go-libp2p (deliberate):

1. **No in-place map mutation.** `PublishAction.nextPeerState` is applied
   atomically by the library per peer, instead of asking the client to
   mutate `Map<PeerId, PeerState>` inside the iterator. Prysm has fixed race
   bugs in the in-place pattern (see commits on `prysm/partial-cells-current`,
   Mar 31 2026); Kotlin's single-threaded event loop makes the atomic-return
   shape natural.
2. **`Unit`-returning callbacks.** Errors do not drive scoring; see §4.4.

### 4.4 Peer feedback (scoring side-channel)

```kotlin
interface PartialMessagesPeerFeedback {
    fun reportFeedback(topic: Topic, peer: PeerId, kind: FeedbackKind)
}

enum class FeedbackKind { USEFUL, INVALID, IGNORED }
```

The handler receives a `PartialMessagesPeerFeedback` instance (via
constructor or context object — TBD during implementation) and uses it to
drive peer score adjustments. This mirrors Prysm's `peerFeedback` pattern.
`INVALID` hooks into the existing `notifyRouterMisbehavior` path.

### 4.5 Topic options

Subscribing to a topic with partial-message flags:

```kotlin
gossip.subscribe(topic, handler,
    requestsPartial = true,
    supportsSendingPartial = true)   // implied if requestsPartial = true
```

Go-libp2p exposes `RequestPartialMessages()` and `SupportsPartialMessages()`
as separate topic options. In Prysm's real integration, only
`RequestPartialMessages()` is ever used; the "supports-but-doesn't-request"
half is currently unexercised. MVP supports both flags in the API but only
the `requests` path needs end-to-end testing.

---

## 5. Routing rules (inside `GossipRouter`)

Three modifications to the existing routing, all behind
`partialMessagesEnabled()` and the per-peer handshake state.

### 5.1 Full-message suppression

When broadcasting a `Message` for topic `T` to peer `P`:
- If `gossipExtensionsState.peerSupportsPartialMessages(P)` **and**
  `partialTopicState.peerRequestsPartial(P, T)` → **do not** send the full
  message to `P`. The client is responsible for pushing parts via
  `publishPartial(...)`.
- This filter applies in `broadcastInbound` and `broadcastOutbound`, before
  messages are queued into `GossipRpcPartsQueue`.
- Spec MUST (§Wire rules): if peer supports sending partial but did *not*
  request, we still send the full message, but when we send a
  `PartialMessagesExtension` to that peer we MUST omit `partialMessage`.

### 5.2 IDONTWANT suppression

When emitting IDONTWANT for a message on topic `T`:
- If, for peer `P`, we `iRequestPartial(T)` **and**
  `peerSupportsSendingPartial(P, T)` → skip IDONTWANT to `P`.
- go-libp2p: `gossipsub.go:892-904`.

### 5.3 IHAVE replacement with `onEmitGossip`

During gossipsub heartbeat lazy-push:
- Partition the selected IHAVE targets into `fullPeers` and
  `partialPeers = { p | iSupportSendingPartial(T) ∧ peerRequestsPartial(p, T) }`.
- Do not enqueue IHAVE for `partialPeers`.
- After the normal loop, for every locally-initiated group under `T`, call
  `handler.onEmitGossip(T, groupId, partialPeers, peerStatesForGroup)` once.
- go-libp2p: `gossipsub.go:2018-2074`.

---

## 6. State and lifecycle

### 6.1 Per-topic-per-peer partial-capability state

Per-peer flags per topic, updated from every inbound `SubOpts` (where
`subscribe = true`):

- `requestsPartial: Boolean`
- `supportsSendingPartial: Boolean`

Spec + go-libp2p coercion: on receive, store
`supportsSendingPartial := requestsPartial || supportsSendingPartial`.

MUST ignore both flags on `SubOpts` with `subscribe = false`.

### 6.2 Per-`(topic, groupID)` group state

```
GroupState {
    ttlInHeartbeats: Int     // counts down each heartbeat, GC at 0
    peerInitiated: Boolean   // true if first seen from a peer, not us
    peerStates: Map<PeerId, PeerState>   // app-opaque
}
```

- Stored in a plain `HashMap` — not thread-safe; access is serialised on the
  pubsub event loop (per the project-wide invariant; do **not** use
  `ConcurrentHashMap`).
- TTL reset whenever `publishPartial(topic, groupId, …)` is called for the
  group.
- GC on `ttl == 0` **or** `peerStates` empty.

### 6.3 DoS caps (match go-libp2p defaults)

Applies only to **peer-initiated** groups (first touched from an inbound
RPC, not via `publishPartial`).

| Cap | Default | Where |
|---|---|---|
| `peerInitiatedGroupLimitPerTopic` | 255 | Across all peers, per topic |
| `peerInitiatedGroupLimitPerTopicPerPeer` | 8 | Per (topic, peer) |

Over-cap: log and drop the RPC. No disconnect. No score penalty (match go;
revise if spec adds guidance).

### 6.4 Cleanup hooks

- Peer disconnect → remove all `peerStates[peer]` entries across groups.
- Unsubscribe (we leave a topic) → drop all group state for that topic.
- Heartbeat → decrement TTLs, GC expired groups.

---

## 7. Known gaps vs. full spec

Explicitly deferred in MVP; listed here so future work can pick them up.

1. **Validator pipeline for partial RPCs** — bypassed entirely (matches
   go-libp2p). Client validates inside `onIncomingRpc`.
2. **Scoring rules for partial misbehaviour** — spec silent, go silent. MVP
   only scores via the existing `notifyRouterMisbehavior` path plus the
   client's `peerFeedback` calls.
3. **Message-ID of reassembled full messages** — spec silent. MVP does not
   reassemble at all; the reconstructed message never re-enters gossip.
4. **Topic-level "partial-only" mode** — spec explicitly defers; no
   implementation.
5. **`SupportsPartialMessages()`-only (support without request) path** —
   supported by the API, but Prysm doesn't exercise it and we don't have an
   end-to-end test for it. Flag if we ship without coverage.
6. **Fanout peers in publish** — MVP does mesh peers (+ fanout fallback if
   mesh empty), mirroring go-libp2p's `MeshPeers`. Fanout specifically for
   partial is not independently exercised.
7. **`interop-test-client`** — deferred. Future work should:
   - Implement `PartialMessagesHandler<TrivialBitmapState>` with SSZ-like
     bitlists for `partsMetadata`.
   - Test the 4-combo matrix (payload+meta / meta-only / payload-only /
     neither) on both send and receive.
   - Test mixed-peer topic: one partial-enabled node, one full-only; verify
     full-only path still works end-to-end.
   - Test `ControlExtensions` handshake ordering: extension RPCs arriving
     before the handshake completes must be ignored.

---

## 8. Implementation plan

Order chosen so an end-to-end partial round-trip works before any of the
fragile routing rules are touched. Each step is independently testable and
mergeable.

Mirror this checklist in issue #435.

- [ ] **Step 1** — Per-topic `SubOpts` flag plumbing. Outbound: flags added
      to subscribe announce RPCs. Inbound: parse flags into a
      `PartialTopicState` (`Map<Topic, Map<PeerId, PartialSubFlags>>`).
      Coercion rule applied on receive. Flags ignored on `subscribe=false`.
- [ ] **Step 2** — `PartialMessagesHandler<PeerState>` interface,
      `PublishAction<PeerState>` (with `nextPeerState`),
      `PublishActionsFn<PeerState>`, `PartialMessagesPeerFeedback`, and
      `GroupState` container with TTL + DoS caps. No routing yet.
- [x] **Step 3** — Inbound `RPC.partial` dispatch: replace the stub at
      `GossipRouter.kt:476` with the full flow (validate caps, create/update
      group state, call `onIncomingRpc`).
- [x] **Step 4** — Outbound `publishPartial(...)` on the `Gossip` facade;
      route through `GossipRpcPartsQueue` (do **not** bypass — PR #433 got
      this wrong). Enforce the "omit `partialMessage` when peer supports but
      didn't request" MUST.
- [x] **Step 5** — End-to-end integration test with a trivial bitmap-based
      handler. Exercises Steps 1-4 before any routing changes.
- [x] **Step 6** — Routing: full-message suppression (§5.1).
- [ ] **Step 7** — Routing: IDONTWANT suppression (§5.2).
- [ ] **Step 8** — Heartbeat tick + TTL GC + cleanup hooks (§6.4).
- [ ] **Step 9** — Routing: IHAVE replacement with `onEmitGossip` (§5.3).
- [x] **Step 10** — Simulator scenario + mixed-peer interop test (partial +
      non-partial nodes on the same topic).

---

## 9. Decision log

Append entries here when design choices change. Keep most-recent on top.

### 2026-04-20 — Initial design

- Scope, boundary, and API agreed per research summarised in this document.
- `PublishAction` returns `nextPeerState` rather than asking the client to
  mutate a shared map in place. Motivation: cleaner Kotlin ergonomics,
  avoids the category of race that Prysm's
  `prysm/partial-cells-current` fixed on 2026-03-31.
- Peer scoring feedback lives on a side-channel
  `PartialMessagesPeerFeedback`, not on callback return values. Matches
  Prysm's `peerFeedback` pattern.
- MVP does not ship `interop-test-client` support; see §7.7 for the future
  checklist.
- DoS caps pinned to go-libp2p defaults (255 / 8).
- Spec pinned to libp2p/specs#685 merge `6b6203ee`. Spec is lifecycle 1A;
  revise this document when spec revisions land.

### Open questions to resolve during implementation

- Exact wiring of `PartialMessagesPeerFeedback` — constructor arg on the
  handler, or a context object passed to each callback? Decide during
  Step 2.
- Whether `publishPartial` on the `Gossip` facade takes a single
  `(topic, groupId)` or supports batched `Seq<(topic, groupId)>`. Prysm
  calls per-topic and iterates; MVP will match.
- Exact return type of `publishPartial` — `CompletableFuture<Unit>` follows
  jvm-libp2p convention; finalise during Step 4.

---

## 10. References

### Spec

- [libp2p/specs — Gossipsub Partial Messages spec (PR #685)](https://github.com/libp2p/specs/pull/685)
- [libp2p/specs — partial-messages.md @ 6b6203ee](https://github.com/libp2p/specs/blob/6b6203ee16ef2e01e6b86fc8f6c3fae0d1c6490e/pubsub/gossipsub/partial-messages.md)

### Related in-flight spec work

- [libp2p/specs#681 — Choke extension](https://github.com/libp2p/specs/pull/681)
- [libp2p/specs#699 — Topic table](https://github.com/libp2p/specs/pull/699)
- [libp2p/specs#706 — Gossipsub v1.4](https://github.com/libp2p/specs/pull/706)
- [libp2p/specs#654 — Message preamble](https://github.com/libp2p/specs/pull/654)

### Implementations

- [go-libp2p-pubsub — extensions.go](https://github.com/libp2p/go-libp2p-pubsub/blob/master/extensions.go)
- [go-libp2p-pubsub — partialmessages/partialmsgs.go](https://github.com/libp2p/go-libp2p-pubsub/blob/master/partialmessages/partialmsgs.go)
- [go-libp2p-pubsub — gossipsub.go](https://github.com/libp2p/go-libp2p-pubsub/blob/master/gossipsub.go)
- [go-libp2p-pubsub — pubsub.go](https://github.com/libp2p/go-libp2p-pubsub/blob/master/pubsub.go)
- [OffchainLabs/prysm — branch `prysm/partial-cells-current`](https://github.com/OffchainLabs/prysm/tree/prysm/partial-cells-current)
  - [`beacon-chain/p2p/partialdatacolumnbroadcaster/`](https://github.com/OffchainLabs/prysm/tree/prysm/partial-cells-current/beacon-chain/p2p/partialdatacolumnbroadcaster)
  - [`consensus-types/blocks/partialdatacolumn.go`](https://github.com/OffchainLabs/prysm/blob/prysm/partial-cells-current/consensus-types/blocks/partialdatacolumn.go)
  - [`proto/prysm/v1alpha1/partial_data_columns.proto`](https://github.com/OffchainLabs/prysm/blob/prysm/partial-cells-current/proto/prysm/v1alpha1/partial_data_columns.proto)

### Interop testing

- [libp2p/test-plans — gossipsub-interop experiment.go](https://github.com/libp2p/test-plans/blob/master/gossipsub-interop/go-libp2p/experiment.go)
- [libp2p/test-plans — gossipsub-interop main.go](https://github.com/libp2p/test-plans/blob/master/gossipsub-interop/go-libp2p/main.go)

### Tracking

- [libp2p/jvm-libp2p#435 — Partial messages tracking issue](https://github.com/libp2p/jvm-libp2p/issues/435)
