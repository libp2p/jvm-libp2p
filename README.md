# jvm-libp2p

[![](https://img.shields.io/badge/project-libp2p-yellow.svg?style=flat-square)](https://libp2p.io/)
[![Gitter](https://img.shields.io/gitter/room/libp2p/jvm-libp2p.svg)](https://gitter.im/jvm-libp2p/community)
[![](https://img.shields.io/badge/freenode-%23libp2p-yellow.svg?style=flat-square)](http://webchat.freenode.net/?channels=%23libp2p)
[![Build Status](https://travis-ci.com/libp2p/jvm-libp2p.svg?branch=master)](https://travis-ci.com/libp2p/jvm-libp2p)
[![Discourse posts](https://img.shields.io/discourse/https/discuss.libp2p.io/posts.svg)](https://discuss.libp2p.io)

> a libp2p implementation for the JVM, written in Kotlin 🔥

**⚠️ This is heavy work in progress! ⚠**

## Roadmap

The endeavour to build jvm-libp2p is split in two phases:

* **minimal phase (v0.x):** aims to provide the bare minimum stack that will
  allow JVM-based Ethereum 2.0 clients to interoperate with other clients that
  rely on fully-fledged libp2p stacks written in other languages.
    * To achieve this, we have to be wire-compliant, but don't need to fulfill
      the complete catalogue of libp2p abstractions.
    * This effort will act as a starting point to evolve this project into a
      fully-fledged libp2p stack for JVM environments, including Android
      runtimes.
    * We are shooting for Aug/early Sept 2019.
    * Only Java-friendly façade.

* **maturity phase (v1.x):** upgrades the minimal version to a flexible and
  versatile stack adhering to the key design principles of modularity and
  pluggability that define the libp2p project. It adds features present in
  mature implementations like go-libp2p, rust-libp2p, js-libp2p.
    * will offer: pluggable peerstore, connection manager, QUIC transport,
      circuit relay, AutoNAT, AutoRelay, NAT traversal, etc.
    * Android-friendly.
    * Kotlin coroutine-based façade, possibly a Reactive Streams façade too.
    * work will begin after the minimal phase concludes.

## minimal phase (v0.x): Definition of Done

We have identified the following components on the path to attaining a minimal
implementation:

- [X] multistream-select 1.0
- [X] multiformats: [multiaddr](https://github.com/multiformats/multiaddr)
- [X] crypto (RSA, ed25519, secp256k1)
- [X] [secio](https://github.com/libp2p/specs/pull/106)
- [X] [connection bootstrapping](https://github.com/libp2p/specs/pull/168)
- [X] mplex as a multiplexer
- [X] stream multiplexing
- [X] TCP transport (dialing and listening)
- [X] Identify protocol
- [X] Ping protocol
- [X] [peer ID](https://github.com/libp2p/specs/pull/100)

We are explicitly leaving out the peerstore, DHT, pubsub, connection manager,
etc. and other subsystems or concepts that are internal to implementations and
do not impact the ability to hold communications with other libp2p processes.

## Adding as a dependency to your Gradle project:

```
   repositories {
       jcenter()
   }

   implementation 'io.libp2p:jvm-libp2p-minimal:0.1.0-RELEASE'
```


## License

Dual-licensed under MIT and ASLv2, by way of the [Permissive License
Stack](https://protocol.ai/blog/announcing-the-permissive-license-stack/).
