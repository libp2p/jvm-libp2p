# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

jvm-libp2p is a JVM implementation of the [libp2p](https://libp2p.io/) networking stack, written in Kotlin. It provides peer-to-peer networking capabilities including transport protocols (TCP, QUIC, WebSocket), security channels (Noise, TLS), stream multiplexing (Yamux, Mplex), and pub/sub messaging (Gossipsub, Floodsub).

Notable users: Teku (Ethereum Consensus Layer client), Nabu (minimal IPFS), Peergos (peer-to-peer encrypted filesystem).

## Build Commands

```bash
# Build the entire project
./gradlew build

# Run all tests (excludes interop tests tagged with "interop")
./gradlew test

# Run tests for a specific module
./gradlew :libp2p:test

# Run a specific test class
./gradlew :libp2p:test --tests "io.libp2p.pubsub.gossip.GossipRpcPartsQueueTest"

# Run a specific test method
./gradlew :libp2p:test --tests "io.libp2p.pubsub.gossip.GossipRpcPartsQueueTest.mergeMessageParts*"

# Check code formatting
./gradlew spotlessCheck

# Apply code formatting
./gradlew spotlessApply

# Run static analysis (Detekt)
./gradlew detekt

# Generate documentation
./gradlew dokkaHtml
# Output in build/dokka/

# Clean build artifacts
./gradlew clean
```

**Requirements:** JDK 11 or higher

**Module Structure:**
- `:libp2p` - Main library module
- `:tools:simulator` - Gossip network simulator
- `:tools:schedulers` - Test scheduling utilities
- `:examples:chatter`, `:examples:cli-chatter`, `:examples:pinger` - Example applications
- `:interop-test-client` - Interoperability testing client

## Architecture Overview

### Core Abstraction Layers

The library follows a layered architecture with protocol negotiation at each layer:

```
Application Layer
    ↓ (Protocol negotiation via multistream-select)
Stream/Protocol Layer (PingProtocol, ChatProtocol, PubsubRouter)
    ↓ (Stream creation)
Stream Multiplexing Layer (Yamux, Mplex)
    ↓ (Multiplexer negotiation)
Security Layer (Noise, TLS)
    ↓ (Security negotiation)
Transport Layer (TCP, QUIC, WebSocket)
    ↓
Raw Network
```

### Key Interfaces and Their Roles

**`Host`** (`core/Host.kt`):
- Main entry point for all libp2p operations
- Manages identity (`PeerId`, `PrivKey`), network, and protocol handlers
- Created via DSL builder: `host { identity { ... }; transports { ... }; protocols { ... } }`

**`Network`** (`core/Network.kt`):
- Manages transports and active connections
- Handles `listen()` and `dial()` operations
- Reuses connections to the same peer

**`Connection`** and **`Stream`** (both extend `P2PChannel`):
- `Connection`: Secured, multiplexed connection between two peers
- `Stream`: Logical stream over a connection for a specific protocol

**`Transport`** (`transport/Transport.kt`):
- Handles raw connection establishment (TCP, QUIC, WebSocket)
- Each transport parses specific multiaddr formats (e.g., `/ip4/127.0.0.1/tcp/30333`)

**`SecureChannel`** (`security/SecureChannel.kt`):
- Protocol binding for security layer negotiation
- Returns `SecureChannel.Session` with `remoteId`, `remotePubKey`
- Implementations: `NoiseXXSecureChannel` (production), `TlsSecureChannel` (beta)

**`StreamMuxer`** (`mux/StreamMuxer.kt`):
- Protocol binding for multiplexer negotiation
- Returns `StreamMuxer.Session` for creating/receiving streams
- Implementations: `MplexStreamMuxer` (production), `YamuxStreamMuxer` (beta)

### The Connection Upgrade Pipeline

When a raw transport connection is established, it goes through staged upgrades:

```
1. Raw Transport (TCP/QUIC/WS)
   ↓
2. ConnectionBuilder (transport/implementation/ConnectionBuilder.kt)
   ↓
3. Security Negotiation → SecureChannel.Session
   ↓
4. Multiplexer Negotiation → StreamMuxer.Session
   ↓
5. Full Connection Ready → ConnectionOverNetty
```

**Key Class:** `ConnectionUpgrader` (`transport/implementation/ConnectionUpgrader.kt`)
- Orchestrates security and muxer protocol negotiation
- Uses `MultistreamProtocol` for protocol selection
- Supports early muxer negotiation (TLS 1.3 feature)

### Protocol Handler Pattern

Custom protocols implement `ProtocolHandler<TController>`:

```kotlin
// Define protocol binding
StrictProtocolBinding("/ipfs/ping/1.0.0", PingProtocol())

// Implement handler
class PingProtocol : ProtocolHandler<PingController> {
    override fun onStartInitiator(stream: Stream): CompletableFuture<PingController>
    override fun onStartResponder(stream: Stream): CompletableFuture<PingController>
}
```

See `examples/chatter/ChatProtocol.kt` for a complete example.

### Pub/Sub Architecture

The pub/sub system is located in `pubsub/` and follows this structure:

**`AbstractRouter`** (`pubsub/AbstractRouter.kt`):
- Base class providing common pubsub logic
- Manages peer subscriptions via `peersTopics` (multi-bimap)
- Implements message validation, deduplication (via `SeenCache`), and batching
- Uses single-threaded event loop (`P2PService`) for thread-safety

**Message Batching via `RpcPartsQueue`**:
- Per-peer queue that accumulates message parts before transmission
- Pattern: accumulate parts → flush via `takeMerged()` → send merged RPC
- Default implementation merges all parts into single RPC
- Gossip implementation (`GossipRpcPartsQueue`) splits messages to respect per-category limits

**Message Flow:**
```
Outbound: publish() → validateAndBroadcast() → submitPublishMessage(peer)
          → queue.addPublish() → flushPending() → queue.takeMerged() → send()

Inbound:  channelRead() → onInbound() → validate & deduplicate
          → broadcastInbound() → queue.addPublish() → flushPending()
```

**Gossip-Specific:**
- **`GossipRouter`** extends `AbstractRouter` with mesh topology management
- Heartbeat mechanism for GRAFT/PRUNE/IHAVE/IWANT control messages
- Peer scoring for spam resistance
- Control messages batched via `GossipRpcPartsQueue`

**Key Flush Triggers:**
- After processing inbound messages (sync validation complete)
- After async message validation completes
- On peer activation (sends initial subscriptions)
- During Gossip heartbeat (mesh management operations)
- After explicit publish/subscribe API calls

### Multistream Protocol Negotiation

**`MultistreamProtocol`** (`protocol/multistream/MultistreamProtocol.kt`):
- Used at three layers: security negotiation, muxer negotiation, protocol negotiation
- Contains list of `ProtocolBinding`s with protocol names
- Delegates to `Negotiator` (initiator/responder)
- Completes with `ProtocolSelect<T>` containing selected protocol handler

**Pattern:** Any negotiable component extends `ProtocolBinding<T>`:
- Security channels, stream muxers, application protocols all use this pattern

## Development Patterns

### Netty Integration

All protocol logic is implemented as Netty `ChannelHandler`s:
- **`P2PChannelOverNetty`**: Base wrapper for both `Connection` and `Stream`
- **`ConnectionOverNetty`**: Wraps connection-level channel with secure and muxer sessions
- **`StreamOverNetty`**: Wraps stream-level channel with protocol negotiation

### Async Pattern

Extensive use of `CompletableFuture<T>` for async operations:
- Protocol negotiation with timeouts
- Connection establishment across multiple addresses
- Message publishing and validation

### Event Thread Safety

The pub/sub system (and other components) use single-threaded event loops via `P2PService`:
- All operations run on `executor: ScheduledExecutorService`
- Components like `RpcPartsQueue` are explicitly "NOT thread safe" but guaranteed single-threaded access
- Methods: `runOnEventThread {}`, `submitOnEventThread {}`, `submitAsyncOnEventThread {}`

### Testing Patterns

**JUnit 5** with:
- `@Test` for standard tests
- `@ParameterizedTest` with `@MethodSource` for data-driven tests
- AssertJ for fluent assertions (`assertThat(...)`)
- MockK for mocking

**Test Infrastructure:**
- Test fixtures in `src/testFixtures/` for shared test utilities
- Host builder DSL used extensively in tests
- `TestChannel` and `TestLogAppender` utilities

**Example Test Pattern (from GossipRpcPartsQueueTest):**
```kotlin
@ParameterizedTest
@MethodSource("testCases")
fun `test message merging`(params: GossipParams, queue: TestQueue) {
    val monolith = queue.mergedSingle()  // Ground truth
    val split = queue.takeMerged()       // Actual implementation

    // Verify limits respected
    assertThat(split).allMatch { router.validateMessageListLimits(it) }

    // Verify semantic equivalence
    assertThat(split.merge().disperse()).isEqualTo(monolith.disperse())
}
```

### Code Style

- Kotlin 1.6 with JVM target 11
- ktlint formatting (run `./gradlew spotlessApply`)
- Detekt static analysis
- Wildcard imports allowed
- No trailing commas enforced
- All warnings as errors (`allWarningsAsErrors = true`)

## Important Implementation Details

### Protobuf Code Generation

Protobuf definitions in `src/main/proto/` are compiled via `com.google.protobuf` Gradle plugin.
Generated code in `build/generated/source/proto/main/java/`.

To regenerate: `./gradlew :libp2p:clean :libp2p:build`

### Multiaddr Format

Network addresses use multiaddr format:
- Example: `/ip4/127.0.0.1/tcp/30333/p2p/QmYyQSo1c1Ym7orWxLYvCrM2EmxFTANf8wXmmE7DWjhx5N`
- Parsed/managed in `core/multiformats/`
- Each transport validates specific multiaddr components

### PeerId Generation

`PeerId` is derived from peer's public key:
- Multihash of the public key bytes
- 32-50 bytes depending on key type
- Supports RSA, Ed25519, Secp256k1, ECDSA

### Security Handshake Timeout

Default timeout for security handshakes: **5 seconds**
- Applies to Noise and TLS handshakes
- Configurable in protocol implementations

## Common Development Workflows

### Adding a New Protocol

1. Define protocol binding with multistream name (e.g., `/myapp/myprotocol/1.0.0`)
2. Implement `ProtocolHandler<TController>` with initiator/responder logic
3. Register with Host via `protocols { add(...) }` in builder
4. Implement controller interface for protocol operations

See `examples/chatter/` for a complete example.

### Adding a New Transport

1. Extend `Transport` interface
2. Implement `listen()` and `dial()` for raw connection establishment
3. Delegate to `ConnectionUpgrader` for security/muxer negotiation
4. Add multiaddr parsing logic for transport-specific components
5. Register with Host via `transports { add(...) }`

### Debugging Connection Issues

- Use `ConnectionVisitor` and `StreamVisitor` for lifecycle observation
- Enable debug logging for `io.libp2p` package
- Check multiaddr format compatibility between peers
- Verify protocol versions match (especially for security/muxer)

### Working with Pub/Sub

- All pub/sub operations run on event thread (thread-safe by design)
- Message validation happens before broadcasting
- Seen cache prevents duplicate message processing
- Control messages automatically batched for efficiency
- Gossip mesh heartbeat runs every 1 second (default)
