package ru.vitrina.sdk

import kotlinx.coroutines.runBlocking
import ru.vitrina.sdk.http.KtorVitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitPaywallProduct
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.model.VitrinaResult

/**
 * Configuration for the high-level VitrinaKit SDK facade.
 */
class VitrinaKitConfig private constructor(
    internal val publicApiKey: String,
    internal val appId: String?,
    internal val httpClient: VitrinaHttpClient?,
) {
    /**
     * Builder for [VitrinaKitConfig].
     */
    class Builder(
        private val publicApiKey: String,
    ) {
        private var appId: String? = null
        private var httpClient: VitrinaHttpClient? = null

        /**
         * Sets an optional public app identifier for API versions that require it.
         */
        fun withAppId(appId: String): Builder = apply {
            this.appId = appId
        }

        /**
         * Sets a custom transport for tests or advanced integrations.
         */
        fun withHttpClient(httpClient: VitrinaHttpClient): Builder = apply {
            this.httpClient = httpClient
        }

        /**
         * Builds the facade configuration.
         */
        fun build(): VitrinaKitConfig = VitrinaKitConfig(
            publicApiKey = publicApiKey,
            appId = appId,
            httpClient = httpClient,
        )
    }
}

/**
 * Adapty-style singleton facade for VitrinaKit mobile integrations.
 */
object VitrinaKit {
    private var client: VitrinaClient? = null

    /**
     * Activates the SDK once with a public API key configuration.
     */
    fun activate(config: VitrinaKitConfig): VitrinaKitResult<Unit> {
        if (config.publicApiKey.isBlank()) {
            return VitrinaKitResult.Failure(VitrinaKitError.Configuration("publicApiKey is required."))
        }
        client = VitrinaClient(
            config = VitrinaConfig(
                appId = config.appId,
                publishableKey = config.publicApiKey,
            ),
            httpClient = config.httpClient ?: KtorVitrinaHttpClient(),
        )
        return VitrinaKitResult.Success(Unit)
    }

    /**
     * Fetches a paywall placement for the user.
     */
    suspend fun getPaywall(
        placementId: String,
        userId: String,
    ): VitrinaKitResult<VitrinaKitPaywall> = activeClient().flatMap { client ->
        client.fetchPaywall(
            placementKey = placementId,
            userContext = UserContext(externalUserId = userId),
        ).toKitResult()
    }

    /**
     * Returns the products attached to a paywall.
     */
    fun getPaywallProducts(paywall: VitrinaKitPaywall): VitrinaKitResult<List<VitrinaKitPaywallProduct>> =
        VitrinaKitResult.Success(paywall.products)

    /**
     * Creates a hosted purchase session for a paywall product.
     */
    suspend fun makePurchase(
        product: VitrinaKitPaywallProduct,
        userId: String,
        receiptEmail: String,
        returnUrl: String,
    ): VitrinaKitResult<VitrinaKitPurchase> = activeClient().flatMap { client ->
        client.createCheckoutSession(
            CheckoutSessionRequest(
                externalUserId = userId,
                productId = product.productId,
                priceId = product.priceId,
                receiptEmail = receiptEmail,
                returnUrl = returnUrl,
            ),
        ).toKitResult()
    }

    /**
     * Refreshes the subscriber profile for the user.
     */
    suspend fun getProfile(userId: String): VitrinaKitResult<VitrinaKitProfile> =
        activeClient().flatMap { client ->
            client.refreshSubscriber(externalUserId = userId).toKitResult()
        }

    /**
     * Blocking variant of [getPaywall].
     */
    fun getPaywallBlocking(
        placementId: String,
        userId: String,
    ): VitrinaKitResult<VitrinaKitPaywall> = runBlocking {
        getPaywall(placementId = placementId, userId = userId)
    }

    /**
     * Blocking variant of [makePurchase].
     */
    fun makePurchaseBlocking(
        product: VitrinaKitPaywallProduct,
        userId: String,
        receiptEmail: String,
        returnUrl: String,
    ): VitrinaKitResult<VitrinaKitPurchase> = runBlocking {
        makePurchase(product = product, userId = userId, receiptEmail = receiptEmail, returnUrl = returnUrl)
    }

    /**
     * Blocking variant of [getProfile].
     */
    fun getProfileBlocking(userId: String): VitrinaKitResult<VitrinaKitProfile> = runBlocking {
        getProfile(userId = userId)
    }

    internal fun resetForTesting() {
        client = null
    }

    private fun activeClient(): VitrinaKitResult<VitrinaClient> {
        val active = client ?: return VitrinaKitResult.Failure(
            VitrinaKitError.Configuration("VitrinaKit.activate must be called before SDK methods."),
        )
        return VitrinaKitResult.Success(active)
    }
}

private inline fun <T, R> VitrinaKitResult<T>.flatMap(transform: (T) -> VitrinaKitResult<R>): VitrinaKitResult<R> =
    when (this) {
        is VitrinaKitResult.Success -> transform(value)
        is VitrinaKitResult.Failure -> this
    }

private fun <T> VitrinaResult<T>.toKitResult(): VitrinaKitResult<T> = when (this) {
    is VitrinaResult.Success -> VitrinaKitResult.Success(value)
    is VitrinaResult.Failure -> VitrinaKitResult.Failure(error.toKitError())
}

private fun ru.vitrina.sdk.model.VitrinaError.toKitError(): VitrinaKitError = when (this) {
    is ru.vitrina.sdk.model.VitrinaError.Auth -> VitrinaKitError.Auth(message)
    is ru.vitrina.sdk.model.VitrinaError.Network -> VitrinaKitError.Network(message)
    is ru.vitrina.sdk.model.VitrinaError.Provider -> VitrinaKitError.Provider(message)
    is ru.vitrina.sdk.model.VitrinaError.Configuration -> VitrinaKitError.Configuration(message)
    is ru.vitrina.sdk.model.VitrinaError.Subscription -> VitrinaKitError.Subscription(message)
}
