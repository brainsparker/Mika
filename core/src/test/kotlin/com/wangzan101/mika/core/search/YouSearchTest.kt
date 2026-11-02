package com.wangzan101.mika.core.search

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class YouSearchTest {
    private lateinit var server: MockWebServer
    private val slept = mutableListOf<Long>()

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    private fun search(key: String? = null) = YouSearch(OkHttpClient(), apiKey = { key },
        keylessUrl = server.url("/v1/agents/search"), keyedUrl = server.url("/v1/search"), sleep = { slept += it })

    private val body = """{"results":{"web":[
        {"url":"https://a.example/","title":"A","description":"about a","snippets":["s1"],"page_age":"2026-10-23T15:32:38"},
        {"url":"https://b.example/","title":"B","snippets":["first","second"]},
        {"title":"no url"}
      ]},"metadata":{"query":"q"}}"""

    @Test fun keylessUsesGetWithQueryAndCount() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        val hits = search().search("kotlin coroutines", 5)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/agents/search", req.requestUrl!!.encodedPath)
        assertEquals("kotlin coroutines", req.requestUrl!!.queryParameter("query"))
        assertEquals("5", req.requestUrl!!.queryParameter("count"))
        assertNull(req.getHeader("X-API-Key"))
        assertEquals(listOf("https://a.example/", "https://b.example/"), hits.map { it.url })
        assertEquals("about a", hits[0].snippet)
        assertEquals("first", hits[1].snippet)
        assertEquals("2026-10-23T15:32:38", hits[0].age)
    }

    @Test fun keyUpgradesToPostWithHeader() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        search(key = " ydc-key ").search("q", 3)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/search", req.requestUrl!!.encodedPath)
        assertEquals("ydc-key", req.getHeader("X-API-Key"))
        val sent = req.body.readUtf8()
        assertTrue(sent, sent.contains("\"query\":\"q\"") && sent.contains("\"count\":3"))
    }

    @Test fun countIsCappedAtFifty() = runBlocking {
        server.enqueue(MockResponse().setBody(body))
        search().search("q", 500)
        assertEquals("50", server.takeRequest().requestUrl!!.queryParameter("count"))
    }

    @Test fun shortRetryAfterIsHonoredOnce() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setBody(body))
        assertEquals(2, search().search("q", 3).size)
        assertEquals(listOf(2_000L), slept)
        assertEquals(2, server.requestCount)
    }

    @Test fun longRetryAfterIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val e = failure { search().search("q", 3) }
        assertEquals(120_000L, (e as SearchError.RateLimited).retryAfterMs)
        assertTrue(slept.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun statusesMapToErrors() = runBlocking {
        for ((code, type) in listOf(401 to SearchError.Auth::class, 403 to SearchError.Auth::class,
            402 to SearchError.Quota::class, 500 to SearchError.Bad::class)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val e = failure { search().search("q", 3) }
            assertEquals("HTTP $code", type, e::class)
        }
    }

    @Test fun emptyOrOddBodies() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":{}}"""))
        assertTrue(search().search("q", 3).isEmpty())
        server.enqueue(MockResponse().setBody("<html>"))
        assertTrue(failure { search().search("q", 3) } is SearchError.Bad)
    }

    private suspend fun failure(block: suspend () -> Unit): SearchError {
        try { block() } catch (e: SearchError) { return e }
        fail("expected SearchError"); throw AssertionError()
    }
}
