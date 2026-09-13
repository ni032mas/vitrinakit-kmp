package ru.vitrina.sdk.http

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod

/**
 * Ktor-backed default transport used by the VitrinaKit SDK facade.
 */
class KtorVitrinaHttpClient(
    private val httpClient: HttpClient = HttpClient(),
) : VitrinaHttpClient {
    /**
     * Sends the SDK request through Ktor and returns a raw SDK response.
     */
    override suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse {
        val response = httpClient.request(request.url) {
            method = request.method.toKtor()
            headers {
                request.headers.forEach { (name, value) -> append(name, value) }
            }
            request.body?.let { setBody(it) }
        }
        return VitrinaHttpResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
        )
    }
}

private fun VitrinaHttpMethod.toKtor(): HttpMethod = when (this) {
    VitrinaHttpMethod.GET -> HttpMethod.Get
    VitrinaHttpMethod.POST -> HttpMethod.Post
    VitrinaHttpMethod.DELETE -> HttpMethod.Delete
}
