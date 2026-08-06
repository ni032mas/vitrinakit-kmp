@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import ru.vitrina.sdk.cache.SubscriberCache
import ru.vitrina.sdk.cache.SubscriberCacheKey
import ru.vitrina.sdk.http.KtorVitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.identity.VitrinaKitIdentity
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitPaywallProduct
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.model.VitrinaResult
import ru.vitrina.sdk.purchase.PurchaseCoordinator
import ru.vitrina.sdk.purchase.SubscriberScope
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseError
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseErrorCode
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResult
import ru.vitrina.sdk.purchase.VitrinaKitRestoreResult
import ru.vitrina.sdk.purchase.VitrinaKitHostedCheckoutOperation
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedMigrationRequest

/** Configuration for the high-level VitrinaKit SDK facade. */
class VitrinaKitConfig private constructor(
    internal val publicApiKey: String,
    internal val appId: String?,
    internal val httpClient: VitrinaHttpClient?,
    internal val purchaseAdapters: List<VitrinaKitPurchaseAdapter>,
    internal val hostedMigrationAdapters: List<VitrinaKitHostedMigrationAdapter>,
) {
    /** Builder for [VitrinaKitConfig]. */
    class Builder(
        private val publicApiKey: String,
    ) {
        private var appId: String? = null
        private var httpClient: VitrinaHttpClient? = null
        private val purchaseAdapters = mutableListOf<VitrinaKitPurchaseAdapter>()
        private val hostedMigrationAdapters = mutableListOf<VitrinaKitHostedMigrationAdapter>()

        /** Sets an optional public app identifier for API versions that require it. */
        fun withAppId(appId: String): Builder = apply {
            this.appId = appId
        }

        /** Sets a custom transport for tests or advanced integrations. */
        fun withHttpClient(httpClient: VitrinaHttpClient): Builder = apply {
            this.httpClient = httpClient
        }

        /** Adds the provider adapter packaged by this application artifact. */
        fun withPurchaseAdapter(adapter: VitrinaKitPurchaseAdapter): Builder = apply {
            purchaseAdapters += adapter
        }

        /** Adds the hosted adapter used by the deprecated migration facade. */
        @VitrinaKitPurchaseAdapterApi
        fun withHostedMigrationAdapter(adapter: VitrinaKitHostedMigrationAdapter): Builder = apply {
            hostedMigrationAdapters += adapter
        }

        /** Builds the facade configuration. */
        fun build(): VitrinaKitConfig = VitrinaKitConfig(
            publicApiKey = publicApiKey,
            appId = appId,
            httpClient = httpClient,
            purchaseAdapters = purchaseAdapters.toList(),
            hostedMigrationAdapters = hostedMigrationAdapters.toList(),
        )
    }
}

/** Identity-bound singleton facade for VitrinaKit mobile integrations. */
@OptIn(VitrinaKitPurchaseAdapterApi::class)
object VitrinaKit {
    private val lifecycleState = MutableStateFlow(VitrinaKitLifecycleState())

    /** Activates the SDK with one public API key and exactly one purchase adapter. */
    fun activate(config: VitrinaKitConfig): VitrinaKitResult<Unit> {
        if (config.publicApiKey.isBlank()) {
            return VitrinaKitResult.Failure(VitrinaKitError.Configuration("publicApiKey is required."))
        }
        if (config.purchaseAdapters.size + config.hostedMigrationAdapters.size != RequiredPurchaseAdapterCount) {
            return VitrinaKitResult.Failure(
                VitrinaKitError.Configuration("Exactly one purchase adapter is required."),
            )
        }
        val client = VitrinaClient(
            config = VitrinaConfig(
                appId = config.appId,
                publishableKey = config.publicApiKey,
            ),
            httpClient = config.httpClient ?: KtorVitrinaHttpClient(),
        )
        val cache = SubscriberCache()
        val nativeAdapter = config.purchaseAdapters.singleOrNull()
        val nextRuntime = VitrinaKitRuntime(
            client = client,
            cache = cache,
            coordinator = nativeAdapter?.let { adapter ->
                PurchaseCoordinator(
                    adapter = adapter,
                    api = VitrinaClientPurchaseApi(client),
                    cache = cache,
                )
            },
            hostedMigrationAdapter = config.hostedMigrationAdapters.singleOrNull(),
            appId = config.appId,
            environment = VitrinaKitApiEnvironment.name,
        )
        val previous = replaceRuntime(nextRuntime)
        previous.runtime?.closePurchaseResources()
        return VitrinaKitResult.Success(Unit)
    }

