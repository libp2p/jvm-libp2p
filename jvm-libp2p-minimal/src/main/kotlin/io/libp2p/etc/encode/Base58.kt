package io.libp2p.etc.encode

import java.util.Arrays

// Adapted from https://github.com/bitcoinj/bitcoinj/
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val ZERO_BYTE = 0.toByte()
    private val ENCODED_ZERO = ALPHABET[0]
    private val INDEXES = IntArray(128)

    init {
        Arrays.fill(INDEXES, -1)
        for (i in 0 until ALPHABET.length) {
            INDEXES[ALPHABET[i].toInt()] = i
        }
    }

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) {
            return ""
        }

        // Count leading zeros.
        var zeros = 0
        while (zeros < input.size && input[zeros] == ZERO_BYTE) {
            zeros++
        }

        // Convert base-256 digits to base-58 digits (plus conversion to ASCII characters)
        val work = input.copyOf(input.size) // since we modify it in-place
        val encoded = CharArray(work.size * 2) // upper bound
        var outputStart = encoded.size
        var inputStart = zeros
        while (inputStart < work.size) {
            encoded[--outputStart] = ALPHABET[
                divmod(
                    work,
                    inputStart,
                    256,
                    58
                ).toInt()
            ]
            if (work[inputStart] == ZERO_BYTE) {
                ++inputStart // optimization - skip leading zeros
            }
        }
        // Preserve exactly as many leading encoded zeros in output as there were leading zeros in input.
        while (outputStart < encoded.size && encoded[outputStart] == ENCODED_ZERO) {
            ++outputStart
        }
        while (--zeros >= 0) {
            encoded[--outputStart] = ENCODED_ZERO
        }
        // Return encoded string (including encoded leading zeros).
        return String(encoded, outputStart, encoded.size - outputStart)
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        // Convert the base58-encoded ASCII chars to a base58 byte sequence (base58 digits).
        val input58 = ByteArray(input.length)
        for ((i, c) in input.withIndex()) {
            val v = if (c.toInt() < 128) INDEXES[c.toInt()].toByte() else -1
            if (v < 0) throw RuntimeException("invalid base58 encoded form")
            input58[i] = v
        }
        // Count leading zeros.
        var zeros = 0
        while (zeros < input58.size && input58[zeros] == ZERO_BYTE) {
            ++zeros
        }

        // Convert base-58 digits to base-256 digits.
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros
        while (inputStart < input58.size) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256)
            if (input58[inputStart] == ZERO_BYTE) ++inputStart // optimization - skip leading zeros
        }
        // Ignore extra leading zeroes that were added during the calculation.
        while (outputStart < decoded.size && decoded[outputStart] == ZERO_BYTE) {
            ++outputStart
        }
        // Return decoded data (including original number of leading zeros).
        return Arrays.copyOfRange(decoded, outputStart - zeros, decoded.size)
    }

    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Byte {
        // this is just long division which accounts for the base of the input digits
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder.toByte()
    }
}
