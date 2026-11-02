package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.net.NetProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一条网页搜索结果。 */
data class SearchHit(val title: String, val url: String, val snippet: String, val age: String? = null)

/** 搜索失败的种类;工具按它给模型说明(见 feedback 里的 web_search.*)。 */
sealed class SearchError(message: String) : Exception(message) {
    /** 401 / 403:填的 Key 不对或没有权限。 */
    class Auth(val status: Int) : SearchError("HTTP $status")
    /** 402:免费额度用完,或请求超出免费档(比如 count 超过 50)。 */
    class Quota : SearchError("HTTP 402")
    /** 429:太频繁;[retryAfterMs] 来自 Retry-After。 */
    class RateLimited(val retryAfterMs: Long?) : SearchError("HTTP 429")
    class Network(cause: IOException) : SearchError(cause.message ?: "network")
    class Bad(detail: String) : SearchError(detail)
}

/** 网页搜索服务;任务里给了才提供 web_search。 */
interface WebSearch {
    /** 展示给模型的服务名(结果的 source)。 */
    val name: String
    suspend fun search(query: String, count: Int): List<SearchHit>
}

/**
 * You.com 网页搜索。没有 Key 时走免费的 `GET https://api.you.com/v1/agents/search`(不用账号,每天 100 次);
 * 给了 Key([apiKey],比如环境变量 YDC_API_KEY)时走 `POST https://ydc-index.io/v1/search`,带 X-API-Key。
 * 两边返回同一种 `results.web[]`。429 且 Retry-After 不长时等一次再试,其余照实报错,不换别的来源。
 */
class YouSearch(
    private val http: OkHttpClient = defaultClient(),
    private val apiKey: () -> String? = { System.getenv(KEY_ENV) },
    private val keylessUrl: HttpUrl = KEYLESS_URL.toHttpUrl(),
    private val keyedUrl: HttpUrl = KEYED_URL.toHttpUrl(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : WebSearch {
    override val name = "you.com"

    override suspend fun search(query: String, count: Int): List<SearchHit> {
        val n = count.coerceIn(1, MAX_COUNT)
        val req = request(query, n)
        var reply = send(req)
        if (reply.status == 429) {
            val wait = HttpSupport.retryAfterMs(reply.retryAfter)
            if (wait != null && wait <= MAX_RETRY_WAIT_MS) {
                sleep(wait)
                reply = send(req)
            }
        }
        return when (reply.status) {
            in 200..299 -> parse(reply.body)
            401, 403 -> throw SearchError.Auth(reply.status)
            402 -> throw SearchError.Quota()
            429 -> throw SearchError.RateLimited(HttpSupport.retryAfterMs(reply.retryAfter))
            else -> throw SearchError.Bad("HTTP ${reply.status}")
        }
    }

    internal fun request(query: String, count: Int): Request {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() }
        val b = Request.Builder().header("User-Agent", USER_AGENT).header("Accept", "application/json")
        return if (key == null) {
            b.url(keylessUrl.newBuilder().addQueryParameter("query", query).addQueryParameter("count", count.toString()).build()).get().build()
        } else {
            val body = buildJsonObject { put("query", query); put("count", count) }.toString()
            b.url(keyedUrl).header("X-API-Key", key).post(body.toRequestBody(JSON_TYPE)).build()
        }
    }

    private class Reply(val status: Int, val retryAfter: String?, val body: String)

    private suspend fun send(req: Request): Reply = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(SearchError.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val r = Reply(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                        if (cont.isActive) cont.resume(r)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(SearchError.Network(e))
                    }
                }
            }
        })
    }

    companion object {
        const val KEY_ENV = "YDC_API_KEY"
        const val KEYLESS_URL = "https://api.you.com/v1/agents/search"
        const val KEYED_URL = "https://ydc-index.io/v1/search"
        /** 两个接口一次最多 50 条;免费接口超过 50 返回 402。 */
        const val MAX_COUNT = 50
        /** 429 时最多等这么久再试一次;更长就把 Retry-After 报给模型。 */
        const val MAX_RETRY_WAIT_MS = 5_000L
        const val USER_AGENT = "Mika (+https://github.com/wangzan101/Mika)"
        private val JSON_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = NetProxy.shared.client(OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS))

        /** `results.web[]` → 结果;摘要取 description,没有时取第一段 snippet。 */
        internal fun parse(body: String): List<SearchHit> {
            val root = try { json.parseToJsonElement(body).jsonObject } catch (e: CancellationException) { throw e } catch (e: Exception) {
                throw SearchError.Bad("not json")
            }
            val web = (root["results"] as? JsonObject)?.get("web") as? JsonArray ?: return emptyList()
            return web.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val url = o.str("url") ?: return@mapNotNull null
                val snippets = (o["snippets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val snippet = o.str("description")?.takeIf { it.isNotBlank() } ?: snippets.firstOrNull().orEmpty()
                SearchHit(o.str("title").orEmpty(), url, snippet, o.str("page_age"))
            }
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    }
}
