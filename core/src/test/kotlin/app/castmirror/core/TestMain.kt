package app.castmirror.core

import kotlin.system.exitProcess

/**
 * Runs every test suite and exits non-zero if anything failed.
 *
 * `core/build.sh` invokes this; there is no JUnit anywhere in the project, so this `main` is the
 * whole test runner.
 */
fun main() {
    println("castmirror core test suite")
    println("=".repeat(64))

    runJsonTests()
    runProtobufTests()
    runOfferAnswerTests()
    runGmsProfileTests()
    runRtpTests()
    runCryptoTests()
    runRtcpTests()
    runSenderTests()
    runBitrateSuggestorTests()
    runMdnsTests()
    runEndToEndTest()
    runFieldSevenCorrelationTest()

    exitProcess(T.summary())
}