    /**
     * Replaces the current subscriber identity and returns its authoritative profile.
     *
     * Trusted tokens are exchanged for an opaque subscriber session and are never retained.
     */
    suspend fun identify(identity: VitrinaKitIdentity): VitrinaKitResult<VitrinaKitProfile> {
        val transition = beginIdentityTransition() ?: return notActivated()
        val active = transition.state.runtime ?: return notActivated()
        if (transition.hadIdentity) {
            active.closePurchaseResources()
        } else {
            active.cache.clearAll()
        }
        active.client.clearPurchaseAttempts()
        val cacheGeneration = active.cache.generation
        return when (identity) {
            is VitrinaKitIdentity.TrustedToken -> identifyTrusted(
                runtime = active,
                identity = identity,
                expectedLifecycle = transition.state,
                cacheGeneration = cacheGeneration,
            )
            is VitrinaKitIdentity.ExternalUserId -> identifyExternal(
                runtime = active,
                identity = identity,
                expectedLifecycle = transition.state,
                cacheGeneration = cacheGeneration,
            )
        }
    }

    /** Clears subscriber-scoped state and releases adapter resources. */
    fun logout(): VitrinaKitResult<Unit> {
        val previous = clearIdentity() ?: return notActivated()
        val active = previous.runtime ?: return notActivated()
        active.closePurchaseResources()
        active.cache.clearAll()
        return VitrinaKitResult.Success(Unit)
    }

