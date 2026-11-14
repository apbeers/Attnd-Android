package com.arcu.attnd.unit

import com.arcu.attnd.data.UsageReporter
import com.arcu.attnd.data.api.ApiJson
import com.arcu.attnd.data.api.UsageEntryDto
import com.arcu.attnd.data.api.UsageRequest
import com.arcu.attnd.domain.UsageAggregator
import com.arcu.attnd.domain.UsageEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime

/** API-005: hourly aggregation, the request's wire shape, and the reporter's flush/retry rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageReportingTest {

    private val t1605 = Instant.parse("2026-11-14T16:05:00Z")
    private val t1659 = Instant.parse("2026-11-14T16:59:59Z")
    private val t1700 = Instant.parse("2026-11-14T17:00:00Z")

    // ── Aggregator ───────────────────────────────────────────────────────────

    @Test
    fun aggregator_countsByNameDetailAndUtcHour() {
        val aggregator = UsageAggregator()
        aggregator.add("tab_view", "map", t1605)
        aggregator.add("tab_view", "map", t1659)
        aggregator.add("tab_view", "schedule", t1605)
        aggregator.add("tab_view", "map", t1700)
        aggregator.add("app_open", null, t1605)
        aggregator.add("app_open", null, t1605)

        val entries = aggregator.drain()

        assertEquals(
            setOf(
                UsageEntry("tab_view", "map", 2, "2026-11-14T16:00:00Z"),
                UsageEntry("tab_view", "schedule", 1, "2026-11-14T16:00:00Z"),
                UsageEntry("tab_view", "map", 1, "2026-11-14T17:00:00Z"),
                UsageEntry("app_open", null, 2, "2026-11-14T16:00:00Z"),
            ),
            entries.toSet(),
        )
        assertEquals(4, entries.size)
        assertEquals("oldest hour first", "2026-11-14T17:00:00Z", entries.last().at)
    }

    @Test
    fun aggregator_drainClears_andIgnoresBlankNamesAndDetails() {
        val aggregator = UsageAggregator()
        aggregator.add("", null, t1605)
        aggregator.add("search", "", t1605)
        aggregator.add("search", null, t1605)

        assertEquals(listOf(UsageEntry("search", null, 2, "2026-11-14T16:00:00Z")), aggregator.drain())
        assertTrue(aggregator.isEmpty)
        assertTrue(aggregator.drain().isEmpty())
    }

    @Test
    fun hourStart_isUtcEvenForOffsetInput() {
        val instant = OffsetDateTime.parse("2026-11-14T10:35:12.345-06:00").toInstant()
        assertEquals("2026-11-14T16:00:00Z", UsageAggregator.formatHour(instant))
    }

    // ── Wire format ─────────────────────────────────────────────────────────

    @Test
    fun request_matchesApiShape_withoutIdentifiers() {
        val request = UsageRequest(
            events = listOf(
                UsageEntryDto(name = "tab_view", detail = "map", count = 3, at = "2026-11-14T16:00:00Z"),
                UsageEntryDto(name = "app_open", at = "2026-11-14T16:00:00Z"),
            ),
        )
        val json = ApiJson.encodeToString(UsageRequest.serializer(), request)
        val root = ApiJson.parseToJsonElement(json).jsonObject

        assertEquals(setOf("platform", "events"), root.keys)
        assertEquals("android", root["platform"]!!.jsonPrimitive.content)
        val events = root["events"]!!.jsonArray
        val first = events[0].jsonObject
        assertEquals("tab_view", first["name"]!!.jsonPrimitive.content)
        assertEquals("map", first["detail"]!!.jsonPrimitive.content)
        assertEquals(3, first["count"]!!.jsonPrimitive.int)
        assertEquals("2026-11-14T16:00:00Z", first["at"]!!.jsonPrimitive.content)
        val second = events[1].jsonObject
        assertFalse("absent detail is omitted: $json", second.containsKey("detail"))
        assertEquals(1, second["count"]!!.jsonPrimitive.int)
        assertFalse(json, json.contains("device"))
    }

    // ── Reporter ────────────────────────────────────────────────────────────

    private class Sent(val eventId: Int, val request: UsageRequest)

    @Test
    fun reporter_sendsOneBatchPerEvent() = runTest {
        val sent = mutableListOf<Sent>()
        val reporter = UsageReporter(backgroundScope, sender = { id, request -> sent += Sent(id, request) }, clock = { t1605 })
        reporter.track(1, "tab_view", "map")
        reporter.track(1, "tab_view", "map")
        reporter.track(2, "app_open")

        reporter.flush()

        assertEquals(listOf(1, 2), sent.map { it.eventId })
        assertEquals(listOf(UsageEntryDto("tab_view", "map", 2, "2026-11-14T16:00:00Z")), sent[0].request.events)
        assertEquals("android", sent[1].request.platform)
        assertFalse(reporter.hasWork())
    }

    @Test
    fun reporter_retriesAFailedBatchOnce_thenDropsIt() = runTest {
        var attempts = 0
        val reporter = UsageReporter(backgroundScope, sender = { _, _ -> attempts++; throw IOException("offline") }, clock = { t1605 })
        reporter.track(1, "refresh")

        reporter.flush()
        assertEquals(1, attempts)
        assertTrue("kept for one retry", reporter.hasWork())

        reporter.flush()
        assertEquals(2, attempts)
        assertFalse("dropped after the retry failed", reporter.hasWork())

        reporter.flush()
        assertEquals(2, attempts)
    }

    @Test
    fun reporter_flushesOnTheTimer() = runTest {
        val sent = mutableListOf<Sent>()
        val reporter = UsageReporter(backgroundScope, sender = { id, request -> sent += Sent(id, request) }, clock = { t1605 })
        reporter.track(7, "search")

        testScheduler.advanceTimeBy(UsageReporter.FLUSH_INTERVAL_MS - 1)
        testScheduler.runCurrent()
        assertTrue(sent.isEmpty())

        testScheduler.advanceTimeBy(2)
        testScheduler.runCurrent()
        assertEquals(listOf(7), sent.map { it.eventId })
    }

    @Test
    fun reporter_splitsLargeBatches() = runTest {
        val sent = mutableListOf<Sent>()
        val reporter = UsageReporter(backgroundScope, sender = { id, request -> sent += Sent(id, request) }, clock = { t1605 })
        repeat(UsageReporter.MAX_ENTRIES_PER_REQUEST + 5) { reporter.track(1, "location_view", it.toString()) }

        reporter.flush()

        assertEquals(listOf(UsageReporter.MAX_ENTRIES_PER_REQUEST, 5), sent.map { it.request.events.size })
    }
}
