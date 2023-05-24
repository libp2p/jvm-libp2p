package io.libp2p.tools

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import java.util.ArrayList

class TestLogAppender : AbstractAppender("test", null, null, false, null), AutoCloseable {
    val logs: MutableList<LogEvent> = ArrayList()

    fun install(): TestLogAppender {
        (LogManager.getRootLogger() as Logger).addAppender(this)
        start()
        return this
    }

    fun uninstall() {
        stop()
        (LogManager.getRootLogger() as Logger).removeAppender(this)
    }

    override fun close() {
        uninstall()
    }

    fun hasAny(level: Level) = logs.any { it.level == level }
    fun hasAnyWarns() = hasAny(Level.ERROR) || hasAny(Level.WARN)

    override fun append(event: LogEvent) {
        logs += event.toImmutable()
    }
}
