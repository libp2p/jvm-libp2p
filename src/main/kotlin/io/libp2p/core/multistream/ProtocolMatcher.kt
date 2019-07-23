package io.libp2p.core.multistream

/**
 * A matcher that evaluates whether a given protocol activates based on its protocol ID.
 */
data class ProtocolMatcher(val mode: Mode, val name: String? = null, val predicate: ((String) -> Boolean)? = null) {

    init {
        when (mode) {
            Mode.NEVER -> {
            }
            Mode.STRICT -> name ?: throw IllegalArgumentException("STRICT mode requires a name to match against")
            Mode.PREFIX -> name ?: throw IllegalArgumentException("PREFIX mode requires a prefix to match against")
            Mode.PREDICATE -> predicate
                ?: throw IllegalArgumentException("PREDICATE mode requires a predicate to invoke")
        }
    }

    /**
     * Evaluates this matcher against a proposed protocol ID.
     */
    // TODO: we could use an operator fun here, e.g. == or invoke, but that would probably harm interop with Java.
    fun matches(proposed: String): Boolean = when (mode) {
        Mode.NEVER -> false
        Mode.STRICT -> proposed == name!!
        Mode.PREFIX -> proposed.startsWith(name!!)
        Mode.PREDICATE -> predicate!!(proposed)
    }
}

/**
 * The match mode determines the heuristic by which a protocol will be selected.
 */
enum class Mode {
    /**
     * This match never succeeds.
     */
    NEVER,

    /**
     * The match will succeed if the proposal matches verbatim.
     */
    STRICT,

    /**
     * The match will succeed if the proposal starts with the name herein.
     */
    PREFIX,

    /**
     * The match will succeed if the predicate returns true.
     */
    PREDICATE
}