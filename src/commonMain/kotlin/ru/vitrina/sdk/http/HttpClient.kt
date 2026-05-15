package ru.vitrina.sdk.http

/**
 * HTTP methods used by the VitrinaKit SDK transport layer.
 */
enum class VitrinaHttpMethod {
    /** HTTP GET request. */
    GET,
    /** HTTP POST request. */
    POST,
}

/**
 * Platform-neutral HTTP request passed to a host-provided transport.
 *
 * @property method HTTP method.
 * @property url Absolute request URL.
 * @property path API path relative to the configured VitrinaKit base URL.
 * @property headers Request headers.
 * @property body Optional JSON request body.
 */
data class VitrinaHttpRequest(
    /** HTTP method. */
    val method: VitrinaHttpMethod,
    /** Absolute request URL. */
    val url: String,
    /** API path relative to the configured VitrinaKit base URL. */
    val path: String,
    /** Request headers. */
    val headers: Map<String, String>,
    /** Optional JSON request body. */
    val body: String?,
)

/**
 * Platform-neutral HTTP response returned by a host-provided transport.
 *
 * @property statusCode HTTP response status code.
 * @property body Raw response body.
 */
data class VitrinaHttpResponse(
    /** HTTP response status code. */
    val statusCode: Int,
    /** Raw response body. */
    val body: String,
)

/**
 * Host-provided HTTP transport used by [ru.vitrina.sdk.VitrinaClient].
 */
interface VitrinaHttpClient {
    /**
     * Sends a request and returns the raw response.
     */
    suspend fun send(request: VitrinaHttpRequest): VitrinaHttpResponse
}
