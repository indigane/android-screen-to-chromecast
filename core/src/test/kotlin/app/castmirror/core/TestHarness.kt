package app.castmirror.core

import app.castmirror.core.util.Bytes

/**
 * A ~100-line homemade assertion harness.
 *
 * There is no JUnit here on purpose: the protocol core must build and test with nothing but
 * `kotlinc` and a JDK, so the whole suite is a `main()` that counts failures and exits non-zero.
 */
object T {
    private var passed = 0
    private var failed = 0
    private val failures = ArrayList<String>()
    private var currentSection = ""

    /** Starts a named group of checks. */
    fun section(name: String) {
        currentSection = name
        println("\n$name")
    }

    /** Asserts [condition]; [detail] is printed on failure. */
    fun check(name: String, condition: Boolean, detail: String = "") {
        if (condition) {
            passed++
            println("  PASS  $name")
        } else {
            failed++
            val line = "$currentSection / $name${if (detail.isEmpty()) "" else ": $detail"}"
            failures.add(line)
            println("  FAIL  $name${if (detail.isEmpty()) "" else " -- $detail"}")
        }
    }

    /** Asserts [actual] equals [expected]. */
    fun <A> assertEquals(name: String, expected: A, actual: A) =
        check(name, expected == actual, "expected <$expected> but was <$actual>")

    /** Asserts two byte arrays are identical, reporting the first differing offset. */
    fun assertBytes(name: String, expected: ByteArray, actual: ByteArray) {
        if (expected.contentEquals(actual)) { check(name, true); return }
        val limit = minOf(expected.size, actual.size)
        var at = -1
        for (i in 0 until limit) if (expected[i] != actual[i]) { at = i; break }
        val detail = if (at >= 0) {
            "first difference at byte $at: expected 0x${hex(expected[at])} got 0x${hex(actual[at])}" +
                "\n         expected: ${preview(expected)}\n         actual:   ${preview(actual)}"
        } else {
            "length differs: expected ${expected.size}, got ${actual.size}" +
                "\n         expected: ${preview(expected)}\n         actual:   ${preview(actual)}"
        }
        check(name, false, detail)
    }

    /** Asserts that [block] throws; optionally that the message contains [messageContains]. */
    fun assertThrows(name: String, messageContains: String? = null, block: () -> Unit) {
        try {
            block()
            check(name, false, "expected an exception but none was thrown")
        } catch (e: Throwable) {
            val msg = e.message ?: ""
            if (messageContains == null || msg.contains(messageContains)) check(name, true)
            else check(name, false, "message '$msg' does not contain '$messageContains'")
        }
    }

    /** True if every check so far has passed. */
    fun ok(): Boolean = failed == 0

    /** Prints the summary and returns the process exit code. */
    fun summary(): Int {
        println("\n" + "=".repeat(64))
        if (failed == 0) {
            println("ALL TESTS PASSED  ($passed checks)")
        } else {
            println("FAILURES ($failed of ${passed + failed} checks):")
            failures.forEach { println("  - $it") }
        }
        println("=".repeat(64))
        return if (failed == 0) 0 else 1
    }

    private fun hex(b: Byte): String = String.format("%02x", b.toInt() and 0xFF)

    private fun preview(b: ByteArray): String {
        val n = minOf(b.size, 48)
        val body = Bytes.toHex(b.copyOf(n))
        return if (b.size > n) "$body... (${b.size} bytes)" else "$body (${b.size} bytes)"
    }
}
