package com.nuvio.app.features.plugins.engine

import co.touchlab.kermit.Logger
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import com.nuvio.app.features.plugins.bindings.PluginRuntimeBindings
import com.nuvio.app.features.plugins.domain.engine.PolyfillInjector
import com.nuvio.app.features.plugins.domain.engine.PluginExecutionEngine
import com.nuvio.app.features.plugins.domain.model.PluginRuntimeResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.text.Charsets
import kotlin.text.decodeToString
import kotlin.text.encodeToByteArray

private const val PLUGIN_TIMEOUT_MS = 60_000L
private const val MAX_FETCH_RESPONSE_BYTES = 256 * 1024
private const val MAX_FETCH_BODY_CHARS = 256 * 1024
private const val MAX_FETCH_HEADER_VALUE_CHARS = 8 * 1024
private const val FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"

private val containsRegex = Regex(""":contains\(["']([^"']+)["']\)""")

class IosQuickJsEngine(
    private val logger: Logger = Logger.withTag("IosQuickJsEngine"),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val httpClient: HttpClient = PluginRuntimeBindings.httpClient 
) : PluginExecutionEngine {

    override suspend fun executeScraper(
        code: String,
        scraperName: String,
        params: Map<String, Any>,
        timeoutMs: Long // Este valor é ignorado, usa o padrão da TV (60s)
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
        val documentCache = mutableMapOf<String, Document>()
        val elementCache = mutableMapOf<String, Element>()
        val inFlightCalls = mutableSetOf<HttpResponse>()

        var resultJson = "[]"

        val parentDispatcher = (coroutineContext[ContinuationInterceptor] as? kotlinx.coroutines.CoroutineDispatcher)
            ?: kotlinx.coroutines.Dispatchers.Default // fallback

        quickJs(parentDispatcher) {
            // --- Bindings JS ---
            define("__cheerio_load") function@{ args ->
                val html = args.getOrNull(0)?.toString() ?: ""
                val docId = java.util.UUID.randomUUID().toString() 
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
                    val elements = if (selector.isEmpty()) {
                        com.fleeksoft.ksoup.select.Elements()
                    } else {
                        doc.select(selector)
                    }
                    val ids = elements.mapIndexed { index, el ->
                        val elId = "$docId:$index:${el.hashCode()}"
                        elementCache[elId] = el
                        elId
                    }
                    "[${ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }}]"
                } catch (e: Exception) {
                    "[]"
                }
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
                } catch (e: Exception) {
                    "[]"
                }
            }

            define("__cheerio_text") function@{ args ->
                val elementIds = args.getOrNull(1)?.toString() ?: ""
                val ids = elementIds.split(",").filter { it.isNotEmpty() }
                val texts = ids.mapNotNull { id ->
                    elementCache[id]?.text()
                }
                texts.joinToString(" ")
            }

            define("__cheerio_html") function@{ args ->
                val docId = args.getOrNull(0)?.toString() ?: ""
                val elementId = args.getOrNull(1)?.toString() ?: ""
                if (elementId.isEmpty()) {
                    documentCache[docId]?.html() ?: ""
                } else {
                    elementCache[elementId]?.html() ?: ""
                }
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

            // --- Binding JS to capture results ---
            define("__capture_result") function@{ args ->
                resultJson = args.getOrNull(0)?.toString() ?: "[]"
                null 
            }

            // --- Binding JS ---
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
                function("log") { args ->
                    logger.d("[JS-$scraperName] ${args.joinToString(" ")}")
                    null
                }
                function("error") { args ->
                    logger.e("[JS-$scraperName] ${args.joinToString(" ")}")
                    null
                }
                function("warn") { args ->
                    logger.w("[JS-$scraperName] ${args.joinToString(" ")}")
                    null
                }
                function("info") { args ->
                    logger.i("[JS-$scraperName] ${args.joinToString(" ")}")
                    null
                }
                function("debug") { args ->
                    logger.d("[JS-$scraperName] ${args.joinToString(" ")}")
                    null
                }
            }

            // --- Pollyfills ---
            val settingsJson = json.encodeToString(params)
            val polyfillCode = PolyfillInjector.buildPolyfillCode(scraperName, settingsJson)
            eval(polyfillCode)

            // --- Load crypto-js ---
            val cryptoJsSource = loadCryptoJsSourceOrNull()
            if (cryptoJsSource != null) {
                eval(cryptoJsSource)
            }

            // --- Execute Scraper---
            val wrappedCode = """
                var module = { exports: {} };
                var exports = module.exports;
                (function() {
                    $code
                })();
            """.trimIndent()
            eval(wrappedCode)

            // --- Call getStreams ---
            val callCode = """
                (async function() {
                    try {
                        var getStreams = module.exports.getStreams || globalThis.getStreams;
                        if (!getStreams) {
                            console.error("getStreams function not found on module.exports or globalThis");
                            __capture_result(JSON.stringify([]));
                            return;
                        }
                        console.log("Calling getStreams with params: ${params}");
                        var result = await getStreams(${params.map { "\"${it.value}\"" }.joinToString(", ")});
                        console.log("getStreams returned: " + (result ? result.length : 0) + " streams");
                        __capture_result(JSON.stringify(result || []));
                    } catch (e) {
                        console.error("getStreams error:", e.message || e, e.stack || "");
                        __capture_result(JSON.stringify([]));
                    }
                })();
            """.trimIndent()

            eval(callCode)
        }

        documentCache.clear()
        elementCache.clear()
        inFlightCalls.forEach { call -> /* null*/ }

        return parseJsonResults(resultJson)
    }

    private fun performNativeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        inFlightCalls: MutableSet<HttpResponse>
    ): String {
        logger.d("Fetch: $method $url body=${body.take(200)}")
        return try {
            val headers = mutableMapOf<String, String>()
            try {
                val headersMap = json.decodeFromString<Map<String, String>>(headersJson)
                headersMap.forEach { (k, v) ->
                    if (k != null && v != null) {
                        val key = k.toString()
                        if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                            headers[key] = v.toString()
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }

            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }

            val requestBuilder = HttpRequestBuilder().apply {
                this.url.takeFrom(Url(url))
                this.method = HttpMethod.parse(method.uppercase())
                this.headers.appendAll(headers)
                if (method.uppercase() in listOf("POST", "PUT", "PATCH")) {
                    this.body = body
                }
            }

            // Executes sync within the QuickJS context via runBlocking
            val response: HttpResponse = runBlocking {
                val resp = httpClient.request(requestBuilder)
                inFlightCalls.add(resp)
                try {
                    val responseBody = resp.body<String>()
                    val responseHeaders = mutableMapOf<String, String>()
                    resp.headers.entries().forEach { entry ->
                        responseHeaders[entry.key.lowercase()] = truncateString(entry.value.joinToString(", "), MAX_FETCH_HEADER_VALUE_CHARS)
                    }

                    val result = mapOf(
                        "ok" to resp.status.value in 200..299,
                        "status" to resp.status.value,
                        "statusText" to resp.status.description,
                        "url" to url,
                        "body" to truncateString(responseBody, MAX_FETCH_BODY_CHARS),
                        "headers" to responseHeaders,
                        "truncated" to responseBody.length > MAX_FETCH_BODY_CHARS
                    )

                    logger.d("Fetch result: ${resp.status.value} ${resp.status.description} url=$url bodyLen=${responseBody.length} bodyPreview=${responseBody.take(300)}")
                    json.encodeToString(result)
                } finally {
                    inFlightCalls.remove(resp) 
                }
            }

            response

        } catch (e: Exception) {
            logger.e("Fetch error: ${e.message}")
            json.encodeToString(mapOf(
                "ok" to false,
                "status" to 0,
                "statusText" to (e.message ?: "Fetch failed"),
                "url" to url,
                "body" to "",
                "headers" to emptyMap<String, String>()
            ))
        }
    }

    private fun truncateString(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val end = maxChars - FETCH_TRUNCATION_SUFFIX.length
        if (end <= 0) return FETCH_TRUNCATION_SUFFIX.take(maxChars)
        return value.substring(0, end) + FETCH_TRUNCATION_SUFFIX
    }

    private fun parseUrl(urlString: String): String {
        return try {
            val urlObj = Url(urlString)
            json.encodeToString(mapOf(
                "protocol" to "${urlObj.protocol.value}:",
                "host" to if (urlObj.port != null) "${urlObj.host}:${urlObj.port}" else urlObj.host,
                "hostname" to urlObj.host,
                "port" to if (urlObj.port != null) urlObj.port.toString() else "",
                "pathname" to (urlObj.encodedPath ?: "/"),
                "search" to if (urlObj.encodedQuery != null) "?${urlObj.encodedQuery}" else "",
                "hash" to if (urlObj.encodedFragment != null) "#${urlObj.encodedFragment}" else ""
            ))
        } catch (e: Exception) {
            json.encodeToString(mapOf(
                "protocol" to "",
                "host" to "",
                "hostname" to "",
                "port" to "",
                "pathname" to "/",
                "search" to "",
                "hash" to ""
            ))
        }
    }

    private fun loadCryptoJsSourceOrNull(): String? {
        return try {
            val resourcePath = NSBundle.mainBundle.pathForResource(
                "crypto-js.min", 
                ofType = "js",
                inDirectory = "plugins" 
            )
            if (resourcePath != null) {
                NSString.stringWithContentsOfFile(
                    resourcePath,
                    encoding = NSUTF8StringEncoding,
                    error = null
                )?.toString()
            } else {
                logger.w("crypto-js.min.js not found in bundle under plugins/")
                null
            }
        } catch (e: Exception) {
            logger.w("Failed to load crypto-js.min.js from iOS bundle", e)
            null
        }
    }

    private fun parseJsonResults(jsonStr: String): List<PluginRuntimeResult> {
        return try {
            val results: JsonArray = json.decodeFromString(jsonStr)
            results.mapNotNull { item ->
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

// Exceção personalizada para timeout
class TimeoutException(message: String) : Exception(message)