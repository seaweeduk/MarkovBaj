import java.io.BufferedReader
import java.io.File

fun readJsonStringArray(file: File): Sequence<String> = sequence {
    file.bufferedReader().use { reader ->
        val firstChar = readNonWhitespace(reader)
        require(firstChar == '['.code) { "Expected '[' at start of JSON array." }

        var nextChar = readNonWhitespace(reader)
        if (nextChar == ']'.code) {
            return@sequence
        }

        while (true) {
            require(nextChar == '"'.code) { "Expected '\"' at start of JSON string." }
            yield(readJsonString(reader))

            nextChar = readNonWhitespace(reader)
            when (nextChar) {
                ','.code -> nextChar = readNonWhitespace(reader)
                ']'.code -> return@sequence
                else -> error("Expected ',' or ']' after JSON string.")
            }
        }
    }
}

private fun readNonWhitespace(reader: BufferedReader): Int {
    while (true) {
        val value = reader.read()
        require(value != -1) { "Unexpected end of JSON input." }
        if (!value.toChar().isWhitespace()) {
            return value
        }
    }
}

private fun readJsonString(reader: BufferedReader): String {
    val builder = StringBuilder()
    while (true) {
        val value = reader.read()
        require(value != -1) { "Unexpected end of JSON string." }
        when (val ch = value.toChar()) {
            '"' -> return builder.toString()
            '\\' -> builder.append(readJsonEscape(reader))
            else -> builder.append(ch)
        }
    }
}

private fun readJsonEscape(reader: BufferedReader): Char {
    val value = reader.read()
    require(value != -1) { "Unexpected end of JSON escape." }
    return when (val esc = value.toChar()) {
        '"', '\\', '/' -> esc
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            val chars = CharArray(4)
            for (index in 0 until 4) {
                val hexValue = reader.read()
                require(hexValue != -1) { "Unexpected end of unicode escape." }
                chars[index] = hexValue.toChar()
            }
            chars.concatToString().toInt(16).toChar()
        }
        else -> error("Invalid JSON escape: \\$esc")
    }
}