    /** Fetches a paywall for the currently identified subscriber. */
    suspend fun getPaywall(placementId: String): VitrinaKitResult<VitrinaKitPaywall> {
        val current = lifecycleState.value
        val active = current.runtime ?: return notActivated()
        val bound = current.identity ?: return identityRequired()
        val cacheGeneration = active.cache.generation
        val result = active.client.fetchPaywall(
            placementKey = placementId,
            externalUserId = bound.externalUserId,
            subscriberSession = bound.sessionToken,
        ).toKitResult()
        if (result is VitrinaKitResult.Success) {
            if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
                return staleIdentityTransition()
            }
            active.cache.replacePaywallIfCurrent(
                key = bound.cacheKey,
                placementId = placementId,
                paywall = result.value,
                generation = cacheGeneration,
            )
        }
        return result
    }

    /** Returns the products attached to a paywall. */
    fun getPaywallProducts(paywall: VitrinaKitPaywall): VitrinaKitResult<List<VitrinaKitPaywallProduct>> =
        VitrinaKitResult.Success(paywall.products)

    /** Starts one provider-neutral purchase for the currently identified subscriber. */
    suspend fun purchase(product: VitrinaKitPaywallProduct): VitrinaKitPurchaseResult {
        val current = lifecycleState.value
        val active = current.runtime ?: return purchaseFailure(
            code = VitrinaKitPurchaseErrorCode.NOT_ACTIVATED,
            message = "VitrinaKit.activate must be called before SDK methods.",
        )
        val bound = current.identity ?: return purchaseFailure(
            code = VitrinaKitPurchaseErrorCode.SUBSCRIBER_AUTH_REQUIRED,
            message = "Subscriber identity is required.",
        )
        val trusted = bound as? BoundIdentity.Trusted ?: return purchaseFailure(
            code = VitrinaKitPurchaseErrorCode.IDENTITY_POLICY_MISMATCH,
            message = "Native purchase requires a trusted subscriber session.",
        )
        val cacheGeneration = active.cache.generation
        if (!claimIdentityOperation(expected = current, bound = trusted)) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
                message = "Subscriber identity changed before purchase started.",
            )
        }
        val placementId = active.cache.placementForProduct(key = trusted.cacheKey, product = product)
            ?: return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.INVALID_REQUEST,
                message = "The product must come from a VitrinaKit paywall.",
            )
        val coordinator = active.coordinator ?: return purchaseFailure(
            code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            message = "The configured adapter does not support native purchase orchestration.",
        )
        return coordinator.purchase(
            scope = trusted.scope,
            placementId = placementId,
            product = product,
            expectedCacheGeneration = cacheGeneration,
        )
    }

    /** Restores provider purchases for the currently identified subscriber. */
    suspend fun restorePurchases(): VitrinaKitRestoreResult {
        val current = lifecycleState.value
        val active = current.runtime ?: return restoreFailure(
            code = VitrinaKitPurchaseErrorCode.NOT_ACTIVATED,
            message = "VitrinaKit.activate must be called before SDK methods.",
        )
        val bound = current.identity ?: return restoreFailure(
            code = VitrinaKitPurchaseErrorCode.SUBSCRIBER_AUTH_REQUIRED,
            message = "Subscriber identity is required.",
        )
        val trusted = bound as? BoundIdentity.Trusted ?: return restoreFailure(
            code = VitrinaKitPurchaseErrorCode.IDENTITY_POLICY_MISMATCH,
            message = "Native restore requires a trusted subscriber session.",
        )
        val cacheGeneration = active.cache.generation
        if (!claimIdentityOperation(expected = current, bound = trusted)) {
            return restoreFailure(
                code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
                message = "Subscriber identity changed before restore started.",
            )
        }
        val coordinator = active.coordinator ?: return restoreFailure(
            code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            message = "The configured adapter does not support native restore orchestration.",
        )
        return coordinator.restore(
            scope = trusted.scope,
            expectedCacheGeneration = cacheGeneration,
        )
    }

    /**
     * Returns the identified subscriber profile, refreshing it from the server when requested.
     *
     * @param forceRefresh Whether to bypass the subscriber-scoped in-memory cache.
     */
    suspend fun getProfile(forceRefresh: Boolean = false): VitrinaKitResult<VitrinaKitProfile> {
        val current = lifecycleState.value
        val active = current.runtime ?: return notActivated()
        val bound = current.identity ?: return identityRequired()
        if (!forceRefresh) {
            active.cache.profile(key = bound.cacheKey)?.let { profile ->
                return VitrinaKitResult.Success(profile)
            }
        }
        val cacheGeneration = active.cache.generation
        val result = active.client.refreshSubscriber(
            externalUserId = bound.externalUserId,
            subscriberSession = bound.sessionToken,
        ).toKitResult()
        if (result is VitrinaKitResult.Success) {
            if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
                return staleIdentityTransition()
            }
            active.cache.replaceProfileIfCurrent(
                key = bound.cacheKey,
                profile = result.value,
                generation = cacheGeneration,
            )
        }
        return result
    }

    /**
     * Legacy identity-per-call paywall API.
     *
     * The [userId] parameter is ignored; call [identify] before this method.
     */
    @Deprecated(
        message = "Call identify(identity), then getPaywall(placementId). Per-call user IDs are no longer accepted.",
        replaceWith = ReplaceWith("getPaywall(placementId)"),
    )
    suspend fun getPaywall(
        placementId: String,
        userId: String,
    ): VitrinaKitResult<VitrinaKitPaywall> {
        return getPaywall(placementId = placementId)
    }

    /**
     * Legacy hosted-checkout API retained for source migration.
     *
     * Use [identify] and [purchase]. A provider-specific hosted adapter is required for hosted checkout.
     */
    @Deprecated(
        message = "Call identify(identity), then purchase(product). Hosted details move to hosted adapter configuration.",
        replaceWith = ReplaceWith("purchase(product)"),
    )
    suspend fun makePurchase(
        product: VitrinaKitPaywallProduct,
        userId: String,
        receiptEmail: String,
        returnUrl: String,
    ): VitrinaKitResult<VitrinaKitPurchase> {
        return makeHostedMigrationPurchase(product = product)
    }

    /**
     * Legacy identity-per-call profile API.
     *
     * The [userId] parameter is ignored; call [identify] before this method.
     */
    @Deprecated(
        message = "Call identify(identity), then getProfile(). Per-call user IDs are no longer accepted.",
        replaceWith = ReplaceWith("getProfile()"),
    )
    suspend fun getProfile(userId: String): VitrinaKitResult<VitrinaKitProfile> {
        return getProfile()
    }

    /** Blocking legacy variant of [getPaywall]. */
    @Deprecated(
        message = "Call identify(identity), then getPaywall(placementId) from a coroutine.",
        replaceWith = ReplaceWith("getPaywall(placementId)"),
    )
    fun getPaywallBlocking(
        placementId: String,
        userId: String,
    ): VitrinaKitResult<VitrinaKitPaywall> = runBlocking {
        getPaywall(placementId = placementId)
    }

    /** Blocking legacy variant of [makePurchase]. */
    @Deprecated(
        message = "Call identify(identity), then purchase(product) from a coroutine.",
        replaceWith = ReplaceWith("purchase(product)"),
    )
    fun makePurchaseBlocking(
        product: VitrinaKitPaywallProduct,
        userId: String,
        receiptEmail: String,
        returnUrl: String,
    ): VitrinaKitResult<VitrinaKitPurchase> = runBlocking {
        makeHostedMigrationPurchase(product = product)
    }

    /** Blocking legacy variant of [getProfile]. */
    @Deprecated(
        message = "Call identify(identity), then getProfile() from a coroutine.",
        replaceWith = ReplaceWith("getProfile()"),
    )
    fun getProfileBlocking(userId: String): VitrinaKitResult<VitrinaKitProfile> = runBlocking {
        getProfile()
    }

    internal fun resetForTesting() {
        val previous = replaceRuntime(null)
        previous.runtime?.closePurchaseResources()
    }

    private suspend fun identifyTrusted(
        runtime: VitrinaKitRuntime,
        identity: VitrinaKitIdentity.TrustedToken,
        expectedLifecycle: VitrinaKitLifecycleState,
        cacheGeneration: Long,
    ): VitrinaKitResult<VitrinaKitProfile> {
        if (identity.value.isBlank()) {
            return VitrinaKitResult.Failure(VitrinaKitError.Auth("Trusted token is required."))
        }
        val exchange = runtime.client.exchangeSubscriberSession(trustedToken = identity.value)
        val session = when (exchange) {
            is VitrinaResult.Success -> exchange.value
            is VitrinaResult.Failure -> return VitrinaKitResult.Failure(exchange.error.toKitError())
        }
        val cacheKey = SubscriberCacheKey(
            environment = runtime.environment,
            appId = runtime.appId,
            subscriberReference = session.subscriberId,
            subscriberSession = session.sessionToken,
        )
        val scope = SubscriberScope(
            cacheKey = cacheKey,
            sessionToken = session.sessionToken,
            purchaseAttemptGeneration = runtime.client.purchaseAttemptGeneration,
        )
        val profile = runtime.client.refreshSubscriber(
            externalUserId = null,
            subscriberSession = session.sessionToken,
        )
        return when (profile) {
            is VitrinaResult.Failure -> VitrinaKitResult.Failure(profile.error.toKitError())
            is VitrinaResult.Success -> {
                val bound = BoundIdentity.Trusted(
                    subscriberExternalUserId = session.externalUserId,
                    sessionToken = session.sessionToken,
                    cacheKey = cacheKey,
                    scope = scope,
                )
                bindIdentityIfCurrent(
                    runtime = runtime,
                    expectedLifecycle = expectedLifecycle,
                    cacheGeneration = cacheGeneration,
                    bound = bound,
                    profile = profile.value,
                )
            }
        }
    }

    private suspend fun identifyExternal(
        runtime: VitrinaKitRuntime,
        identity: VitrinaKitIdentity.ExternalUserId,
        expectedLifecycle: VitrinaKitLifecycleState,
        cacheGeneration: Long,
    ): VitrinaKitResult<VitrinaKitProfile> {
        if (identity.value.isBlank()) {
            return VitrinaKitResult.Failure(VitrinaKitError.Auth("External user ID is required."))
        }
        val cacheKey = SubscriberCacheKey(
            environment = runtime.environment,
            appId = runtime.appId,
            subscriberReference = identity.value,
        )
        val profile = runtime.client.refreshSubscriber(
            externalUserId = identity.value,
            subscriberSession = null,
        )
        return when (profile) {
            is VitrinaResult.Failure -> VitrinaKitResult.Failure(profile.error.toKitError())
            is VitrinaResult.Success -> {
                val bound = BoundIdentity.External(
                    externalUserId = identity.value,
                    cacheKey = cacheKey,
                )
                bindIdentityIfCurrent(
                    runtime = runtime,
                    expectedLifecycle = expectedLifecycle,
                    cacheGeneration = cacheGeneration,
                    bound = bound,
                    profile = profile.value,
                )
            }
        }
    }

    private fun bindIdentityIfCurrent(
        runtime: VitrinaKitRuntime,
        expectedLifecycle: VitrinaKitLifecycleState,
        cacheGeneration: Long,
        bound: BoundIdentity,
        profile: VitrinaKitProfile,
    ): VitrinaKitResult<VitrinaKitProfile> {
        if (runtime.cache.generation != cacheGeneration) {
            return staleIdentityTransition()
        }
        runtime.cache.replaceProfileIfCurrent(
            key = bound.cacheKey,
            profile = profile,
            generation = cacheGeneration,
        )
        val boundState = expectedLifecycle.copy(identity = bound)
        if (!lifecycleState.compareAndSet(expect = expectedLifecycle, update = boundState)) {
            runtime.cache.clearIfCurrent(key = bound.cacheKey, generation = cacheGeneration)
            return staleIdentityTransition()
        }
        return VitrinaKitResult.Success(profile)
    }

    @Suppress("DEPRECATION")
    private suspend fun makeHostedMigrationPurchase(
        product: VitrinaKitPaywallProduct,
    ): VitrinaKitResult<VitrinaKitPurchase> {
        val current = lifecycleState.value
        val active = current.runtime ?: return notActivated()
        val bound = current.identity ?: return identityRequired()
        val adapter = active.hostedMigrationAdapter ?: return VitrinaKitResult.Failure(
            VitrinaKitError.Configuration(
                "The legacy hosted purchase API requires the VitrinaKit hosted adapter.",
            ),
        )
        val cacheGeneration = active.cache.generation
        if (!claimIdentityOperation(expected = current, bound = bound)) {
            return staleIdentityTransition()
        }
        val checkout = VitrinaKitHostedCheckoutOperation { receiptEmail, returnUrl ->
            if (active.cache.generation != cacheGeneration ||
                !claimIdentityOperation(expected = current, bound = bound)
            ) {
                return@VitrinaKitHostedCheckoutOperation staleIdentityTransition()
            }
            val result = active.client.createCheckoutSession(
                CheckoutSessionRequest(
                    externalUserId = bound.hostedExternalUserId,
                    productId = product.productId,
                    priceId = product.priceId,
                    receiptEmail = receiptEmail,
                    returnUrl = returnUrl,
                ),
            ).toKitResult()
            if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
                staleIdentityTransition()
            } else {
                result
            }
        }
        val result = runCatching {
            adapter.purchaseForBoundIdentity(
                VitrinaKitHostedMigrationRequest(
                    product = product,
                    externalUserId = bound.hostedExternalUserId,
                    checkout = checkout,
                ),
            )
        }.getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            return VitrinaKitResult.Failure(
                VitrinaKitError.Provider("The hosted purchase adapter could not complete checkout."),
            )
        }
        if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
            return staleIdentityTransition()
        }
        return result
    }

    private fun replaceRuntime(nextRuntime: VitrinaKitRuntime?): VitrinaKitLifecycleState {
        while (true) {
            val current = lifecycleState.value
            val next = VitrinaKitLifecycleState(
                revision = current.revision + 1,
                runtime = nextRuntime,
                identity = null,
            )
            if (lifecycleState.compareAndSet(expect = current, update = next)) {
                return current
            }
        }
    }

    private fun beginIdentityTransition(): IdentityTransition? {
        while (true) {
            val current = lifecycleState.value
            if (current.runtime == null) {
                return null
            }
            val next = current.copy(revision = current.revision + 1, identity = null)
            if (lifecycleState.compareAndSet(expect = current, update = next)) {
                return IdentityTransition(state = next, hadIdentity = current.identity != null)
            }
        }
    }

    private fun clearIdentity(): VitrinaKitLifecycleState? {
        while (true) {
            val current = lifecycleState.value
            if (current.runtime == null) {
                return null
            }
            val next = current.copy(revision = current.revision + 1, identity = null)
            if (lifecycleState.compareAndSet(expect = current, update = next)) {
                return current
            }
        }
    }

    private fun claimIdentityOperation(
        expected: VitrinaKitLifecycleState,
        bound: BoundIdentity,
    ): Boolean {
        while (true) {
            val current = lifecycleState.value
            if (current.revision != expected.revision ||
                current.runtime !== expected.runtime ||
                current.identity !== bound
            ) {
                return false
            }
            val claimed = current.copy(operationSerial = current.operationSerial + 1)
            if (lifecycleState.compareAndSet(expect = current, update = claimed)) {
                return true
            }
        }
    }

    private fun isSameIdentityLifecycle(expected: VitrinaKitLifecycleState): Boolean {
        val current = lifecycleState.value
        return current.revision == expected.revision &&
            current.runtime === expected.runtime &&
            current.identity === expected.identity
    }
}

