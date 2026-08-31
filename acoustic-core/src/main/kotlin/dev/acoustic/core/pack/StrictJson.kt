package dev.acoustic.core.pack

import java.util.LinkedHashMap

/** Tiny dependency-free JSON parser for manifests/configs. Produces Map/List/String/Number/Boolean/null. */
class StrictJson private constructor(private val text: String) {
    private var position = 0

    private fun value(): Any? {
        whitespace()
        if (position >= text.length) throw error("unexpected end")
        return when (val c = text[position]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> stringValue()
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            'n' -> { literal("null"); null }
            '-', in '0'..'9' -> numberValue()
            else -> throw error("unexpected character '$c'")
        }
    }

    private fun objectValue(): Map<String, Any?> {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        whitespace()
        if (peek('}')) { position++; return map }
        while (true) {
            whitespace()
            val key = stringValue()
            whitespace()
            expect(':')
            val value = value()
            if (map.containsKey(key)) throw error("duplicate object key: $key")
            map[key] = value
            whitespace()
            if (peek('}')) { position++; return map }
            expect(',')
        }
    }

    private fun arrayValue(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        whitespace()
        if (peek(']')) { position++; return list }
        while (true) {
            list.add(value())
            whitespace()
            if (peek(']')) { position++; return list }
            expect(',')
        }
    }

    private fun stringValue(): String {
        expect('"')
        val builder = StringBuilder()
        while (position < text.length) {
            val c = text[position++]
            if (c == '"') return builder.toString()
            if (c == '\\') {
                if (position >= text.length) throw error("unterminated escape")
                when (text[position++]) {
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000c')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'u' -> {
                        if (position + 4 > text.length) throw error("bad unicode escape")
                        val hex = text.substring(position, position + 4)
                        try { builder.append(hex.toInt(16).toChar()) }
                        catch (_: NumberFormatException) { throw error("bad unicode escape") }
                        position += 4
                    }
                    else -> throw error("bad escape")
                }
            } else {
                if (c.code < 0x20) throw error("control character in string")
                builder.append(c)
            }
        }
        throw error("unterminated string")
    }

    private fun numberValue(): Number {
        val start = position
        if (peek('-')) position++
        if (position >= text.length) throw error("bad number")
        if (peek('0')) position++ else digits()
        var decimal = false
        if (peek('.')) { decimal = true; position++; digits() }
        if (peek('e') || peek('E')) {
            decimal = true
            position++
            if (peek('+') || peek('-')) position++
            digits()
        }
        val number = text.substring(start, position)
        try { return if (decimal) java.lang.Double.valueOf(number) else java.lang.Long.valueOf(number) }
        catch (_: NumberFormatException) { throw error("bad number") }
    }

    private fun digits() {
        val start = position
        while (position < text.length && text[position].isDigit()) position++
        if (start == position) throw error("expected digit")
    }
    private fun literal(value: String) {
        if (!text.regionMatches(position, value, 0, value.length)) throw error("expected $value")
        position += value.length
    }
    private fun whitespace() {
        while (position < text.length) {
            when (text[position]) { ' ', '\t', '\r', '\n' -> position++; else -> return }
        }
    }
    private fun expect(c: Char) {
        whitespace()
        if (position >= text.length || text[position] != c) throw error("expected '$c'")
        position++
    }
    private fun peek(c: Char): Boolean = position < text.length && text[position] == c
    private fun error(message: String): IllegalArgumentException = IllegalArgumentException("$message at offset $position")

    companion object {
        @JvmStatic
        fun parse(text: String): Any? {
            val json = StrictJson(text)
            val value = json.value()
            json.whitespace()
            if (json.position != text.length) throw json.error("trailing data")
            return value
        }
    }
}
