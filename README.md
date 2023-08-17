# jvm-libp2p

[![](https://img.shields.io/badge/project-libp2p-yellow.svg?style=flat-square)](https://libp2p.io/)
[![Gitter](https://img.shields.io/gitter/room/libp2p/jvm-libp2p.svg)](https://gitter.im/jvm-libp2p/community)
[![](https://img.shields.io/badge/freenode-%23libp2p-yellow.svg?style=flat-square)](http://webchat.freenode.net/?channels=%23libp2p)
![Build Status](https://github.com/libp2p/jvm-libp2p/actions/workflows/build.yml/badge.svg?branch=master)
[![Discourse posts](https://img.shields.io/discourse/https/discuss.libp2p.io/posts.svg)](https://discuss.libp2p.io)

[Libp2p](https://libp2p.io/) implementation for the JVM, written in Kotlin 🔥

## Components

List of components in the Libp2p spec and their JVM implementation status 

|                          | Component                                                                                       |      Status      |
|--------------------------|-------------------------------------------------------------------------------------------------|:----------------:|
| **Transport**            | tcp                                                                                             |  :green_apple:   |
|                          | [quic](https://github.com/libp2p/specs/tree/master/quic)                                        |     :tomato:     |
|                          | websocket                                                                                       |     :lemon:      |
|                          | [webtransport](https://github.com/libp2p/specs/tree/master/webtransport)                        |                  |
|                          | [webrtc-browser-to-server](https://github.com/libp2p/specs/blob/master/webrtc/webrtc-direct.md) |                  |
|                          | [webrtc-private-to-private](https://github.com/libp2p/specs/blob/master/webrtc/webrtc.md)       |                  |
| **Secure Communication** | [noise](https://github.com/libp2p/specs/blob/master/noise/)                                     |  :green_apple:   |
|                          | [tls](https://github.com/libp2p/specs/blob/master/tls/tls.md)                                   |     :lemon:      |
|                          | [plaintext](https://github.com/libp2p/specs/blob/master/plaintext/README.md)                    |     :lemon:      |
|                          | [secio](https://github.com/libp2p/specs/blob/master/secio/README.md) **(deprecated)**           |  :green_apple:   |
| **Protocol Select**      | [multistream](https://github.com/multiformats/multistream-select)                               |  :green_apple:   |
| **Stream Multiplexing**  | [yamux](https://github.com/libp2p/specs/blob/master/yamux/README.md)                            |     :lemon:      |
|                          | [mplex](https://github.com/libp2p/specs/blob/master/mplex/README.md)                            |  :green_apple:   |
| **NAT Traversal**        | [circuit-relay-v2](https://github.com/libp2p/specs/blob/master/relay/circuit-v2.md)             |                  |
|                          | [autonat](https://github.com/libp2p/specs/tree/master/autonat)                                  |                  |
|                          | [hole-punching](https://github.com/libp2p/specs/blob/master/connections/hole-punching.md)       |                  |
| **Discovery**            | [bootstrap](https://github.com/libp2p/specs/blob/master/kad-dht/README.md#bootstrap-process)    |                  |
|                          | random-walk                                                                                     |                  |
|                          | [mdns-discovery](https://github.com/libp2p/specs/blob/master/discovery/mdns.md)                 |     :lemon:      |
|                          | [rendezvous](https://github.com/libp2p/specs/blob/master/rendezvous/README.md)                  |                  |
| **Peer Routing**         | [kad-dht](https://github.com/libp2p/specs/blob/master/kad-dht/README.md)                        |                  |
| **Publish/Subscribe**    | floodsub                                                                                        |     :lemon:      |
|                          | [gossipsub](https://github.com/libp2p/specs/tree/master/pubsub/gossipsub)                       |  :green_apple:   |
| **Storage**              | record                                                                                          |                  |
| **Other protocols**      | [ping](https://github.com/libp2p/specs/blob/master/ping/ping.md)                                |  :green_apple:   |
|                          | [identify](https://github.com/libp2p/specs/blob/master/identify/README.md)                      |  :green_apple:   |

Legend:
- :green_apple: - tested in production
- :lemon: - prototype or beta, not tested in production
- :tomato: - in progress 

## Gossip simulator

Deterministic Gossip simulator which may simulate networks as large as 10000 of peers

Please check the Simulator [README](tools/simulator/README.md) for more details

## Android support

The library is basically being developed with Android compatibility in mind. 
However we are not aware of anyone using it in production.

The `examples/android-chatter` module contains working sample Android application. This module is ignored by the Gradle 
build when no Android SDK is installed. 
To include the Android module define a valid SDK location with an `ANDROID_HOME` environment variable
or by setting the `sdk.dir` path in your project's local properties file local.properties.

Importing the project into Android Studio should work out of the box.

## Adding as a dependency to your project

Hosting of artefacts is graciously provided by [Cloudsmith](https://cloudsmith.com).

[![Latest version of 'jvm-libp2p-minimal' @ Cloudsmith](https://api-prd.cloudsmith.io/v1/badges/version/libp2p/jvm-libp2p/maven/jvm-libp2p-minimal/latest/a=noarch;xg=io.libp2p/?render=true&show_latest=true)](https://cloudsmith.io/~libp2p/repos/jvm-libp2p/packages/detail/maven/jvm-libp2p-minimal/latest/a=noarch;xg=io.libp2p/)

As an alternative, artefacts are also available on [JitPack](https://jitpack.io/).

[![](https://jitpack.io/v/libp2p/jvm-libp2p.svg)](https://jitpack.io/#libp2p/jvm-libp2p)

### Using Gradle
Add the Cloudsmith repository to the `repositories` section of your Gradle file.
```groovy
repositories {
  // ...
  maven { url "https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/" }
}
```
Add the library to the `implementation` part of your Gradle file.
```groovy
dependencies {
  // ...
  implementation 'io.libp2p:jvm-libp2p-minimal:X.Y.Z-RELEASE'
}
```
### Using Maven
Add the repository to the `dependencyManagement` section of the pom file:
```xml
<repositories>
  <repository>
    <id>libp2p-jvm-libp2p</id>
    <url>https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/</url>
    <releases>
      <enabled>true</enabled>
      <updatePolicy>always</updatePolicy>
    </releases>
    <snapshots>
      <enabled>true</enabled>
      <updatePolicy>always</updatePolicy>
    </snapshots>
  </repository>
</repositories>
```

And then add jvm-libp2p as a dependency:
``` xml
<dependency>
  <groupId>io.libp2p</groupId>
  <artifactId>jvm-libp2p-minimal</artifactId>
  <version>X.Y.Z-RELEASE</version>
  <type>pom</type>
</dependency>
```

## Building the project 

To build the library you will need just 
- JDK (Java Development Kit) of version 11 or higher
 
For building a stable release version clone the `master` branch:  
```bash
git clone https://github.com/libp2p/jvm-libp2p -b master
```
For building a version with the latest updates clone the `develop` (default) branch:
```bash
git clone https://github.com/libp2p/jvm-libp2p
```

To build the library from the `jvm-libp2p` folder, run:
```bash
./gradlew build
```

After the build is complete you may find the library `.jar` file here: `jvm-libp2p/build/libs/jvm-libp2p-minimal-0.x.y-RELEASE.jar`

## Notable users

- [Teku](https://github.com/Consensys/teku) - Ethereum Consensus Layer client 

(Please open a pull request if you want your project to be added here)

## License

Dual-licensed under MIT and ASLv2, by way of the [Permissive License
Stack](https://protocol.ai/blog/announcing-the-permissive-license-stack/).
