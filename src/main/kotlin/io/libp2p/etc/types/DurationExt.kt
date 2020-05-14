package io.libp2p.etc.types

import java.time.Duration

val Int.millis: Duration
    get() = Duration.ofMillis(this.toLong())

val Int.seconds: Duration
    get() = Duration.ofSeconds(this.toLong())

val Int.minutes: Duration
    get() = Duration.ofMinutes(this.toLong())

val Int.hours: Duration
    get() = Duration.ofHours(this.toLong())

val Int.days: Duration
    get() = Duration.ofDays(this.toLong())
