@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpMethod
import ru.vitrina.sdk.http.VitrinaHttpRequest
import ru.vitrina.sdk.model.CheckoutSession
import ru.vitrina.sdk.model.Paywall
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.model.VitrinaCheckoutErrorCode
import ru.vitrina.sdk.model.VitrinaError
import ru.vitrina.sdk.model.VitrinaResult
import ru.vitrina.sdk.purchase.PurchaseApi
import ru.vitrina.sdk.purchase.PurchaseApiResult
import ru.vitrina.sdk.purchase.PurchaseAttempt
import ru.vitrina.sdk.purchase.PurchaseConfirmation
import ru.vitrina.sdk.purchase.PurchaseRestoreResponse
import ru.vitrina.sdk.purchase.SubscriberScope
import ru.vitrina.sdk.purchase.VitrinaKitProviderProof
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAttemptStatus
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseError
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseErrorCode
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseInstruction
import ru.vitrina.sdk.purchase.VitrinaKitRestorablePurchase
import ru.vitrina.sdk.purchase.VitrinaKitRestoredPurchase

/**
 * Configuration required to access the VitrinaKit public SDK API.
 *
 * @property appId Public app identifier from the VitrinaKit dashboard, when required by the API.
 * @property publishableKey SDK-safe publishable key. Never use a secret API key in a mobile app.
 */
data class VitrinaConfig(
    /** Public app identifier from the VitrinaKit dashboard, when required by the API. */
    val appId: String?,
    /** SDK-safe publishable key. Never use a secret API key in a mobile app. */
    val publishableKey: String,
) {
    internal val baseUrl: String = VitrinaKitApiBaseUrl
    internal val environment = VitrinaKitApiEnvironment
}

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
 * @property receiptEmail Email address used for checkout receipt delivery.
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
    /** Email address used for checkout receipt delivery. */
    @SerialName("receipt_email")
    val receiptEmail: String,
    /** URL that receives the user after provider checkout. */
    @SerialName("return_url")
    val returnUrl: String,
)

/**
 * Main client for the VitrinaKit public SDK API.
 *
 * The client only uses publishable SDK keys and must never receive backend secret keys or provider credentials.
 */
