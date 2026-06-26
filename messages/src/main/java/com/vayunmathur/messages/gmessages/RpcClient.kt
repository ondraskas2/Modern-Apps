package com.vayunmathur.messages.gmessages

import android.util.Log
import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel

/**
 * HTTP transport for the Google-Messages-for-Web RPC protocol.
 *
 * Mirrors `pkg/libgm/http.go`:
 *   - All requests are POSTs to the RPC URLs (`$rpc/.../<Method>`).
 *   - Bodies are either protobuf binary (`application/x-protobuf`) or
 *     pblite JSON-array (`application/json+protobuf`).
 *   - Every request carries the relay headers from
 *     [Endpoints] + `util/func.go.BuildRelayHeaders` (Sec-CH-UA,
 *     User-Agent, X-Goog-API-Key, etc).
 *
 * Two underlying Ktor clients exist because long-poll requests need a
 * much longer read timeout than normal RPC.
 */
class RpcClient {

    private val normal: HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 120_000  // 2 min (matches Go's http.Client{Timeout: 2 * time.Minute})
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    private val longPoll: HttpClient = HttpClient(CIO) {
        engine {
            // No request-level timeout — long polls can sit open for
            // several minutes between heartbeats.
            requestTimeout = 0
        }
        install(HttpTimeout) {
            // Total request budget: 6 minutes. Beyond that we cycle.
            requestTimeoutMillis = 6 * 60 * 1000
            connectTimeoutMillis = 15_000
            // Socket-level idle timeout above 1 minute so heartbeats
            // (every ~minute) keep the stream alive.
            socketTimeoutMillis = 90_000
        }
    }

    fun close() {
        runCatching { normal.close() }
        runCatching { longPoll.close() }
    }

    /**
     * POST [body] as binary protobuf and decode the response as the
     * template's message type. Uses the normal client.
     */
    suspend fun <T : Message> postProtobuf(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = post(url, body.toByteArray(), ContentTypes.Protobuf, longPoll = false)
        return decodeBody(resp, responseTemplate)
    }

    /**
     * POST [body] as a pblite JSON array and decode the response as the
     * template's message type. Used for RegisterRefresh.
     */
    suspend fun <T : Message> postPbLiteDecoded(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = postPbLite(url, body)
        return decodeBody(resp, responseTemplate)
    }

    /**
     * POST [body] as a pblite JSON array. Used for SendMessage and Ack.
     * Most responses we care about are empty-ish OutgoingRPCResponse
     * acks (any failure is conveyed via HTTP status).
     */
    suspend fun postPbLite(
        url: String,
        body: Message,
    ): HttpResponse {
        val json = PbLite.encode(body)
        return post(url, json.toByteArray(Charsets.UTF_8), ContentTypes.PbLite, longPoll = false)
    }

    /**
     * Open a long-poll: POSTs the request body as pblite and invokes
     * [onResponse] with the live [HttpResponse]. The body MUST be
     * consumed via the streaming channel API ([bodyAsChannel]); calling
     * [HttpResponse.bodyAsBytes] would buffer-then-block.
     *
     * The connection is held open for the duration of the callback,
     * then closed cleanly. Returns whatever [onResponse] returned.
     *
     * Implemented via `preparePost().execute { ... }` rather than
     * `request()` because the latter eagerly reads the entire response
     * body before returning — fine for short RPCs, fatal for long-poll
     * where the body is an unbounded stream of pushed events.
     */
    suspend fun <T> openLongPoll(
        url: String,
        body: Message,
        onResponse: suspend (HttpResponse) -> T,
    ): T {
        val json = PbLite.encode(body)
        val bytes = json.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "POST $url (${bytes.size} bytes, ${ContentTypes.PbLite}) [long-poll]")
        return longPoll.preparePost(url) {
            method = HttpMethod.Post
            contentType(ContentType.parse(ContentTypes.PbLite))
            applyRelayHeaders(ContentTypes.PbLite, accept = "*/*")
            setBody(bytes)
        }.execute { response ->
            onResponse(response)
        }
    }

    /**
     * GET [url] and decode the response as the template's message type. Used for the
     * messages-for-web /web/config endpoint (see [Endpoints.ConfigUrl]). Mirrors libgm
     * client.go fetchConfig: same-origin fetch headers, no x-user-agent / origin.
     */
    suspend fun <T : Message> getDecoded(
        url: String,
        responseTemplate: T,
        accept: String = "*/*",
    ): T {
        Log.d(TAG, "GET $url")
        val resp = normal.request(url) {
            method = HttpMethod.Get
            headers {
                append("sec-ch-ua", Endpoints.SecUA)
                append("x-goog-api-key", Endpoints.GoogleApiKey)
                append("sec-ch-ua-mobile", Endpoints.SecUAMobile)
                append("user-agent", Endpoints.UserAgent)
                append("sec-ch-ua-platform", "\"${Endpoints.UAPlatform}\"")
                append("accept", accept)
                append("sec-fetch-site", "same-origin")
                append("sec-fetch-mode", "cors")
                append("sec-fetch-dest", "empty")
                append("referer", "https://messages.google.com/")
                append("accept-language", "en-US,en;q=0.9")
            }
        }
        return decodeBody(resp, responseTemplate)
    }

    private suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
        longPoll: Boolean,
    ): HttpResponse {
        val client = if (longPoll) this.longPoll else normal
        Log.d(TAG, "POST $url (${body.size} bytes, $contentType)")
        return client.request(url) {
            method = HttpMethod.Post
            contentType(ContentType.parse(contentType))
            applyRelayHeaders(contentType, accept = "*/*")
            setBody(body)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyRelayHeaders(
        contentType: String,
        accept: String,
    ) {
        // Port of util.BuildRelayHeaders. Each `Sec-Fetch-*` header is
        // what real Chrome sends for the cross-site fetch; Google's
        // anti-abuse layer reads these to distinguish browser traffic
        // from random clients.
        headers {
            append("sec-ch-ua", Endpoints.SecUA)
            append("x-user-agent", Endpoints.XUserAgent)
            append("x-goog-api-key", Endpoints.GoogleApiKey)
            append("sec-ch-ua-mobile", Endpoints.SecUAMobile)
            append("user-agent", Endpoints.UserAgent)
            append("sec-ch-ua-platform", "\"${Endpoints.UAPlatform}\"")
            append("accept", accept)
            append("origin", "https://messages.google.com")
            append("sec-fetch-site", "cross-site")
            append("sec-fetch-mode", "cors")
            append("sec-fetch-dest", "empty")
            append("referer", "https://messages.google.com/")
            append("accept-language", "en-US,en;q=0.9")
        }
    }

    private suspend fun <T : Message> decodeBody(resp: HttpResponse, template: T): T {
        require(resp.status.value in 200..299) {
            "HTTP ${resp.status.value} ${resp.status.description}"
        }
        val ct = resp.headers["Content-Type"].orEmpty().lowercase()
        val bytes = resp.bodyAsBytes()
        @Suppress("UNCHECKED_CAST")
        return when {
            ct.contains("x-protobuf") -> template.parserForType.parseFrom(bytes) as T
            ct.contains("json") || ct.startsWith("text/plain") -> {
                val builder = template.newBuilderForType()
                PbLite.decode<T>(String(bytes, Charsets.UTF_8), builder)
            }
            else -> error("unknown response content-type: $ct")
        }
    }

    companion object {
        private const val TAG = "GMessages/RpcClient"
    }
}

/** Convenience: open a long-poll's body channel for streaming reads. */
suspend fun HttpResponse.bodyChannel(): ByteReadChannel = bodyAsChannel()
