package com.atlas.sdk

/**
 * An in-memory [HttpTransport]: it records every outgoing request (the
 * mutation-check surface) and replies from a FIFO queue of canned responses. No
 * network, no MockWebServer, so the suite runs on any JVM with zero fetched HTTP
 * dependencies.
 */
class FakeTransport : HttpTransport {
    val recorded = mutableListOf<HttpRequest>()
    private val queue = ArrayDeque<HttpResponse>()

    fun enqueue(status: Int, json: String, setCookie: String? = null) {
        val headers = buildMap<String, List<String>> {
            put("Content-Type", listOf("application/json"))
            if (setCookie != null) put("Set-Cookie", listOf(setCookie))
        }
        queue.addLast(HttpResponse(status, json, headers))
    }

    override fun execute(request: HttpRequest): HttpResponse {
        recorded.add(request)
        if (queue.isEmpty()) {
            throw AtlasException(AtlasError.Transport("No stub enqueued for ${request.method} ${request.url}."))
        }
        return queue.removeFirst()
    }
}