@OptIn(VitrinaKitPurchaseAdapterApi::class)
class VitrinaClient(
    private val config: VitrinaConfig,
    private val httpClient: VitrinaHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val fallbackPaywalls = mutableMapOf<String, Paywall>()
    private val purchaseAttemptIndex = MutableStateFlow(PurchaseAttemptIndex())

    internal val trackedPurchaseAttemptCount: Int
        get() = purchaseAttemptIndex.value.attempts.size

    internal val purchaseAttemptGeneration: Long
        get() = purchaseAttemptIndex.value.generation

    internal fun clearPurchaseAttempts() {
        purchaseAttemptIndex.update { current ->
            PurchaseAttemptIndex(generation = current.generation + 1)
        }
    }

    internal suspend fun exchangeSubscriberSession(trustedToken: String): VitrinaResult<SubscriberSession> = request(
        method = VitrinaHttpMethod.POST,
        path = "/api/v1/subscriber-sessions",
        body = json.encodeToString(SubscriberSessionExchangeRequest(token = trustedToken)),
        decode = { payload -> json.decodeFromString<SubscriberSession>(payload) },
        errorMapper = ::identityError,
    )

    internal suspend fun fetchPaywall(
        placementKey: String,
        externalUserId: String?,
        subscriberSession: String?,
    ): VitrinaResult<Paywall> = request(
        method = VitrinaHttpMethod.GET,
        path = identityPaywallPath(placementKey = placementKey, externalUserId = externalUserId),
        body = null,
        additionalHeaders = subscriberSessionHeaders(sessionToken = subscriberSession),
        decode = { payload -> decodePaywall(payload = payload) },
        errorMapper = ::paywallError,
    )

    internal suspend fun refreshSubscriber(
        externalUserId: String?,
        subscriberSession: String?,
    ): VitrinaResult<SubscriberState> = request(
        method = VitrinaHttpMethod.GET,
        path = externalUserId?.let { "/api/v1/subscriber/${encodePathSegment(it)}" }
            ?: "/api/v1/subscriber/me",
        body = null,
        additionalHeaders = subscriberSessionHeaders(sessionToken = subscriberSession),
        decode = { payload -> json.decodeFromString<SubscriberState>(payload) },
        errorMapper = ::subscriberError,
    )

    /**
     * Fetches a paywall placement and its server-owned product and price options.
     */
    @Deprecated(
        message = "Use the identity-bound VitrinaKit facade: identify(identity), then getPaywall(placementId).",
        replaceWith = ReplaceWith("VitrinaKit.getPaywall(placementKey)"),
    )
    suspend fun fetchPaywall(
        placementKey: String,
        userContext: UserContext,
    ): VitrinaResult<Paywall> = request(
        method = VitrinaHttpMethod.GET,
        path = paywallPath(placementKey = placementKey, userContext = userContext),
        body = null,
        decode = { payload -> decodePaywall(payload = payload) },
        errorMapper = ::paywallError,
    )

    /**
     * Creates a hosted checkout session for a product and price returned by a paywall response.
     */
    @Deprecated(
        message = "Use the identity-bound VitrinaKit facade and a configured hosted adapter.",
        replaceWith = ReplaceWith("VitrinaKit.purchase(product)"),
    )
    suspend fun createCheckoutSession(request: CheckoutSessionRequest): VitrinaResult<CheckoutSession> =
        createCheckoutSession(request = request, subscriberSession = null)

    internal suspend fun createCheckoutSession(
        request: CheckoutSessionRequest,
        subscriberSession: String?,
    ): VitrinaResult<CheckoutSession> = request(
        method = VitrinaHttpMethod.POST,
        path = "/api/v1/checkout/sessions",
        body = json.encodeToString(request),
        additionalHeaders = subscriberSessionHeaders(sessionToken = subscriberSession),
        decode = { payload -> json.decodeFromString<CheckoutSession>(payload) },
        errorMapper = ::checkoutError,
    )

    /**
     * Refreshes the current subscription and entitlement state for an external user.
     */
    @Deprecated(
        message = "Use the identity-bound VitrinaKit facade: identify(identity), then getProfile().",
        replaceWith = ReplaceWith("VitrinaKit.getProfile()"),
    )
    suspend fun refreshSubscriber(externalUserId: String): VitrinaResult<SubscriberState> = request(
        method = VitrinaHttpMethod.GET,
        path = "/api/v1/subscriber/${encodePathSegment(externalUserId)}",
        body = null,
        decode = { payload -> json.decodeFromString<SubscriberState>(payload) },
        errorMapper = ::subscriberError,
    )

    internal suspend fun startPurchase(
        scope: SubscriberScope,
        placementId: String,
        productReference: String,
        capability: VitrinaKitPurchaseCapability,
        idempotencyKey: String,
    ): PurchaseApiResult<PurchaseAttempt> {
        val result = purchaseRequest(
            method = VitrinaHttpMethod.POST,
            path = "/api/v1/purchase-attempts",
            body = json.encodeToString(
                StartPurchaseRequest(
                    placementId = placementId,
                    productReference = productReference,
                    capability = capability,
                ),
            ),
            headers = subscriberSessionHeaders(scope.sessionToken) + (IdempotencyHeader to idempotencyKey),
            expectedStatus = HttpStatusCreated,
            decode = { payload -> json.decodeFromString<PurchaseAttemptResponse>(payload).toDomain() },
        )
        if (result is PurchaseApiResult.Success) {
            trackPurchaseAttempt(attempt = result.value, generation = scope.purchaseAttemptGeneration)
        }
        return result
    }

    internal suspend fun confirmPurchase(
        scope: SubscriberScope,
        attemptReference: String,
        idempotencyKey: String,
        proof: VitrinaKitProviderProof,
    ): PurchaseApiResult<PurchaseConfirmation> {
        val result = purchaseRequest(
            method = VitrinaHttpMethod.POST,
            path = "/api/v1/purchase-attempts/${encodePathSegment(attemptReference)}/confirm",
            body = json.encodeToString(ConfirmPurchaseRequest(proof = proof.value)),
            headers = subscriberSessionHeaders(scope.sessionToken) + (IdempotencyHeader to idempotencyKey),
            expectedStatus = HttpStatusOk,
            decode = { payload -> json.decodeFromString<PurchaseConfirmationResponse>(payload).toDomain() },
        )
        if (result is PurchaseApiResult.Failure && result.error.code == VitrinaKitPurchaseErrorCode.PURCHASE_PENDING) {
            val pendingAttempt = purchaseAttempt(
                reference = attemptReference,
                generation = scope.purchaseAttemptGeneration,
            )?.copy(
                status = VitrinaKitPurchaseAttemptStatus.PENDING,
            ) ?: return result
            trackPurchaseAttempt(attempt = pendingAttempt, generation = scope.purchaseAttemptGeneration)
            return PurchaseApiResult.Success(
                PurchaseConfirmation(attempt = pendingAttempt, pending = true, profile = null),
            )
        }
        if (result is PurchaseApiResult.Success) {
            trackPurchaseAttempt(attempt = result.value.attempt, generation = scope.purchaseAttemptGeneration)
        } else if (result is PurchaseApiResult.Failure && !result.error.isRecoverableConfirmationFailure()) {
            removePurchaseAttempt(reference = attemptReference, generation = scope.purchaseAttemptGeneration)
        }
        return result
    }

    internal suspend fun getPurchase(
        scope: SubscriberScope,
        attemptReference: String,
    ): PurchaseApiResult<PurchaseAttempt> {
        val result = purchaseRequest(
            method = VitrinaHttpMethod.GET,
            path = "/api/v1/purchase-attempts/${encodePathSegment(attemptReference)}",
            body = null,
            headers = subscriberSessionHeaders(scope.sessionToken),
            expectedStatus = HttpStatusOk,
            decode = { payload -> json.decodeFromString<PurchaseAttemptResponse>(payload).toDomain() },
        )
        if (result is PurchaseApiResult.Success) {
            trackPurchaseAttempt(attempt = result.value, generation = scope.purchaseAttemptGeneration)
        }
        return result
    }

    internal suspend fun restorePurchases(
        scope: SubscriberScope,
        purchases: List<VitrinaKitRestorablePurchase>,
        capability: VitrinaKitPurchaseCapability,
    ): PurchaseApiResult<PurchaseRestoreResponse> = purchaseRequest(
        method = VitrinaHttpMethod.POST,
        path = "/api/v1/purchases/restore",
        body = json.encodeToString(
            RestorePurchaseRequest(
                purchases = purchases.map { purchase ->
                    RestorePurchaseProofRequest(
                        placementId = purchase.placementId,
                        productReference = purchase.productReference,
                        capability = capability,
                        proof = purchase.proof.value,
                    )
                },
            ),
        ),
        headers = subscriberSessionHeaders(scope.sessionToken),
        expectedStatus = HttpStatusOk,
        decode = { payload -> json.decodeFromString<PurchaseRestoreResponseWire>(payload).toDomain() },
    )

    internal suspend fun refreshPurchaseProfile(
        scope: SubscriberScope,
    ): PurchaseApiResult<SubscriberState> = purchaseRequest(
        method = VitrinaHttpMethod.GET,
        path = "/api/v1/subscriber/me",
        body = null,
        headers = subscriberSessionHeaders(scope.sessionToken),
        expectedStatus = HttpStatusOk,
        decode = { payload -> json.decodeFromString<SubscriberState>(payload) },
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
        additionalHeaders: Map<String, String> = emptyMap(),
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
                    headers = authHeaders() + additionalHeaders,
                    body = body,
                ),
            )
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            return VitrinaResult.Failure(
                VitrinaError.Network("Network request failed."),
            )
        }

        if (response.statusCode !in SuccessStatusRange) {
            return VitrinaResult.Failure(errorMapper(response.statusCode, response.body))
        }

        return runCatching { VitrinaResult.Success(decode(response.body)) }
            .getOrElse { error ->
                VitrinaResult.Failure(VitrinaError.Network("Response decoding failed."))
            }
    }

    private suspend fun <T> purchaseRequest(
        method: VitrinaHttpMethod,
        path: String,
        body: String?,
        headers: Map<String, String>,
        expectedStatus: Int,
        decode: (String) -> T,
    ): PurchaseApiResult<T> {
        val configurationError = validateConfig()
        if (configurationError != null) {
            return PurchaseApiResult.Failure(
                VitrinaKitPurchaseError(
                    code = VitrinaKitPurchaseErrorCode.NOT_ACTIVATED,
                    message = configurationError.message,
                    retryable = false,
                    supportReference = null,
                ),
            )
        }
        val response = runCatching {
            httpClient.send(
                VitrinaHttpRequest(
                    method = method,
                    url = config.baseUrl.trimEnd('/') + path,
                    path = path,
                    headers = authHeaders() + headers,
                    body = body,
                ),
            )
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            return PurchaseApiResult.Failure(
                VitrinaKitPurchaseError(
                    code = VitrinaKitPurchaseErrorCode.NETWORK_ERROR,
                    message = "Network request failed.",
                    retryable = true,
                    supportReference = null,
                ),
            )
        }
        if (response.statusCode != expectedStatus) {
            return PurchaseApiResult.Failure(purchaseError(body = response.body, statusCode = response.statusCode))
        }
        return runCatching { PurchaseApiResult.Success(decode(response.body)) }
            .getOrElse {
                PurchaseApiResult.Failure(
                    VitrinaKitPurchaseError(
                        code = VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED,
                        message = "The server response could not be decoded.",
                        retryable = false,
                        supportReference = null,
                    ),
                )
            }
    }

    private fun validateConfig(): VitrinaError.Configuration? = when {
        config.publishableKey.isBlank() -> VitrinaError.Configuration("publishableKey is required.")
        else -> null
    }

    private fun purchaseAttempt(reference: String, generation: Long): PurchaseAttempt? {
        val current = purchaseAttemptIndex.value
        return current.attempts[reference].takeIf { current.generation == generation }
    }

    private fun removePurchaseAttempt(reference: String, generation: Long) {
        purchaseAttemptIndex.update { current ->
            if (current.generation != generation) {
                current
            } else {
                current.copy(attempts = current.attempts - reference)
            }
        }
    }

    private fun trackPurchaseAttempt(attempt: PurchaseAttempt, generation: Long) {
        purchaseAttemptIndex.update { current ->
            if (current.generation != generation) {
                current
            } else if (attempt.status.isTerminalPurchaseStatus()) {
                current.copy(attempts = current.attempts - attempt.reference)
            } else {
                current.copy(attempts = current.attempts + (attempt.reference to attempt))
            }
        }
    }

    private fun authHeaders(): Map<String, String> = buildMap {
        put("Authorization", "PublishableKey ${config.publishableKey}")
        put("Content-Type", "application/json")
        config.appId?.takeIf { it.isNotBlank() }?.let { appId ->
            put("X-Vitrina-App-Id", appId)
        }
        put("X-Vitrina-Environment", config.environment.name)
    }

    private fun subscriberSessionHeaders(sessionToken: String?): Map<String, String> =
        sessionToken?.takeIf { it.isNotBlank() }?.let { mapOf(SubscriberSessionHeader to it) }.orEmpty()

    private fun identityPaywallPath(placementKey: String, externalUserId: String?): String {
        val path = "/api/v1/paywall/${encodePathSegment(normalizePlacementKey(placementKey))}"
        return externalUserId?.let { "$path?external_user_id=${encodeQueryValue(it)}" } ?: path
    }

    private fun decodePaywall(payload: String): Paywall {
        return json.decodeFromString<Paywall>(payload)
    }

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

        HttpStatusBadRequest,
        HttpStatusConflict,
        -> checkoutErrorCode(body)?.let { code ->
            VitrinaError.Checkout(
                code = code,
                message = checkoutErrorMessage(body = body),
            )
        } ?: if (statusCode == HttpStatusConflict) {
            VitrinaError.Provider(body)
        } else {
            VitrinaError.Network(body)
        }

        else -> VitrinaError.Network(body)
    }

    private fun checkoutErrorCode(body: String): VitrinaCheckoutErrorCode? {
        val parsed = parseErrorBody(body = body) ?: return null
        val rawCode = parsed[ErrorCodeField]?.jsonPrimitive?.contentOrNull
            ?: parsed[ErrorField]?.jsonPrimitive?.contentOrNull
            ?: return null
        return runCatching {
            json.decodeFromJsonElement<VitrinaCheckoutErrorCode>(JsonPrimitive(rawCode))
        }.getOrNull()
    }

    private fun checkoutErrorMessage(body: String): String {
        val parsed = parseErrorBody(body = body)
        return parsed?.get(MessageField)?.jsonPrimitive?.contentOrNull ?: body
    }

    private fun parseErrorBody(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun subscriberError(statusCode: Int, body: String): VitrinaError = when (statusCode) {
        HttpStatusUnauthorized,
        HttpStatusForbidden,
        -> VitrinaError.Auth(body)

        HttpStatusNotFound -> VitrinaError.Subscription(body)
        else -> VitrinaError.Network(body)
    }

    private fun identityError(statusCode: Int, body: String): VitrinaError = when (statusCode) {
        HttpStatusBadRequest,
        HttpStatusUnauthorized,
        HttpStatusForbidden,
        HttpStatusConflict,
        -> VitrinaError.Auth(problemDetail(body = body))

        else -> VitrinaError.Network(problemDetail(body = body))
    }

    private fun purchaseError(body: String, statusCode: Int): VitrinaKitPurchaseError {
        val problem = runCatching { json.decodeFromString<ProblemDetailsWire>(body) }.getOrNull()
        val code = problem?.code?.let(::decodePurchaseErrorCode) ?: VitrinaKitPurchaseErrorCode.UNKNOWN
        return VitrinaKitPurchaseError(
            code = code,
            message = problem?.detail?.takeIf { it.isNotBlank() } ?: "The purchase request failed.",
            retryable = problemRetryable(problem = problem, statusCode = statusCode),
            supportReference = problem?.requestId?.takeIf { it.isNotBlank() },
        )
    }

    private fun problemRetryable(problem: ProblemDetailsWire?, statusCode: Int): Boolean {
        val metadataRetryable = runCatching {
            problem?.meta?.get(RetryableField)?.jsonPrimitive?.contentOrNull == TrueValue
        }.getOrDefault(false)
        return metadataRetryable || statusCode >= HttpStatusServerError
    }

    private fun decodePurchaseErrorCode(rawCode: String): VitrinaKitPurchaseErrorCode = runCatching {
        json.decodeFromJsonElement<VitrinaKitPurchaseErrorCode>(JsonPrimitive(rawCode))
    }.getOrDefault(VitrinaKitPurchaseErrorCode.UNKNOWN)

    private fun problemDetail(body: String): String = runCatching {
        json.decodeFromString<ProblemDetailsWire>(body).detail
    }.getOrDefault("The request failed.")

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

internal class VitrinaClientPurchaseApi(
    private val client: VitrinaClient,
) : PurchaseApi {
    override suspend fun startPurchase(
        scope: SubscriberScope,
        placementId: String,
        productReference: String,
        capability: VitrinaKitPurchaseCapability,
        idempotencyKey: String,
    ): PurchaseApiResult<PurchaseAttempt> = client.startPurchase(
        scope = scope,
        placementId = placementId,
        productReference = productReference,
        capability = capability,
        idempotencyKey = idempotencyKey,
    )

    override suspend fun confirmPurchase(
        scope: SubscriberScope,
        attemptReference: String,
        idempotencyKey: String,
        proof: VitrinaKitProviderProof,
    ): PurchaseApiResult<PurchaseConfirmation> = client.confirmPurchase(
        scope = scope,
        attemptReference = attemptReference,
        idempotencyKey = idempotencyKey,
        proof = proof,
    )

    override suspend fun getPurchase(
        scope: SubscriberScope,
        attemptReference: String,
    ): PurchaseApiResult<PurchaseAttempt> = client.getPurchase(
        scope = scope,
        attemptReference = attemptReference,
    )

    override suspend fun restorePurchases(
        scope: SubscriberScope,
        purchases: List<VitrinaKitRestorablePurchase>,
        capability: VitrinaKitPurchaseCapability,
    ): PurchaseApiResult<PurchaseRestoreResponse> = client.restorePurchases(
        scope = scope,
        purchases = purchases,
        capability = capability,
    )

    override suspend fun refreshProfile(scope: SubscriberScope): PurchaseApiResult<SubscriberState> =
        client.refreshPurchaseProfile(scope = scope)
}

private val SuccessStatusRange = 200..299
private const val HttpStatusBadRequest = 400
private const val HttpStatusOk = 200
private const val HttpStatusCreated = 201
private const val HttpStatusUnauthorized = 401
private const val HttpStatusForbidden = 403
private const val HttpStatusNotFound = 404
private const val HttpStatusConflict = 409
private const val HttpStatusServerError = 500
private const val ErrorField = "error"
private const val ErrorCodeField = "code"
private const val MessageField = "message"
private val LowercaseAsciiRange = 'a'.code..'z'.code
private val UppercaseAsciiRange = 'A'.code..'Z'.code
private val DigitAsciiRange = '0'.code..'9'.code
private const val HyphenAscii = 45
private const val UnderscoreAscii = 95
private const val DotAscii = 46
private const val ByteMask = 0xFF
private const val HexRadix = 16
private const val HexWidth = 2
private const val SubscriberSessionHeader = "Vitrina-Subscriber-Session"
private const val IdempotencyHeader = "Idempotency-Key"
private const val RetryableField = "retryable"
private const val TrueValue = "true"

private data class PurchaseAttemptIndex(
    val generation: Long = 0,
    val attempts: Map<String, PurchaseAttempt> = emptyMap(),
)

private fun VitrinaKitPurchaseError.isRecoverableConfirmationFailure(): Boolean =
    code == VitrinaKitPurchaseErrorCode.NETWORK_ERROR ||
        code == VitrinaKitPurchaseErrorCode.PROVIDER_VALIDATION_UNAVAILABLE

private fun VitrinaKitPurchaseAttemptStatus.isTerminalPurchaseStatus(): Boolean = when (this) {
    VitrinaKitPurchaseAttemptStatus.SUCCEEDED,
    VitrinaKitPurchaseAttemptStatus.CANCELLED,
    VitrinaKitPurchaseAttemptStatus.FAILED,
    VitrinaKitPurchaseAttemptStatus.DUPLICATE_COVERAGE,
    -> true

    VitrinaKitPurchaseAttemptStatus.CREATED,
    VitrinaKitPurchaseAttemptStatus.PROVIDER_READY,
    VitrinaKitPurchaseAttemptStatus.PRESENTED,
    VitrinaKitPurchaseAttemptStatus.PROOF_RECEIVED,
    VitrinaKitPurchaseAttemptStatus.VALIDATING,
    VitrinaKitPurchaseAttemptStatus.PENDING,
    -> false
}

@Serializable
internal data class SubscriberSessionExchangeRequest(val token: String) {
    override fun toString(): String = "SubscriberSessionExchangeRequest(token=<redacted>)"
}

@Serializable
internal data class SubscriberSession(
    @SerialName("session_token")
    val sessionToken: String,
    @SerialName("subscriber_id")
    val subscriberId: String,
    @SerialName("external_user_id")
    val externalUserId: String,
    @SerialName("expires_at")
    val expiresAt: String,
) {
    override fun toString(): String =
        "SubscriberSession(sessionToken=<redacted>, subscriberId=$subscriberId, " +
            "externalUserId=$externalUserId, expiresAt=$expiresAt)"
}

@Serializable
private data class StartPurchaseRequest(
    @SerialName("placement_key")
    val placementId: String,
    @SerialName("product_reference")
    val productReference: String,
    val capability: VitrinaKitPurchaseCapability,
)

@Serializable
private data class ConfirmPurchaseRequest(val proof: String) {
    override fun toString(): String = "ConfirmPurchaseRequest(proof=<redacted>)"
}

@Serializable
private data class PurchasePresentationResponse(
    @SerialName("product_id")
    val productId: String,
    @SerialName("price_id")
    val priceId: String? = null,
    @SerialName("package_name")
    val packageName: String,
    @SerialName("account_binding")
    val accountBinding: String,
)

@Serializable
private data class PurchaseAttemptResponse(
    val reference: String,
    val status: VitrinaKitPurchaseAttemptStatus,
    val reason: String? = null,
    @SerialName("expires_at")
    val expiresAt: String,
    val capability: VitrinaKitPurchaseCapability,
    val presentation: PurchasePresentationResponse,
)

private fun PurchaseAttemptResponse.toDomain(): PurchaseAttempt = PurchaseAttempt(
    reference = reference,
    status = status,
    reason = reason,
    expiresAt = expiresAt,
    capability = capability,
    instruction = VitrinaKitPurchaseInstruction(
        attemptReference = reference,
        productId = presentation.productId,
        priceId = presentation.priceId,
        packageName = presentation.packageName,
        accountBinding = presentation.accountBinding,
        expiresAt = expiresAt,
    ),
)

@Serializable
private data class PurchaseConfirmationResponse(
    val attempt: PurchaseAttemptResponse,
    val pending: Boolean,
    val profile: SubscriberState,
)

private fun PurchaseConfirmationResponse.toDomain(): PurchaseConfirmation = PurchaseConfirmation(
    attempt = attempt.toDomain(),
    pending = pending,
    profile = profile,
)

@Serializable
private data class RestorePurchaseRequest(val purchases: List<RestorePurchaseProofRequest>)

@Serializable
private data class RestorePurchaseProofRequest(
    @SerialName("placement_key")
    val placementId: String,
    @SerialName("product_reference")
    val productReference: String,
    val capability: VitrinaKitPurchaseCapability,
    val proof: String,
) {
    override fun toString(): String =
        "RestorePurchaseProofRequest(placementId=$placementId, productReference=$productReference, " +
            "capability=$capability, proof=<redacted>)"
}

@Serializable
private data class RestoredPurchaseWire(
    val reference: String,
    val status: VitrinaKitPurchaseAttemptStatus,
    val replayed: Boolean,
)

@Serializable
private data class PurchaseRestoreResponseWire(
    val purchases: List<RestoredPurchaseWire>,
    val profile: SubscriberState,
)

private fun PurchaseRestoreResponseWire.toDomain(): PurchaseRestoreResponse = PurchaseRestoreResponse(
    purchases = purchases.map { purchase ->
        VitrinaKitRestoredPurchase(
            purchaseReference = purchase.reference,
            status = purchase.status,
            replayed = purchase.replayed,
        )
    },
    profile = profile,
)

@Serializable
private data class ProblemDetailsWire(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    @SerialName("request_id")
    val requestId: String,
    val meta: JsonObject? = null,
)
