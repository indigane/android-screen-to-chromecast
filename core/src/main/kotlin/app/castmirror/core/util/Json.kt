package app.castmirror.core.util

/**
 * A minimal, dependency-free JSON model, recursive-descent parser and writer.
 *
 * `org.json` is an Android platform class and is not present on a plain JDK, and the project
 * takes no third-party dependencies, so this is hand-rolled. It is deliberately small but
 * complete enough for the Cast control protocol:
 *
 *  * Object key order is preserved (`LinkedHashMap`), which matters because the OFFER JSON is
 *    compared field-by-field against the reference implementation's output in tests.
 *  * Numbers keep their original text so that an integer round-trips as an integer and never
 *    acquires a `.0` suffix — receivers parse `udpPort` and `ssrc` as integers.
 *  * Parsing is strict about structure but tolerant about trailing whitespace.
 */
sealed class Json {

    /** JSON `null`. */
    object Null : Json()

    /** JSON `true` / `false`. */
    data class Bool(val value: Boolean) : Json()

    /**
     * A JSON number, stored as its literal source text so that formatting is lossless.
     */
    class Num(val text: String) : Json() {
        override fun equals(other: Any?): Boolean = other is Num && other.text == text
        override fun hashCode(): Int = text.hashCode()
        override fun toString(): String = "Num($text)"
    }

    /** A JSON string. */
    data class Str(val value: String) : Json()

    /** A JSON array. */
    class Arr(val items: MutableList<Json> = ArrayList()) : Json() {
        /** Number of elements. */
        val size: Int get() = items.size

        /** Element at [i], or [Null] if out of range. */
        operator fun get(i: Int): Json = if (i in items.indices) items[i] else Null

        /** Appends [v]. */
        fun add(v: Json): Arr { items.add(v); return this }

        override fun equals(other: Any?): Boolean = other is Arr && other.items == items
        override fun hashCode(): Int = items.hashCode()
    }

    /** A JSON object with insertion-ordered keys. */
    class Obj(val entries: LinkedHashMap<String, Json> = LinkedHashMap()) : Json() {

        /** Value for [key], or `null` when the key is absent. */
        operator fun get(key: String): Json? = entries[key]

        /** True when [key] is present (even if its value is `null`). */
        fun has(key: String): Boolean = entries.containsKey(key)

        /** Inserts or replaces [key]. */
        fun put(key: String, v: Json): Obj { entries[key] = v; return this }

        /** Inserts a string value. */
        fun put(key: String, v: String): Obj = put(key, Str(v))

        /** Inserts an integer value. */
        fun put(key: String, v: Int): Obj = put(key, of(v))

        /** Inserts a long value. */
        fun put(key: String, v: Long): Obj = put(key, of(v))

        /** Inserts a floating-point value. */
        fun put(key: String, v: Double): Obj = put(key, of(v))

        /** Inserts a boolean value. */
        fun put(key: String, v: Boolean): Obj = put(key, Bool(v))

        override fun equals(other: Any?): Boolean = other is Obj && other.entries == entries
        override fun hashCode(): Int = entries.hashCode()
    }

    // ---- typed accessors -------------------------------------------------------------------

    /** This value as a [String], or `null` if it is not a JSON string. */
    fun asStringOrNull(): String? = (this as? Str)?.value

    /** This value as a [Boolean], or `null` if it is not a JSON boolean. */
    fun asBoolOrNull(): Boolean? = (this as? Bool)?.value

    /** This value as an [Int], or `null` if it is not an integral JSON number. */
    fun asIntOrNull(): Int? = asLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

    /** This value as a [Long], or `null` if it is not an integral JSON number. */
    fun asLongOrNull(): Long? {
        val n = this as? Num ?: return null
        return n.text.toLongOrNull() ?: n.text.toDoubleOrNull()?.let {
            if (it == Math.floor(it) && !it.isInfinite()) it.toLong() else null
        }
    }

    /** This value as a [Double], or `null` if it is not a JSON number. */
    fun asDoubleOrNull(): Double? = (this as? Num)?.text?.toDoubleOrNull()

    /** This value as an [Obj], or `null`. */
    fun asObjOrNull(): Obj? = this as? Obj

    /** This value as an [Arr], or `null`. */
    fun asArrOrNull(): Arr? = this as? Arr

    /** Serializes this value to compact JSON text. */
    fun encode(): String = StringBuilder().also { write(it) }.toString()

