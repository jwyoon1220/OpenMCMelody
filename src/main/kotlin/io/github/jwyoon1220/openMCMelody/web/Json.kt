package io.github.jwyoon1220.openMCMelody.web

/**
 * Minimal hand-rolled JSON reader/writer. The web API's request/response shapes are small and
 * fully controlled by this plugin (client and server are the same codebase), so a full JSON
 * library dependency isn't worth pulling in just for this.
 *
 * Values round-trip as: null, [String], [Boolean], [Double] (all numbers), [Map]<String, Any?>
 * (objects, insertion-ordered), [List]<Any?> (arrays).
 */
object Json {

    fun stringify(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(value)
            is Number -> sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    write(v, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(v, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val s: String) {
        var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) throw JsonException("Unexpected end of input")
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): LinkedHashMap<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') throw JsonException("Expected ':' at $pos")
                pos++
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): ArrayList<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            if (peek() != '"') throw JsonException("Expected string at $pos")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("Unterminated string")
                val c = s[pos++]
                if (c == '"') break
                if (c == '\\') {
                    if (pos >= s.length) throw JsonException("Unterminated escape")
                    when (val esc = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            if (pos + 4 > s.length) throw JsonException("Invalid unicode escape")
                            sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw JsonException("Invalid escape '\\$esc'")
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            if (pos == start) throw JsonException("Expected value at $pos")
            return s.substring(start, pos).toDouble()
        }

        private fun expect(literal: String) {
            if (pos + literal.length > s.length || s.substring(pos, pos + literal.length) != literal) {
                throw JsonException("Expected '$literal' at $pos")
            }
            pos += literal.length
        }

        private fun peek(): Char {
            if (pos >= s.length) throw JsonException("Unexpected end of input")
            return s[pos]
        }

        private fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}

class JsonException(message: String) : RuntimeException(message)

fun Map<*, *>.jsonString(key: String): String? = this[key] as? String

fun Map<*, *>.jsonStringList(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

/** Parser.parseNumber always produces a [Double] - see [Json.Parser]. */
fun Map<*, *>.jsonNumber(key: String): Double? = this[key] as? Double
