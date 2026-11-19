package com.kdzw8p.mqa.v71f.utils

import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LepaoTodayRunTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-25T05:00:00Z")
    private fun record(id: Long, time: String, uid: String = "123") = JSONObject()
        .put("id", id).put("start_time", time).put("uid", uid).put("record_status", "1")
    private fun page(vararg rows: JSONObject) = JSONObject().put("list", JSONArray(rows.toList()))

    @Test fun todayIsDeterminedByLocalCalendarNotUtcAndSupportsEpochUnits() {
        assertEquals("2026-10-25", LepaoTodayRun.recordDay("2026-10-24T16:00:00Z", zone).toString())
        val t = Instant.parse("2026-10-24T16:00:00Z")
        assertEquals("2026-10-25", LepaoTodayRun.recordDay(t.epochSecond.toString(), zone).toString())
        assertEquals("2026-10-25", LepaoTodayRun.recordDay(t.toEpochMilli().toString(), zone).toString())
        assertEquals("2026-10-24", LepaoTodayRun.recordDay("2026-10-24 23:59:59", zone).toString())
        assertNull(LepaoTodayRun.recordDay("unknown", zone))
        assertNull(LepaoTodayRun.recordDay("0", zone))
    }

    @Test fun countsAllPagesWithoutAssumingNewestFirstAndDeduplicates() {
        val pages = listOf(page(record(1, "2026-10-24")),
            page(record(1, "2026-10-24"), record(2, "2026-10-25 08:00:00")),
            page(record(3, "2026-10-25 11:00:00")), page())
        val summary = LepaoTodayRun.collect("123", now, zone) { n, year ->
            assertEquals("2026", year)
            pages[n - 1]
        }
        assertEquals("2026-10-25", summary.day)
        assertEquals(2, summary.count)
    }

    @Test fun completeEmptyDayIsZeroButInvalidResponsesAreNot() {
        assertEquals(0, LepaoTodayRun.collect("123", now, zone) { _, _ -> page() }.count)
        assertEquals(0, LepaoTodayRun.collect("123", now, zone) { n, _ -> if (n == 1) page(record(1, "2026-10-24")) else page() }.count)
        for (bad in listOf(JSONObject(), page(record(1, "invalid")), page(record(1, "2026-10-25", "456")))) {
            try { LepaoTodayRun.collect("123", now, zone) { _, _ -> bad }; fail("Invalid history must remain unknown") }
            catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun repeatedPagesCannotFalselyConfirmNotRun() {
        try { LepaoTodayRun.collect("123", now, zone) { _, _ -> page(record(1, "2026-10-24")) }; fail() }
        catch (_: IllegalStateException) { }
    }
}
