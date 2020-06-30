package io.libp2p.tools

/**
 * When protobuf message is converted to String it prints binary data ([ByteSequence]) by escaping bytes as
 * characters.
 * The string looks like `\026\030L\034E?\226\374`
 * This functions converts this representation back to bytes.
 * This can be handy when sorting out logs
 */
fun parseProtobufBytesToString(str: String): ByteArray {
    val bytes = mutableListOf<Byte>()
    var pos = 0
    while (pos < str.length) {
        bytes += when (str[pos]) {
            '\\' -> {
                pos++
                when (str[pos]) {
                    in '0'..'9' -> {
                        val r = ((("" + str[pos]).toInt() shl 6) or
                                (("" + str[pos + 1]).toInt() shl 3) or
                                (("" + str[pos + 2]).toInt())).toByte()
                        pos += 2
                        r
                    }
                    'a' -> 0x07
                    'b' -> '\b'.toByte()
                    'f' -> 0xC
                    'n' -> '\n'.toByte()
                    'r' -> '\r'.toByte()
                    't' -> '\t'.toByte()
                    'v' -> 0x0b
                    '\\' -> '\\'.toByte()
                    '\'' -> '\''.toByte()
                    '"' -> '"'.toByte()
                    else -> throw IllegalArgumentException("Invalid escape char")
                }.also { pos++ }
            }
            else -> str[pos++].toByte()
        }
    }
    return bytes.toByteArray()
}
