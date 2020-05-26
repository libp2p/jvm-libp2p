package io.libp2p.etc.types

import java.time.Duration

val Number.millis: Duration
    get() = Duration.ofMillis(this.toLong())

val Number.seconds: Duration
    get() = Duration.ofSeconds(this.toLong())

val Number.minutes: Duration
    get() = Duration.ofMinutes(this.toLong())

val Number.hours: Duration
    get() = Duration.ofHours(this.toLong())

val Number.days: Duration
    get() = Duration.ofDays(this.toLong())

operator fun Duration.times(i: Int): Duration = Duration.ofMillis(toMillis() * i)
