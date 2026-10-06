package com.atlas.sdk

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI

/** A single HTTP request, transport-agnostic. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

/** A single HTTP response. `headers` are lower-cased keys → the raw values. */
data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    /** All `Set-Cookie` values, however the server cased the header. */
    fun setCookies(): List<String> =
        headers.entries.firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }?.value ?: emptyList()
}

/**
 * The seam between the client and the network. `AtlasClient` depends only on
 * this, so tests inject a fake transport and run with no network and no
 * Gradle-fetched HTTP dependency (MockWebServer et al).
 */
interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * The production transport: `HttpURLConnection` from the JDK, so the SDK carries
 * no third-party HTTP dependency. Blocking; `AtlasClient` moves it to a
 * background dispatcher.
 */
class UrlConnectionTransport(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (URI(request.url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        try {
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            // HttpURLConnection returns a null key for the status line; drop it so
            // downstream header lookups never NPE on a null key.
            val headers = connection.headerFields.entries.mapNotNull { entry ->
                entry.key?.let { it to entry.value }
            }.toMap()
            return HttpResponse(status = status, body = body, headers = headers)
        } catch (e: Exception) {
            throw AtlasException(AtlasError.Transport("We could not reach the server: ${e.message}"))
        } finally {
            connection.disconnect()
        }
    }
}
