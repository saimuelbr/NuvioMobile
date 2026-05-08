package com.nuvio.app.features.plugins.engine

import co.touchlab.kermit.Logger
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.select.Elements
import com.nuvio.app.features.plugins.bindings.PluginRuntimeBindings
import com.nuvio.app.features.plugins.domain.engine.PolyfillInjector
import com.nuvio.app.features.plugins.domain.engine.PluginExecutionEngine
import com.nuvio.app.features.plugins.domain.model.PluginRuntimeResult
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.text.Charsets

private const val PLUGIN_TIMEOUT_MS = 60_000L
private const val MAX_FETCH_RESPONSE_BYTES = 256 * 1024
private const val MAX_FETCH_BODY_CHARS = 256 * 1024
private const val MAX_FETCH_HEADER_VALUE_CHARS = 8 * 1024
private const val FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"

private val containsRegex = Regex(""":contains\(["']([^"']+)["']\)""")

class AndroidQuickJsEngine(
    private val logger: Logger = Logger.withTag("AndroidQuickJsEngine"),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val httpClient: OkHttpClient = PluginRuntimeBindings.okHttpClient
) : PluginExecutionEngine {

    override suspend fun executeScraper(
        code: String,
        scraperName: String,
        params: Map<String, Any>,
        timeoutMs: Long // Ignorado, usa PLUGIN_TIMEOUT_MS
    ): Result<List<PluginRuntimeResult>> {
        return try {
            val results = withTimeout(PLUGIN_TIMEOUT_MS) {
                executeScraperInternal(code, scraperName, params)
            }
            Result.success(results)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(TimeoutException("Script execution timed out after ${PLUGIN_TIMEOUT_MS}ms"))
        } catch (e: Exception) {
            logger.e("Unexpected error during scraper execution '$scraperName'", e)
            Result.failure(e)
        }
    }

    private suspend fun executeScraperInternal(
        code: String,
        scraperName: String,
        params: Map<String, Any>
    ): List<PluginRuntimeResult> {
        val documentCache = ConcurrentHashMap<String, Document>()
        val elementCache = ConcurrentHashMap<String, Element>()
        val inFlightCalls = ConcurrentHashMap.newKeySet<Call>()

        var resultJson = "[]"

        val parentDispatcher = (coroutineContext[ContinuationInterceptor] as? kotlinx.coroutines.CoroutineDispatcher)
            ?: kotlinx.coroutines.Dispatchers.Default

        quickJs(parentDispatcher) {
            // --- Bindings ksoup ---
            define("__cheerio_load") function@{ args ->
                val html = args.getOrNull(0)?.toString() ?: ""
                val docId = UUID.randomUUID().toString()
                val doc = Ksoup.parse(html)
                documentCache[docId] = doc
                docId
            }

            define("__cheerio_select") function@{ args ->
                val docId = args.getOrNull(0)?.toString() ?: ""
                var selector = args.getOrNull(1)?.toString() ?: ""
                val doc = documentCache[docId] ?: return@function "[]"
                try {
                    selector = selector.replace(containsRegex, ":contains($1)")
                    val elements = if (selector.isEmpty()) Elements() else doc.select(selector)
                    val ids = elements.mapIndexed { index, el ->
                        val elId = "$docId:$index:${el.hashCode()}"
                        elementCache[elId] = el
                        elId
                    }
                    "[${ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]"
                } catch (e: Exception) { "[]" }
            }

            define("__cheerio_find") function@{ args ->
                val docId = args.getOrNull(0)?.toString() ?: ""
                val elementId = args.getOrNull(1)?.toString() ?: ""
                var selector = args.getOrNull(2)?.toString() ?: ""
                val element = elementCache[elementId] ?: return@function "[]"
                try {
                    selector = selector.replace(containsRegex, ":contains($1)")
                    val elements = element.select(selector)
                    val ids = elements.mapIndexed { index, el ->
                        val elId = "$docId:find:$index:${el.hashCode()}"
                        elementCache[elId] = el
                        elId
                    }
                    "[${ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]"
                } catch (e: Exception) { "[]" }
            }

            define("__cheerio_text") function@{ args ->
                val elementIds = args.getOrNull(1)?.toString() ?: ""
                val ids = elementIds.split(",").filter { it.isNotEmpty() }
                val texts = ids.mapNotNull { id -> elementCache[id]?.text() }
                texts.joinToString(" ")
            }

            define("__cheerio_html") function@{ args ->
                val docId = args.getOrNull(0)?.toString() ?: ""
                val elementId = args.getOrNull(1)?.toString() ?: ""
                if (elementId.isEmpty()) documentCache[docId]?.html() ?: ""
                else elementCache[elementId]?.html() ?: ""
            }

            define("__cheerio_inner_html") function@{ args ->
                val elementId = args.getOrNull(1)?.toString() ?: ""
                elementCache[elementId]?.html() ?: ""
            }

            define("__cheerio_attr") function@{ args ->
                val elementId = args.getOrNull(1)?.toString() ?: ""
                val attrName = args.getOrNull(2)?.toString() ?: ""
                val value = elementCache[elementId]?.attr(attrName)
                if (value.isNullOrEmpty()) "__UNDEFINED__" else value
            }

            define("__cheerio_next") function@{ args ->
                val docId = args.getOrNull(0)?.toString() ?: ""
                val elementId = args.getOrNull(1)?.toString() ?: ""
                val el = elementCache[elementId] ?: return@function "__NONE__"
                val next = el.nextElementSibling() ?: return@function "__NONE__"
                val nextId = "$docId:next:${next.hashCode()}"
                elementCache[nextId] = next
                nextId
            }

            define("__cheerio_prev") function@{ args ->
                val docId = args.getOrNull(0)?.toString() ?: ""
                val elementId = args.getOrNull(1)?.toString() ?: ""
                val el = elementCache[elementId] ?: return@function "__NONE__"
                val prev = el.previousElementSibling() ?: return@function "__NONE__"
                val prevId = "$docId:prev:${prev.hashCode()}"
                elementCache[prevId] = prev
                prevId
            }

            // --- Capture result ---
            define("__capture_result") function@{ args ->
                resultJson = args.getOrNull(0)?.toString() ?: "[]"
                null
            }

            // --- Native fetch bridge ---
            define("__native_fetch") function@{ args ->
                val url = args.getOrNull(0)?.toString() ?: ""
                val method = args.getOrNull(1)?.toString() ?: "GET"
                val headersJson = args.getOrNull(2)?.toString() ?: "{}"
                val body = args.getOrNull(3)?.toString() ?: ""
                try {
                    performNativeFetch(url, method, headersJson, body, inFlightCalls)
                } catch (t: Throwable) {
                    logger.e("Fetch bridge error for $method $url: ${t.message}")
                    json.encodeToString(
                        buildJsonObject {
                            put("ok", JsonPrimitive(false))
                            put("status", JsonPrimitive(0))
                            put("statusText", JsonPrimitive(t.message ?: "Fetch failed"))
                            put("url", JsonPrimitive(url))
                            put("body", JsonPrimitive(""))
                            put("headers", JsonObject(emptyMap()))
                        }
                    )
                }
            }

            // --- Parse URL ---
            define("__parse_url") function@{ args ->
                parseUrl(args.getOrNull(0)?.toString() ?: "")
            }

            // --- Console ---
            define("console") {
                function("log") { args -> logger.d("[JS-$scraperName] ${args.joinToString(" ")}"); null }
                function("error") { args -> logger.e("[JS-$scraperName] ${args.joinToString(" ")}"); null }
                function("warn") { args -> logger.w("[JS-$scraperName] ${args.joinToString(" ")}"); null }
                function("info") { args -> logger.i("[JS-$scraperName] ${args.joinToString(" ")}"); null }
                function("debug") { args -> logger.d("[JS-$scraperName] ${args.joinToString(" ")}"); null }
            }

            // --- Polyfills ---
            val settingsJson = json.encodeToString(params)
            eval(PolyfillInjector.buildPolyfillCode(scraperName, settingsJson))

            // --- Crypto-JS ---
            loadCryptoJsSourceOrNull()?.let { eval(it) }

            // --- Scraper Execution ---
            val wrappedCode = """
                var module = { exports: {} };
                var exports = module.exports;
                (function() { $code })();
            """.trimIndent()
            eval(wrappedCode)

            // --- Call getStreams ---
            val callCode = """
                (async function() {
                    try {
                        var getStreams = module.exports.getStreams || globalThis.getStreams;
                        if (!getStreams) {
                            console.error("getStreams not found");
                            __capture_result(JSON.stringify([]));
                            return;
                        }
                        var result = await getStreams(${params.map { "\"${it.value}\"" }.joinToString(", ")});
                        __capture_result(JSON.stringify(result || []));
                    } catch (e) {
                        console.error("getStreams error:", e.message || e);
                        __capture_result(JSON.stringify([]));
                    }
                })();
            """.trimIndent()
            eval(callCode)
        }

        documentCache.clear()
        elementCache.clear()
        inFlightCalls.forEach { call -> call.cancel() }
        inFlightCalls.clear()

        return parseJsonResults(resultJson)
    }

    private fun performNativeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        inFlightCalls: MutableSet<Call>
    ): String {
        logger.d("Fetch: $method $url")
        return try {
            val headers = mutableMapOf<String, String>()
            try {
                val headersMap = json.decodeFromString<Map<String, String>>(headersJson)
                headersMap.forEach { (k, v) ->
                    if (k.isNotBlank() && !k.equals("Accept-Encoding", ignoreCase = true)) {
                        headers[k] = v
                    }
                }
            } catch (_: Exception) {}

            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }

            val requestBuilder = Request.Builder().url(url).headers(Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
            val normalizedMethod = method.uppercase()
            when {
                normalizedMethod in listOf("POST", "PUT", "PATCH") -> {
                    val contentType = headers["Content-Type"] ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
                    requestBuilder.method(normalizedMethod, body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType()))
                }
                normalizedMethod == "DELETE" -> requestBuilder.delete()
                else -> requestBuilder.get()
            }

            val request = requestBuilder.build()
            val call = httpClient.newCall(request)
            inFlightCalls.add(call)

            try {
                val response = call.execute()
                response.use { httpResponse ->
                    val contentEncoding = httpResponse.header("Content-Encoding")?.lowercase()?.trim()
                    val decodedBytes = try {
                        val stream = httpResponse.body?.byteStream() ?: return@use buildFetchResult(false, 0, "", "", emptyMap(), false)
                        val decodeStream = when (contentEncoding) {
                            "gzip" -> GZIPInputStream(stream)
                            "deflate" -> InflaterInputStream(stream)
                            else -> stream
                        }
                        decodeStream.use { readAtMostBytes(it, MAX_FETCH_RESPONSE_BYTES) }
                    } catch (e: Exception) {
                        logger.w("Failed to read/decode response for $url: ${e.message}")
                        BoundedReadResult(ByteArray(0), false)
                    }

                    val charset = httpResponse.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val responseBody = decodeBodyToSafeString(decodedBytes.bytes, charset)
                    val responseHeaders = mutableMapOf<String, String>()
                    httpResponse.headers.forEach { (name, value) ->
                        responseHeaders[name.lowercase()] = truncateString(value, MAX_FETCH_HEADER_VALUE_CHARS)
                    }

                    buildFetchResult(
                        httpResponse.isSuccessful,
                        httpResponse.code,
                        httpResponse.message,
                        httpResponse.request.url.toString(),
                        responseBody,
                        responseHeaders,
                        decodedBytes.truncated
                    )
                }
            } finally {
                inFlightCalls.remove(call)
            }
        } catch (e: Exception) {
            logger.e("Fetch error: ${e.message}")
            buildFetchResult(false, 0, e.message ?: "Fetch failed", url, "", emptyMap(), false)
        }
    }

    private fun buildFetchResult(
        ok: Boolean,
        status: Int,
        statusText: String,
        url: String,
        body: String,
        headers: Map<String, String>,
        truncated: Boolean
    ): String = json.encodeToString(
        buildJsonObject {
            put("ok", JsonPrimitive(ok))
            put("status", JsonPrimitive(status))
            put("statusText", JsonPrimitive(statusText))
            put("url", JsonPrimitive(url))
            put("body", JsonPrimitive(body))
            put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
            put("truncated", JsonPrimitive(truncated))
        }
    )

    private data class BoundedReadResult(val bytes: ByteArray, val truncated: Boolean)

    private fun truncateString(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val end = maxChars - FETCH_TRUNCATION_SUFFIX.length
        if (end <= 0) return FETCH_TRUNCATION_SUFFIX.take(maxChars)
        return value.substring(0, end) + FETCH_TRUNCATION_SUFFIX
    }

    private fun decodeBodyToSafeString(bytes: ByteArray, charset: java.nio.charset.Charset): String {
        val decoded = try { String(bytes, charset) } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
        return truncateString(decoded, MAX_FETCH_BODY_CHARS)
    }

    private fun readAtMostBytes(stream: InputStream, maxBytes: Int): BoundedReadResult {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = maxBytes
        var truncated = false
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        if (remaining == 0) {
            truncated = stream.read() != -1
        }
        return BoundedReadResult(out.toByteArray(), truncated)
    }

    private fun parseUrl(urlString: String): String = json.encodeToString(
        buildJsonObject {
            try {
                val url = URL(urlString)
                put("protocol", JsonPrimitive("${url.protocol}:"))
                put("host", JsonPrimitive(if (url.port > 0) "${url.host}:${url.port}" else url.host))
                put("hostname", JsonPrimitive(url.host))
                put("port", JsonPrimitive(if (url.port > 0) url.port.toString() else ""))
                put("pathname", JsonPrimitive(url.path ?: "/"))
                put("search", JsonPrimitive(if (url.query != null) "?${url.query}" else ""))
                put("hash", JsonPrimitive(if (url.ref != null) "#${url.ref}" else ""))
            } catch (_: Exception) {
                put("protocol", JsonPrimitive(""))
                put("host", JsonPrimitive(""))
                put("hostname", JsonPrimitive(""))
                put("port", JsonPrimitive(""))
                put("pathname", JsonPrimitive("/"))
                put("search", JsonPrimitive(""))
                put("hash", JsonPrimitive(""))
            }
        }
    )

    private fun loadCryptoJsSourceOrNull(): String? {
        return try {
            val context = PluginRuntimeBindings.appContext
            context.assets.open("plugins/crypto-js.min.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logger.w("Failed to load crypto-js.min.js from assets", e)
            null
        }
    }

    private fun parseJsonResults(jsonStr: String): List<PluginRuntimeResult> {
        return try {
            val list = json.decodeFromString<JsonArray>(jsonStr)
            list.mapNotNull { item ->
                if (item !is JsonObject) return@mapNotNull null
                val urlValue = item["url"]
                val url = when (urlValue) {
                    is JsonPrimitive -> urlValue.contentOrNull?.takeIf { it.isNotBlank() && !it.contains("[object") }
                    is JsonObject -> (urlValue["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    else -> null
                } ?: return@mapNotNull null

                val headersValue = item["headers"]
                val headers: Map<String, String>? = when (headersValue) {
                    is JsonObject -> headersValue.entries
                        .filter { it.key.isNotBlank() && it.value is JsonPrimitive }
                        .associate { it.key to (it.value as JsonPrimitive).content }
                        .takeIf { it.isNotEmpty() }
                    else -> null
                }

                PluginRuntimeResult(
                    url = url,
                    title = (item["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") }
                        ?: (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    name = (item["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    quality = (item["quality"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    size = (item["size"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    language = (item["language"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    provider = (item["provider"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    type = (item["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    seeders = (item["seeders"] as? JsonPrimitive)?.intOrNull,
                    peers = (item["peers"] as? JsonPrimitive)?.intOrNull,
                    infoHash = (item["infoHash"] as? JsonPrimitive)?.contentOrNull?.takeIf { !it.contains("[object") },
                    headers = headers ?: emptyMap()
                )
            }.filter { it.url.isNotBlank() }
        } catch (e: Exception) {
            logger.e("Failed to parse results: ${e.message}")
            emptyList()
        }
    }

    override fun cancelActiveJobs() {
        // QuickJS doesn't support direct cancellation
        logger.d("Canceling active jobs...")
    }
}

class TimeoutException(message: String) : Exception(message)