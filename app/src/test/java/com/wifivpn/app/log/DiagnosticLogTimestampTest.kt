package com.wifivpn.app.log

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The log is parsed by support tooling, so the timestamp format must not change. */
class DiagnosticLogTimestampTest {

    @Test
    fun dateTimeFormatterMatchesLegacySimpleDateFormat() {
        val zones = listOf("UTC", "Europe/Berlin", "America/Los_Angeles", "Asia/Kolkata")
        val instants = listOf(
            0L,
            1_700_000_000_123L,
            1_711_846_799_999L, // just before an EU DST switch
            1_711_846_800_000L
        )
        for (zone in zones) {
            val legacy = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone(zone) }
            val modern = DateTimeFormatter.ofPattern(DiagnosticLogger.TIME_PATTERN, Locale.US)
                .withZone(ZoneId.of(zone))
            for (ms in instants) {
                assertEquals(
                    "$zone @ $ms",
                    legacy.format(Date(ms)),
                    modern.format(Instant.ofEpochMilli(ms))
                )
            }
        }
    }
}
