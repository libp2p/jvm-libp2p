package io.libp2p.tools

import java.util.logging.*

class TestLogAppender : MemoryHandler(ConsoleHandler(), 1, Level.ALL), AutoCloseable {
    val logs: MutableList<LogRecord> = ArrayList()
    val logger: Logger = Logger.getLogger("") // root logger
    fun install(): TestLogAppender {
        logger.addHandler(this)
        return this
    }

    fun uninstall() {
        logger.removeHandler(this)
    }

    override fun close() {
        uninstall()
    }

    fun hasAny(level: Level) = logs.any { it.level == level }
    fun hasAnyWarns() = hasAny(Level.SEVERE) || hasAny(Level.WARNING)

    @Synchronized
    override fun publish(record: LogRecord) {
        super.publish(record)
        logs += record
    }
}
