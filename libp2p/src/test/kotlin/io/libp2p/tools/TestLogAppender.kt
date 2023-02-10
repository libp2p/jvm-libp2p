package io.libp2p.tools


import java.util.ArrayList

class TestLogAppender { //}: AbstractAppender("test", null, null, false, null), AutoCloseable {
    /*val logs: MutableList<LogEvent> = ArrayList()

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
    }*/
}
