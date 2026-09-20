package com.vibeplayer.tv

/**
 * Decoder for the URI-safe format emitted by the Lampa bridge's small lz-string encoder.
 * Keeping this decoder in the app avoids carrying a whole URL graph through Android Binder.
 */
internal object LzString {
    private const val URI_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-$"

    fun decompressFromEncodedURIComponent(input: String?): String? {
        if (input == null) return ""
        if (input.isEmpty()) return null
        val normalized = input.replace(' ', '+')
        return decompress(normalized.length, 32) { index ->
            val character = normalized.getOrNull(index) ?: return@decompress 0
            URI_ALPHABET.indexOf(character).coerceAtLeast(0)
        }
    }

    private fun decompress(
        length: Int,
        resetValue: Int,
        nextValue: (Int) -> Int,
    ): String? {
        val dictionary = ArrayList<String?>(16)
        dictionary.add(null)
        dictionary.add(null)
        dictionary.add(null)
        var enlargeIn = 4
        var dictSize = 4
        var numBits = 3
        var dataValue = nextValue(0)
        var dataPosition = resetValue
        var dataIndex = 1

        fun readBits(count: Int): Int {
            var bits = 0
            var power = 1
            repeat(count) {
                val resb = dataValue and dataPosition
                dataPosition = dataPosition ushr 1
                if (dataPosition == 0) {
                    dataPosition = resetValue
                    dataValue = if (dataIndex < length) nextValue(dataIndex++) else 0
                }
                if (resb != 0) bits = bits or power
                power = power shl 1
            }
            return bits
        }

        val firstType = readBits(2)
        val first = when (firstType) {
            0 -> readBits(8).toChar().toString()
            1 -> readBits(16).toChar().toString()
            2 -> return ""
            else -> return null
        }
        dictionary.add(first)
        var w = first
        val result = StringBuilder(first)

        while (true) {
            if (dataIndex > length) return ""
            val code = readBits(numBits)
            var c = code
            when (code) {
                0 -> {
                    dictionary.add(readBits(8).toChar().toString())
                    c = dictSize
                    dictSize += 1
                    enlargeIn -= 1
                }
                1 -> {
                    dictionary.add(readBits(16).toChar().toString())
                    c = dictSize
                    dictSize += 1
                    enlargeIn -= 1
                }
                2 -> return result.toString()
            }

            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits += 1
            }

            val entry = dictionary.getOrNull(c) ?: if (c == dictSize) {
                w + w.first()
            } else {
                return null
            }
            result.append(entry)
            dictionary.add(w + entry.first())
            dictSize += 1
            enlargeIn -= 1
            w = entry

            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits += 1
            }
        }
    }
}
