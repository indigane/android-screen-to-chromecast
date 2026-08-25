package app.castmirror.core

import app.castmirror.core.util.Json
import app.castmirror.core.util.jsonObj

/** The hand-rolled JSON parser and writer. */
fun runJsonTests() {
    T.section("JSON parser")

    T.assertEquals("null", Json.Null, Json.parse("null"))
    T.assertEquals("true", true, Json.parse("true").asBoolOrNull())
    T.assertEquals("false", false, Json.parse("false").asBoolOrNull())
    T.assertEquals("integer", 42, Json.parse("42").asIntOrNull())
    T.assertEquals("negative integer", -7, Json.parse("-7").asIntOrNull())
    T.assertEquals("zero", 0, Json.parse("0").asIntOrNull())
    T.assertEquals("uint32-sized value", 4294967295L, Json.parse("4294967295").asLongOrNull())
    T.assertEquals("double", 1.5, Json.parse("1.5").asDoubleOrNull())
    T.assertEquals("exponent", 6.2208e7, Json.parse("6.2208e7").asDoubleOrNull())
    T.assertEquals("negative exponent", 1.0e-3, Json.parse("1.0e-3").asDoubleOrNull())
    T.assertEquals("string", "hi", Json.parse("\"hi\"").asStringOrNull())
    T.assertEquals("empty string", "", Json.parse("\"\"").asStringOrNull())

    T.assertEquals("escapes", "a\"b\\c\nd\te", Json.parse("\"a\\\"b\\\\c\\nd\\te\"").asStringOrNull())
    T.assertEquals("unicode escape", "\u00e9", Json.parse("\"\\u00e9\"").asStringOrNull())
    T.assertEquals("solidus escape", "a/b", Json.parse("\"a\\/b\"").asStringOrNull())
    T.assertEquals("raw utf-8", "Salon TV", Json.parse("\"Salon TV\"").asStringOrNull())

    T.assertEquals("empty object", 0, Json.parse("{}").asObjOrNull()!!.entries.size)
    T.assertEquals("empty array", 0, Json.parse("[]").asArrOrNull()!!.size)
    T.assertEquals("whitespace tolerated", 1, Json.parse("  {  \"a\" : 1 }  ").asObjOrNull()!!["a"]?.asIntOrNull())

    val nested = Json.parse("""{"a":{"b":[1,2,{"c":"d"}]}}""").asObjOrNull()!!
    T.assertEquals(
        "nested access",
        "d",
        nested["a"]?.asObjOrNull()?.get("b")?.asArrOrNull()?.get(2)?.asObjOrNull()?.get("c")?.asStringOrNull(),
    )

    // Key order must be preserved: the OFFER is compared key-by-key downstream.
    val ordered = Json.parse("""{"z":1,"a":2,"m":3}""").asObjOrNull()!!
    T.assertEquals("key order preserved", listOf("z", "a", "m"), ordered.entries.keys.toList())

    T.section("JSON parser rejects malformed input")

    val bad = listOf(
        "", "{", "}", "[", "]", "{\"a\"}", "{\"a\":}", "{a:1}", "{'a':1}",
        "[1,]", "{\"a\":1,}", "01", "+1", ".5", "1.", "1e", "tru", "nul",
        "\"unterminated", "\"bad\\xescape\"", "{\"a\":1}x", "[1 2]",
    )
    for (s in bad) {
        T.check("rejects ${if (s.isEmpty()) "<empty>" else s}", Json.parseOrNull(s) == null)
    }

    T.section("JSON writer")

    T.assertEquals("writes integers without a decimal point", "1920", Json.of(1920).encode())
    T.assertEquals("writes whole doubles as integers", "30", Json.of(30.0).encode())
    T.assertEquals("writes fractional doubles", "0.05", Json.of(0.05).encode())
    T.assertEquals("writes booleans", "true", Json.of(true).encode())
    T.assertEquals("escapes on write", "\"a\\\"b\\nc\"", Json.of("a\"b\nc").encode())
    T.assertEquals(
        "escapes control characters",
        "\"\\u0001\"",
        Json.of("\u0001").encode(),
    )

    val obj = jsonObj {
        put("type", "OFFER")
        put("seqNum", 1)
        put("ok", true)
        put("nested", jsonObj { put("x", 1L) })
        put("list", Json.arrayOfInts(listOf(0, 1)))
    }
    T.assertEquals(
        "writes an object in insertion order",
        """{"type":"OFFER","seqNum":1,"ok":true,"nested":{"x":1},"list":[0,1]}""",
        obj.encode(),
    )
    T.assertEquals("write/parse round-trip", obj, Json.parse(obj.encode()))

    T.section("JSON accessors")

    val o = Json.parse("""{"i":5,"s":"x","b":false,"d":1.25,"n":null,"big":99999999999}""").asObjOrNull()!!
    T.check("asIntOrNull on a string is null", o["s"]?.asIntOrNull() == null)
    T.check("asStringOrNull on a number is null", o["i"]?.asStringOrNull() == null)
    T.check("asBoolOrNull on null is null", o["n"]?.asBoolOrNull() == null)
    T.check("asIntOrNull overflows to null", o["big"]?.asIntOrNull() == null)
    T.assertEquals("asLongOrNull handles big values", 99999999999L, o["big"]?.asLongOrNull())
    T.check("missing key is null", o["absent"] == null)
    T.check("present-but-null key is distinguishable", o.has("n") && o["n"] == Json.Null)
    T.assertEquals("integral double reads as long", 5L, Json.parse("5.0").asLongOrNull())
}
