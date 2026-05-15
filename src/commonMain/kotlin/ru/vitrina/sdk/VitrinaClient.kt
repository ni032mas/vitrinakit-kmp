package ru.vitrina.sdk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpMethod
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.model.CheckoutSession
import ru.vitrina.sdk.model.Paywall
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.VitrinaEnvironment
import ru.vitrina.sdk.model.VitrinaError
import ru.vitrina.sdk.model.VitrinaResult

/**
 * Configuration required to access the VitrinaKit public SDK API.
 *
 * @property appId Public app identifier from the VitrinaKit dashboard.
 * @property publishableKey SDK-safe publishable key. Never use a secret API key in a mobile app.
 * @property baseUrl VitrinaKit API base URL, for example `https://api.vitrinakit.ru`.
 * @property environment Target VitrinaKit environment.
 */
data class VitrinaConfig(
    /** Public app identifier from the VitrinaKit dashboard. */
    val appId: String,
    /** SDK-safe publishable key. Never use a secret API key in a mobile app. */
    val publishableKey: String,
    /** VitrinaKit API base URL, for example `https://api.vitrinakit.ru`. */
    val baseUrl: String,
    /** Target VitrinaKit environment. */
    val environment: VitrinaEnvironment,
)

/**
 * End-user context sent with SDK API requests.
 *
 * @property externalUserId Stable user identifier from the integrating product.
 * @property attributes Optional targeting attributes used by paywall placement logic.
 */
