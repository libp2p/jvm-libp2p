package io.libp2p.core.multistream

data class ProtocolVersion(
    val majorVersion: Int,
    val minorVersion: Int,
    val fixVersion: Int
) : Comparable<ProtocolVersion> {

    override fun compareTo(other: ProtocolVersion): Int {
        val v1 = majorVersion.compareTo(other.majorVersion)
        if (v1 != 0) {
            return v1
        }
        val v2 = minorVersion.compareTo(other.minorVersion)
        if (v2 != 0) {
            return v2
        }
        return fixVersion.compareTo(other.fixVersion)
    }

    override fun toString(): String {
        return "$majorVersion.$minorVersion.$fixVersion"
    }

    companion object {

        fun parse(versionString: String): ProtocolVersion {
            val split = versionString.split(".")
            require(split.size == 3)
            try {
                return ProtocolVersion(split[0].toInt(), split[1].toInt(), split[2].toInt())
            } catch (e: Exception) {
                throw IllegalArgumentException("Invali version string: '$versionString'", e)
            }
        }
    }
}