private fun <T> staleIdentityTransition(): VitrinaKitResult<T> = VitrinaKitResult.Failure(
    VitrinaKitError.Auth("Subscriber identity changed before identification completed."),
)

private data class VitrinaKitRuntime(
    val client: VitrinaClient,
    val cache: SubscriberCache,
    val coordinator: PurchaseCoordinator?,
    val hostedMigrationAdapter: VitrinaKitHostedMigrationAdapter?,
    val appId: String?,
    val environment: String,
) {
    fun closePurchaseResources() {
        client.clearPurchaseAttempts()
        coordinator?.close()
        hostedMigrationAdapter?.close()
    }
}

private data class VitrinaKitLifecycleState(
    val revision: Long = 0,
    val runtime: VitrinaKitRuntime? = null,
    val identity: BoundIdentity? = null,
    val operationSerial: Long = 0,
)

private data class IdentityTransition(
    val state: VitrinaKitLifecycleState,
    val hadIdentity: Boolean,
)

private sealed interface BoundIdentity {
    val externalUserId: String?
    val hostedExternalUserId: String
    val sessionToken: String?
    val cacheKey: SubscriberCacheKey

    data class Trusted(
        val subscriberExternalUserId: String,
        override val sessionToken: String,
        override val cacheKey: SubscriberCacheKey,
        val scope: SubscriberScope,
    ) : BoundIdentity {
        override val externalUserId: String? = null
        override val hostedExternalUserId: String = subscriberExternalUserId

        override fun toString(): String =
            "Trusted(externalUserId=$subscriberExternalUserId, sessionToken=<redacted>, cacheKey=$cacheKey)"
    }

