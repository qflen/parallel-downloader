package com.example.downloader.http

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.LongRange
import net.jqwik.api.constraints.StringLength
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/**
 * Property tests for [JdkHttpRangeFetcher.parseRetryAfter] hardened against hostile inputs.
 * The parser sits behind every 429 / 5xx response from an arbitrary upstream; a thrown
 * exception here would crash the retry loop and turn a transient failure into a permanent
 * one. The strongest property below is "no exception escapes the parser, ever, for any
 * input string". Additional properties pin the parser's contract on well-formed inputs and
 * reject malformed ones quietly.
 *
 * Hostile fuzzing seeds explicitly include the shapes RFC 9110 §10.2.3 names plus the
 * shapes a misbehaving server might emit:
 *   - non-negative delta-seconds (the spec'd happy path)
 *   - negative deltas (must reject; RFC says non-negative)
 *   - HTTP-dates with extra interior whitespace (formatter is strict; must reject)
 *   - quoted strings (RFC doesn't permit quoting Retry-After; must reject)
 *   - empty / whitespace-only / very long inputs
 *   - random arbitrary strings (the "no exception" property)
 */
class RetryAfterParserPropertyTest {

    private val anchor: Instant = Instant.parse("2026-05-05T12:00:00Z")
    private val clock: Clock = Clock.fixed(anchor, ZoneOffset.UTC)

    @Property
    fun `parser never throws for any string input`(
        @ForAll @StringLength(max = MAX_INPUT) input: String,
    ) {
        // Result may be null or a Duration; the property is just that no exception escapes.
        // A throw here would mean a hostile or malformed server response could crash the
        // retry decorator, turning a transient failure into a permanent one.
        JdkHttpRangeFetcher.parseRetryAfter(input, clock)
    }

    @Property
    fun `parser never throws for hostile strings drawn from named bad shapes`(
        @ForAll @From("hostileStrings") input: String,
    ) {
        JdkHttpRangeFetcher.parseRetryAfter(input, clock)
    }

    @Property
    fun `non-negative delta-seconds parses to that many seconds`(
        @ForAll @LongRange(min = 0, max = MAX_DELTA) deltaSeconds: Long,
    ) {
        val parsed = JdkHttpRangeFetcher.parseRetryAfter(deltaSeconds.toString(), clock)
        assertEquals(deltaSeconds.seconds, parsed)
    }

    @Property
    fun `negative delta-seconds maps to null`(
        @ForAll @LongRange(min = MIN_NEGATIVE, max = -1) negative: Long,
    ) {
        assertNull(JdkHttpRangeFetcher.parseRetryAfter(negative.toString(), clock))
    }

    @Property
    fun `surrounding whitespace around delta-seconds is tolerated`(
        @ForAll @LongRange(min = 0, max = MAX_DELTA) deltaSeconds: Long,
        @ForAll @From("whitespacePadding") padding: String,
    ) {
        val parsed = JdkHttpRangeFetcher.parseRetryAfter("$padding$deltaSeconds$padding", clock)
        assertEquals(deltaSeconds.seconds, parsed)
    }

    @Property
    fun `HTTP-date in the future parses to a positive Duration`(
        @ForAll @LongRange(min = 1, max = MAX_FUTURE_SECONDS) futureSeconds: Long,
    ) {
        val future = anchor.plusSeconds(futureSeconds)
        val httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneId.of("GMT"))
            .format(future.atZone(ZoneId.of("GMT")))
        val parsed = JdkHttpRangeFetcher.parseRetryAfter(httpDate, clock)
        assertEquals(futureSeconds.seconds, parsed)
    }

    @Property
    fun `HTTP-date in the past or present maps to null`(
        @ForAll @LongRange(min = 0, max = MAX_PAST_SECONDS) pastSeconds: Long,
    ) {
        val past = anchor.minusSeconds(pastSeconds)
        val httpDate = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneId.of("GMT"))
            .format(past.atZone(ZoneId.of("GMT")))
        val parsed = JdkHttpRangeFetcher.parseRetryAfter(httpDate, clock)
        assertNull(parsed, "past-or-present HTTP-date must reject; was '$httpDate' -> $parsed")
    }

    @Property
    fun `quoted delta-seconds reject (RFC does not permit quoting)`(
        @ForAll @LongRange(min = 0, max = MAX_DELTA) deltaSeconds: Long,
        @ForAll @From("quoteWrappers") quote: String,
    ) {
        // A naive parser might accept `"5"` as `5`; ours rejects because the regex doesn't
        // allow surrounding quotes. Keeping that strict avoids accidentally honoring a
        // server that smuggles malformed values past us.
        val parsed = JdkHttpRangeFetcher.parseRetryAfter("$quote$deltaSeconds$quote", clock)
        assertNull(parsed, "quoted Retry-After must reject; was '$quote$deltaSeconds$quote' -> $parsed")
    }

    @Property
    fun `parsed delta-seconds is non-negative when non-null`(
        @ForAll @StringLength(max = MAX_INPUT) input: String,
    ) {
        // A successful parse always yields a non-negative duration. RFC: delta-seconds are
        // non-negative by definition; HTTP-dates in the past return null. So if we got a
        // Duration, it must be >= 0.
        val parsed = JdkHttpRangeFetcher.parseRetryAfter(input, clock) ?: return
        assertTrue(
            parsed.inWholeMilliseconds >= 0L,
            "parser returned negative duration $parsed for input '$input'",
        )
    }

    @Provide
    fun hostileStrings(): Arbitrary<String> = Arbitraries.of(
        // Empty / whitespace
        "", " ", "\t", "\n", "   \t\r\n   ",
        // Mixed garbage
        "never", "soon", "in a bit", "abc", "5x", "x5",
        // Negative / huge
        "-1", "-9999999999", "9999999999999999999999999",
        // Quoted forms
        "\"5\"", "'5'", "<5>", "(5)",
        // HTTP-date with extra interior whitespace (RFC 1123 formatter is strict)
        "Tue,  15 Nov 1994 08:12:31 GMT",
        "Tue, 15  Nov 1994 08:12:31 GMT",
        "Tue, 15 Nov 1994  08:12:31 GMT",
        // HTTP-date with wrong case
        "tue, 15 nov 1994 08:12:31 gmt",
        // HTTP-date with wrong timezone form
        "Tue, 15 Nov 1994 08:12:31 UTC",
        "Tue, 15 Nov 1994 08:12:31 +0000",
        // Mixed forms
        "5 seconds", "5s", "PT5S",
        // Just punctuation
        ",", ".", ":", ";", "/", "-", "_", "*",
        // Newlines and special chars
        "5\n5", "5\r\n", "\u0000", "\u00ff",
    )

    @Provide
    fun whitespacePadding(): Arbitrary<String> = Arbitraries.of("", " ", "  ", "\t", " \t ", "  \t  ")

    @Provide
    fun quoteWrappers(): Arbitrary<String> = Arbitraries.of("\"", "'", "`", "<<", ">>", "[", "]")

    private companion object {
        const val MAX_INPUT: Int = 100
        const val MAX_DELTA: Long = 1_000_000L
        const val MIN_NEGATIVE: Long = -1_000_000L
        const val MAX_FUTURE_SECONDS: Long = 365L * 24L * 60L * 60L  // up to 1 year out
        const val MAX_PAST_SECONDS: Long = 365L * 24L * 60L * 60L
    }
}
