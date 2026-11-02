package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.search.SearchError
import com.wangzan101.mika.core.search.SearchHit
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolTest {
    private class FakeSearch(val hits: List<SearchHit> = emptyList(), val error: SearchError? = null) : WebSearch {
        override val name = "fake"
        val asked = mutableListOf<Pair<String, Int>>()
        override suspend fun search(query: String, count: Int): List<SearchHit> {
            asked += query to count
            error?.let { throw it }
            return hits
        }
    }

    private fun call(s: WebSearch, args: String) = runBlocking {
        toolDispatcher(FakeScreen(), webSearch = s).execute(Part.ToolCall("w", WEB_SEARCH, args), ActionPass())
    }

    @Test fun offeredOnlyWithService() {
        assertTrue(WEB_SEARCH in toolDispatcher(FakeScreen(), webSearch = FakeSearch()).definitions().map { it.name })
        assertFalse(WEB_SEARCH in toolDispatcher(FakeScreen()).definitions().map { it.name })
    }

    @Test fun returnsWrappedResultsAndUntrustedObservations() {
        val s = FakeSearch(listOf(SearchHit("Title", "https://a.example/", "snippet text")))
        val r = call(s, """{"query":"  mika android  "}""")
        assertEquals(listOf("mika android" to WebSearchTool.DEFAULT_COUNT), s.asked)
        val first = (r.content["results"] as JsonArray)[0] as JsonObject
        assertEquals("https://a.example/", first["url"]!!.jsonPrimitive.content)
        assertTrue(first["snippet"]!!.jsonPrimitive.content.contains("<untrusted>"))
        assertEquals(1, r.observations.size)
    }

    @Test fun errorsBecomeFailures() {
        for ((e, code) in listOf(SearchError.Quota() to "search_quota", SearchError.Auth(401) to "search_auth",
            SearchError.RateLimited(30_000) to "search_rate_limited")) {
            val r = call(FakeSearch(error = e), """{"query":"q"}""")
            assertEquals(code, r.content["error"]!!.jsonPrimitive.content)
        }
    }
}
