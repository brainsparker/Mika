package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.search.SearchError
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val WEB_SEARCH = "web_search"

/**
 * 网页搜索:不开浏览器、不在搜索引擎页面上打字,直接拿到标题、网址和摘要。只读;
 * 结果是网上的内容,作为不可信的观察交给核查。只在给了搜索服务([com.wangzan101.mika.core.tool.ToolConfig.webSearch])时提供。
 */
internal object WebSearchTool : AgentTool {
    const val DEFAULT_COUNT = 8
    const val MAX_COUNT = 20
    const val QUERY_MAX = 400

    override val verb = Verb.WebSearch
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(WEB_SEARCH,
        Prompts.tool(WEB_SEARCH, "max" to MAX_COUNT),
        mapOf(
            "query" to str("What to search for, as you would type it into a search engine."),
            "count" to int("How many results (default $DEFAULT_COUNT).", 1, MAX_COUNT),
        ), listOf("query"))

    override fun available(ctx: ToolContext): Boolean = ctx.config.webSearch != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val service = ctx.config.webSearch!!
        val query = a.str("query")?.trim()?.take(QUERY_MAX)
        if (query.isNullOrEmpty()) return fail("bad_args", Feedback["web_search.empty_query"])
        val count = a.int("count", 1, MAX_COUNT) ?: DEFAULT_COUNT
        val hits = try {
            service.search(query, count)
        } catch (e: SearchError) {
            return when (e) {
                is SearchError.Auth -> fail("search_auth", Feedback["web_search.auth"])
                is SearchError.Quota -> fail("search_quota", Feedback["web_search.quota"])
                is SearchError.RateLimited -> fail("search_rate_limited",
                    Feedback.format("web_search.rate_limited", "seconds" to ((e.retryAfterMs ?: 60_000L) / 1000).coerceAtLeast(1)))
                is SearchError.Network -> fail("search_network", Feedback["web_search.network"])
                is SearchError.Bad -> fail("search_failed", Feedback["web_search.failed"])
            }
        }
        val content = okObj {
            put("query", query)
            put("source", service.name)
            put("results", JsonArray(hits.mapIndexed { i, h ->
                buildJsonObject {
                    put("n", i + 1)
                    put("title", Untrusted.wrap(h.title))
                    put("url", h.url)
                    put("snippet", Untrusted.wrap(h.snippet))
                    h.age?.let { put("age", it) }
                }
            }))
            if (hits.isEmpty()) put("message", Feedback["web_search.no_results"])
        }
        val observations = hits.map { h ->
            Observation(ObsKind.Read, "${h.title}\n${h.url}\n${h.snippet}", source = SOURCE, title = h.title, trust = Trust.Untrusted)
        }
        return ToolExecution(content, observations = observations)
    }

    private const val SOURCE = "web_search"
}