    private fun write(sb: StringBuilder) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (value) "true" else "false")
            is Num -> sb.append(text)
            is Str -> escape(value, sb)
            is Arr -> {
                sb.append('[')
                for ((i, v) in items.withIndex()) {
                    if (i > 0) sb.append(',')
                    v.write(sb)
                }
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in entries) {
                    if (!first) sb.append(',')
                    first = false
                    escape(k, sb)
                    sb.append(':')
                    v.write(sb)
                }
                sb.append('}')
            }
        }
    }

    companion object {
        /** Wraps an [Int]. */
        fun of(v: Int): Num = Num(v.toString())

        /** Wraps a [Long]. */
        fun of(v: Long): Num = Num(v.toString())

        /**
         * Wraps a [Double]. Whole values are emitted without a fractional part so that e.g.
         * a resolution width never appears as `1920.0`.
         */
        fun of(v: Double): Num =
            if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 9.0e15) Num(v.toLong().toString())
            else Num(v.toString())

        /** Wraps a [String]. */
        fun of(v: String): Str = Str(v)

        /** Wraps a [Boolean]. */
        fun of(v: Boolean): Bool = Bool(v)

        /** Builds an array of strings. */
        fun arrayOfStrings(values: List<String>): Arr = Arr(values.map { Str(it) as Json }.toMutableList())

        /** Builds an array of ints. */
        fun arrayOfInts(values: List<Int>): Arr = Arr(values.map { of(it) as Json }.toMutableList())

        /**
         * Parses [text] as a single JSON value.
         *
         * @throws JsonException on any syntax error.
         */
        fun parse(text: String): Json {
            val p = Parser(text)
            p.skipWs()
            val v = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) p.fail("trailing content")
            return v
        }

        /** Parses [text], returning `null` instead of throwing on malformed input. */
        fun parseOrNull(text: String): Json? = try { parse(text) } catch (e: JsonException) { null }

        private fun escape(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else ->
                        if (c < ' ' || c == '\u007F') sb.append(String.format("\\u%04x", c.code))
                        else sb.append(c)
                }
            }
            sb.append('"')
        }
    }

    /** Recursive-descent parser over a JSON document. */
    private class Parser(val src: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun fail(msg: String): Nothing = throw JsonException("$msg at offset $pos")

        fun skipWs() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(): Json {
            if (atEnd()) fail("unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> { expect("true"); Bool(true) }
                'f' -> { expect("false"); Bool(false) }
                'n' -> { expect("null"); Null }
                else ->
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else fail("unexpected character '$c'")
            }
        }

        fun expect(word: String) {
            if (!src.startsWith(word, pos)) fail("expected '$word'")
            pos += word.length
        }

        fun parseObject(): Obj {
            pos++ // '{'
            val o = Obj()
            skipWs()
            if (!atEnd() && src[pos] == '}') { pos++; return o }
            while (true) {
                skipWs()
                if (atEnd() || src[pos] != '"') fail("expected object key")
                val k = parseString()
                skipWs()
                if (atEnd() || src[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                o.entries[k] = parseValue()
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return o }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun parseArray(): Arr {
            pos++ // '['
            val a = Arr()
            skipWs()
            if (!atEnd() && src[pos] == ']') { pos++; return a }
            while (true) {
                skipWs()
                a.items.add(parseValue())
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return a }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = src[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (atEnd()) fail("unterminated escape")
                        when (val e = src[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= src.length) fail("truncated \\u escape")
                                val hex = src.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                        pos++
                    }
                    else -> {
                        if (c < ' ') fail("raw control character in string")
                        sb.append(c); pos++
                    }
                }
            }
        }

        fun parseNumber(): Num {
            val start = pos
            if (!atEnd() && src[pos] == '-') pos++
            if (atEnd() || src[pos] !in '0'..'9') fail("bad number")
            // JSON forbids leading zeros: "0" and "0.5" are fine, "01" is not.
            if (src[pos] == '0') {
                pos++
                if (!atEnd() && src[pos] in '0'..'9') fail("leading zero in number")
            } else {
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && src[pos] == '.') {
                pos++
                if (atEnd() || src[pos] !in '0'..'9') fail("bad fraction")
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (!atEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                if (atEnd() || src[pos] !in '0'..'9') fail("bad exponent")
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            return Num(src.substring(start, pos))
        }
    }
}

/** Thrown by [Json.parse] for malformed input. */
class JsonException(message: String) : RuntimeException(message)

/** Builds a [Json.Obj] with a small DSL: `jsonObj { put("a", 1) }`. */
inline fun jsonObj(build: Json.Obj.() -> Unit): Json.Obj = Json.Obj().apply(build)

/** Builds a [Json.Arr] with a small DSL. */
inline fun jsonArr(build: Json.Arr.() -> Unit): Json.Arr = Json.Arr().apply(build)