data class UserContext(
    /** Stable user identifier from the integrating product. */
    val externalUserId: String,
    /** Optional targeting attributes used by paywall placement logic. */
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Request for creating a hosted checkout session.
 *
 * @property externalUserId Stable user identifier from the integrating product.
 * @property productId Server-owned product identifier returned by a paywall response.
 * @property priceId Server-owned price identifier returned by a paywall response.
 * @property returnUrl URL that receives the user after provider checkout.
 */
@Serializable
data class CheckoutSessionRequest(
    /** Stable user identifier from the integrating product. */
    @SerialName("external_user_id")
    val externalUserId: String,
    /** Server-owned product identifier returned by a paywall response. */
    @SerialName("product_id")
    val productId: String,
    /** Server-owned price identifier returned by a paywall response. */
    @SerialName("price_id")
    val priceId: String,
    /** URL that receives the user after provider checkout. */
    @SerialName("return_url")
    val returnUrl: String,
)

/**
 * Main client for the VitrinaKit public SDK API.
 *
 * The client only uses publishable SDK keys and must never receive backend secret keys or provider credentials.
 */
class VitrinaClient(
    private val config: VitrinaConfig,
    private val httpClient: VitrinaHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val fallbackPaywalls = mutableMapOf<String, Paywall>()

    /**
     * Fetches a paywall placement and its server-owned product and price options.
     */
    suspend fun fetchPaywall(
        placementKey: String,
        userContext: UserContext,
    ): VitrinaResult<Paywall> = request(
        method = VitrinaHttpMethod.GET,
        path = paywallPath(placementKey = placementKey, userContext = userContext),
        body = null,
        decode = { payload -> json.decodeFromString<Paywall>(payload) },
        errorMapper = ::paywallError,
    )

    /**
     * Creates a hosted checkout session for a product and price returned by a paywall response.
     */
    suspend fun createCheckoutSession(request: CheckoutSessionRequest): VitrinaResult<CheckoutSession> = request(
        method = VitrinaHttpMethod.POST,
        path = "/api/v1/checkout/sessions",
        body = json.encodeToString(request),
        decode = { payload -> json.decodeFromString<CheckoutSession>(payload) },
        errorMapper = ::checkoutError,
    )

    /**
     * Refreshes the current subscription and entitlement state for an external user.
     */
    suspend fun refreshSubscriber(externalUserId: String): VitrinaResult<SubscriberState> = request(
        method = VitrinaHttpMethod.GET,
        path = "/api/v1/subscriber/${encodePathSegment(externalUserId)}",
        body = null,
        decode = { payload -> json.decodeFromString<SubscriberState>(payload) },
        errorMapper = ::subscriberError,
    )

    /**
     * Stores a fallback paywall that can be used when the network or provider is unavailable.
     */
    fun cacheFallbackPaywall(placementKey: String, paywall: Paywall) {
        fallbackPaywalls[normalizePlacementKey(placementKey)] = paywall
    }

    /**
     * Returns a previously cached fallback paywall for the placement key, if one exists.
     */
    fun loadFallbackPaywall(placementKey: String): Paywall? = fallbackPaywalls[normalizePlacementKey(placementKey)]

    private suspend fun <T> request(
        method: VitrinaHttpMethod,
        path: String,
        body: String?,
        decode: (String) -> T,
        errorMapper: (Int, String) -> VitrinaError,
    ): VitrinaResult<T> {
        val configurationError = validateConfig()
        if (configurationError != null) {
            return VitrinaResult.Failure(configurationError)
        }

        val response = runCatching {
            httpClient.send(
                VitrinaHttpRequest(
                    method = method,
                    url = config.baseUrl.trimEnd('/') + path,
                    path = path,
                    headers = authHeaders(),
                    body = body,
                ),
            )
        }.getOrElse { error ->
            return VitrinaResult.Failure(
                VitrinaError.Network(error.message ?: "Network request failed."),
            )
        }

        if (response.statusCode !in SuccessStatusRange) {
            return VitrinaResult.Failure(errorMapper(response.statusCode, response.body))
        }

        return runCatching { VitrinaResult.Success(decode(response.body)) }
            .getOrElse { error ->
                VitrinaResult.Failure(VitrinaError.Network(error.message ?: "Response decoding failed."))
            }
    }

    private fun validateConfig(): VitrinaError.Configuration? = when {
        config.appId.isBlank() -> VitrinaError.Configuration("appId is required.")
        config.publishableKey.isBlank() -> VitrinaError.Configuration("publishableKey is required.")
        config.baseUrl.isBlank() -> VitrinaError.Configuration("baseUrl is required.")
        else -> null
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "PublishableKey ${config.publishableKey}",
        "Content-Type" to "application/json",
        "X-Vitrina-App-Id" to config.appId,
        "X-Vitrina-Environment" to config.environment.name,
    )

    private fun paywallPath(placementKey: String, userContext: UserContext): String {
        val normalizedPlacement = normalizePlacementKey(placementKey)
        val query = buildList {
            add("external_user_id=${encodeQueryValue(userContext.externalUserId)}")
            userContext.attributes.forEach { (key, value) ->
                add("${encodeQueryValue(key)}=${encodeQueryValue(value)}")
            }
        }.joinToString(separator = "&")
        return "/api/v1/paywall/${encodePathSegment(normalizedPlacement)}?$query"
    }

    private fun paywallError(statusCode: Int, body: String): VitrinaError = when (statusCode) {
        HttpStatusUnauthorized,
        HttpStatusForbidden,
        -> VitrinaError.Auth(body)

        HttpStatusNotFound -> VitrinaError.Configuration(body)
        else -> VitrinaError.Network(body)
    }

    private fun checkoutError(statusCode: Int, body: String): VitrinaError = when (statusCode) {
        HttpStatusUnauthorized,
        HttpStatusForbidden,
        -> VitrinaError.Auth(body)

        HttpStatusConflict -> VitrinaError.Provider(body)
        else -> VitrinaError.Network(body)
    }

    private fun subscriberError(statusCode: Int, body: String): VitrinaError = when (statusCode) {
        HttpStatusUnauthorized,
        HttpStatusForbidden,
        -> VitrinaError.Auth(body)

        HttpStatusNotFound -> VitrinaError.Subscription(body)
        else -> VitrinaError.Network(body)
    }

    private fun normalizePlacementKey(value: String): String = value.trim().lowercase()

    private fun encodePathSegment(value: String): String = encodeQueryValue(value)

    private fun encodeQueryValue(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            if (byte.isUrlSafeAscii()) {
                append(byte.toInt().toChar())
            } else {
                append('%')
                append(
                    (byte.toInt() and ByteMask)
                        .toString(radix = HexRadix)
                        .padStart(length = HexWidth, padChar = '0')
                        .uppercase(),
                )
            }
        }
    }

    private fun Byte.isUrlSafeAscii(): Boolean {
        val value = toInt() and ByteMask
        return value in LowercaseAsciiRange ||
            value in UppercaseAsciiRange ||
            value in DigitAsciiRange ||
            value == HyphenAscii ||
            value == UnderscoreAscii ||
            value == DotAscii
    }
}

private val SuccessStatusRange = 200..299
private const val HttpStatusUnauthorized = 401
private const val HttpStatusForbidden = 403
private const val HttpStatusNotFound = 404
private const val HttpStatusConflict = 409
private val LowercaseAsciiRange = 'a'.code..'z'.code
private val UppercaseAsciiRange = 'A'.code..'Z'.code
private val DigitAsciiRange = '0'.code..'9'.code
private const val HyphenAscii = 45
private const val UnderscoreAscii = 95
private const val DotAscii = 46
private const val ByteMask = 0xFF
private const val HexRadix = 16
private const val HexWidth = 2
