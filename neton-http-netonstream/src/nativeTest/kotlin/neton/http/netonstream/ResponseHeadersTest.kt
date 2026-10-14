package neton.http.netonstream

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ResponseHeadersTest {
    private fun response(cors: Map<String, List<String>> = emptyMap()) =
        NetonStreamLiveResponse(cors, 1) { }

    @Test
    fun snapshotsSurviveReplacementPromotionAndClear() {
        val headers = response().headers
        assertEquals(emptyMap(), headers.toMap())
        headers.set("Content-Type", "text/plain")
        val first = headers.toMap()
        assertSame(first, headers.toMap())
        assertEquals("text/plain", headers["CONTENT-TYPE"])
        headers.set("content-type", "application/json")
        val second = headers.toMap()
        headers.add("Set-Cookie", "a=1")
        headers.add("set-cookie", "b=2")
        val third = headers.toMap()
        headers.clear()
        assertEquals(mapOf("Content-Type" to listOf("text/plain")), first)
        assertEquals(mapOf("content-type" to listOf("application/json")), second)
        assertEquals(listOf("a=1", "b=2"), third["Set-Cookie"])
        assertEquals(emptyMap(), headers.toMap())
        headers.set("X-New", "value")
        assertEquals(setOf("X-New"), headers.names())
    }

    @Test
    fun repeatedSingleHeaderKeepsSeparateValuesAndOriginalName() {
        val headers = response().headers
        headers.add("Set-Cookie", "a=1")
        val before = headers.toMap()
        headers.add("set-cookie", "b=2")
        assertEquals(listOf("a=1", "b=2"), headers.getAll("SET-COOKIE"))
        assertEquals(setOf("Set-Cookie"), headers.names())
        assertEquals(mapOf("Set-Cookie" to listOf("a=1")), before)
        headers.remove("SET-cookie")
        assertEquals(emptyMap(), headers.toMap())
    }

    @Test
    fun outgoingHeadDropsContentLengthWithoutChangingPublicSnapshot() {
        val live = response()
        live.headers.set("content-length", "999")
        val original = live.headers.toMap()
        assertEquals(emptyMap(), live.completeResponse().headers)
        live.headers.set("Content-Type", "text/plain")
        assertEquals(mapOf("Content-Type" to listOf("text/plain")), live.completeResponse().headers)
        assertEquals(mapOf("content-length" to listOf("999")), original)
        live.headers.remove("CONTENT-LENGTH")
        assertSame(live.headers.toMap(), live.completeResponse().headers)
    }

    @Test
    fun corsAndCookieValuesSurviveHeadConversion() {
        val live = response(mapOf("Access-Control-Allow-Origin" to listOf("https://example.test")))
        live.headers.set("Content-Type", "text/plain")
        live.headers.add("Set-Cookie", "a=1")
        live.headers.add("set-cookie", "b=2")
        live.headers.set("Content-Length", "999")
        val converted = live.completeResponse().toStreamResponse(null)
        val actual = linkedMapOf<String, MutableList<String>>()
        converted.headers.forEach { name, value ->
            actual.getOrPut(name.asStr()) { mutableListOf() }.add(value.tryToStr()!!)
        }
        assertEquals(
            mapOf(
                "content-type" to listOf("text/plain"),
                "set-cookie" to listOf("a=1", "b=2"),
                "access-control-allow-origin" to listOf("https://example.test"),
            ),
            actual,
        )
    }

    @Test
    fun mutationsMatchTheOriginalMapModel() {
        val random = Random(217)
        val headers = response().headers
        val model = linkedMapOf<String, MutableList<String>>()
        val names = listOf("Content-Type", "content-type", "Set-Cookie", "SET-COOKIE", "X-Test", "content-length")
        val snapshots = mutableListOf<Pair<Map<String, List<String>>, Map<String, List<String>>>>()
        repeat(1000) { step ->
            val name = names[random.nextInt(names.size)]
            val existing = model.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            when (random.nextInt(5)) {
                0, 1 -> {
                    headers.set(name, "$step")
                    existing?.let(model::remove)
                    model[name] = mutableListOf("$step")
                }
                2 -> {
                    headers.add(name, "$step")
                    model.getOrPut(existing ?: name) { mutableListOf() }.add("$step")
                }
                3 -> {
                    headers.remove(name)
                    existing?.let(model::remove)
                }
                else -> {
                    headers.clear()
                    model.clear()
                }
            }
            assertEquals(model, headers.toMap(), "step $step")
            assertEquals(model.keys.toList(), headers.names().toList(), "header order at $step")
            for (key in names) {
                val expected = model.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
                assertEquals(expected?.firstOrNull(), headers[key])
                assertEquals(expected ?: emptyList(), headers.getAll(key))
                assertEquals(expected != null, headers.contains(key))
            }
            if (step % 25 == 0) snapshots += headers.toMap() to model.mapValues { it.value.toList() }
        }
        snapshots.forEach { (actual, expected) -> assertEquals(expected, actual) }
    }
}
