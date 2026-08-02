package com.vibeplayer.tv

internal object HeaderParser {
    fun mergeByPriority(
        primary: Map<String, String>,
        secondary: Map<String, String>,
        rawValues: Array<String>?,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        addMissing(result, primary)
        addMissing(result, secondary)
        addMissing(result, parseRaw(rawValues))
        return result
    }

    fun parseRaw(values: Array<String>?): Map<String, String> {
        if (values.isNullOrEmpty()) return emptyMap()

        val result = linkedMapOf<String, String>()
        val allColonEntries = values.all { it.contains(':') }
        if (allColonEntries) {
            values.forEach { entry ->
                val separator = entry.indexOf(':')
                putIfSafe(result, entry.substring(0, separator), entry.substring(separator + 1))
            }
        } else {
            var index = 0
            while (index + 1 < values.size) {
                putIfSafe(result, values[index], values[index + 1])
                index += 2
            }
        }
        return result
    }

    private fun addMissing(target: MutableMap<String, String>, source: Map<String, String>) {
        source.forEach { (name, value) ->
            if (target.keys.none { it.equals(name.trim(), ignoreCase = true) }) {
                putIfSafe(target, name, value)
            }
        }
    }

    private fun putIfSafe(target: MutableMap<String, String>, rawName: String, rawValue: String) {
        val name = rawName.trim()
        val value = rawValue.trim()
        if (name.isEmpty() || value.isEmpty()) return
        if (name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) return
        target[name] = value
    }
}

