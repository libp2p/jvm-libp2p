package io.libp2p.pubsub.flood

import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.PubsubRouterTest

class FloodPubsubRouterTest : PubsubRouterTest(DeterministicFuzz.createFloodFuzzRouterFactory())
