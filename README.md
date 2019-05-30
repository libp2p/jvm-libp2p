# jvm-libp2p-minimal

> a minimal libp2p implementation for the JVM, written in Kotlin 🔥

**⚠️ This is heavy work in progress! ⚠**

## What we're doing here

We aim to provide the bare minimum stack that will allow JVM-based Ethereum 2.0
clients to interoperate with other clients that rely on fully-fledged libp2p
stacks written in other languages.

To achieve this, we have to be wire-compliant, but don't need to fulfill all
libp2p abstractions at this time.

This effort will act as a starting point to evolve this project into a
fully-fledged libp2p stack for JVM environments, including Android runtimes.

## Definition of Done

We have identified the following components on the path to attaining a minimal
implementation:

- [ ] [connection bootstrapping](https://github.com/libp2p/specs/pull/168)
- [ ] multistream-select 1.0
- [ ] [secio](https://github.com/libp2p/specs/pull/106)
- [ ] mplex as a multiplexer
- [ ] stream multiplexing
- [ ] TCP transport (dialing and listening)
- [ ] multiformats: [multiaddr](https://github.com/multiformats/multiaddr)
- [ ] [peer ID](https://github.com/libp2p/specs/pull/100) & crypto (RSA,
  ed25519, secp256k1?)

We are explicitly leaving out the peerstore, DHT, pubsub, connection manager,
etc. and other subsystems or concepts that are internal to implementations and
do not impact the ability to hold communications with other libp2p processes.

## License

Dual-licensed under MIT and ASLv2, by way of the [Permissive License
Stack](https://protocol.ai/blog/announcing-the-permissive-license-stack/).