    data class External(
        override val externalUserId: String,
        override val cacheKey: SubscriberCacheKey,
    ) : BoundIdentity {
        override val sessionToken: String? = null
        override val hostedExternalUserId: String = externalUserId
    }
}

private fun <T> notActivated(): VitrinaKitResult<T> = VitrinaKitResult.Failure(
    VitrinaKitError.Configuration("VitrinaKit.activate must be called before SDK methods."),
)

private fun <T> identityRequired(): VitrinaKitResult<T> = VitrinaKitResult.Failure(
    VitrinaKitError.Auth("Subscriber identity is required. Call VitrinaKit.identify first."),
)

private fun purchaseFailure(
    code: VitrinaKitPurchaseErrorCode,
    message: String,
): VitrinaKitPurchaseResult.Failure = VitrinaKitPurchaseResult.Failure(
    VitrinaKitPurchaseError(
        code = code,
        message = message,
        retryable = false,
        supportReference = null,
    ),
)

private fun restoreFailure(
    code: VitrinaKitPurchaseErrorCode,
    message: String,
): VitrinaKitRestoreResult.Failure = VitrinaKitRestoreResult.Failure(
    VitrinaKitPurchaseError(
        code = code,
        message = message,
        retryable = false,
        supportReference = null,
    ),
)

private fun <T> VitrinaResult<T>.toKitResult(): VitrinaKitResult<T> = when (this) {
    is VitrinaResult.Success -> VitrinaKitResult.Success(value)
    is VitrinaResult.Failure -> VitrinaKitResult.Failure(error.toKitError())
}

private fun ru.vitrina.sdk.model.VitrinaError.toKitError(): VitrinaKitError = when (this) {
    is ru.vitrina.sdk.model.VitrinaError.Auth -> VitrinaKitError.Auth(message)
    is ru.vitrina.sdk.model.VitrinaError.Network -> VitrinaKitError.Network(message)
    is ru.vitrina.sdk.model.VitrinaError.Provider -> VitrinaKitError.Provider(message)
    is ru.vitrina.sdk.model.VitrinaError.Checkout -> VitrinaKitError.Checkout(code = code, message = message)
    is ru.vitrina.sdk.model.VitrinaError.Configuration -> VitrinaKitError.Configuration(message)
    is ru.vitrina.sdk.model.VitrinaError.Subscription -> VitrinaKitError.Subscription(message)
}

private const val RequiredPurchaseAdapterCount = 1
