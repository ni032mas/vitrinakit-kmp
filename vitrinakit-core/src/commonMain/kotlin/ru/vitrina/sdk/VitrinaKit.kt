@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import ru.vitrina.sdk.cache.SubscriberCache
import ru.vitrina.sdk.cache.SubscriberCacheKey
import ru.vitrina.sdk.http.KtorVitrinaHttpClient
import ru.vitrina.sdk.http.VitrinaHttpClient
import ru.vitrina.sdk.installation.VitrinaKitInstallationIdStorage
import ru.vitrina.sdk.installation.defaultInstallationIdStorage
import ru.vitrina.sdk.installation.loadOrCreateInstallationId
import ru.vitrina.sdk.installation.rotateInstallationId
import ru.vitrina.sdk.model.VitrinaKitError
import ru.vitrina.sdk.model.VitrinaKitAccessResolution
import ru.vitrina.sdk.model.VitrinaKitIdentifyResult
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.model.VitrinaKitPaywallProduct
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitPurchase
import ru.vitrina.sdk.model.VitrinaKitResult
import ru.vitrina.sdk.model.VitrinaResult
import ru.vitrina.sdk.purchase.PurchaseCoordinator
import ru.vitrina.sdk.purchase.IdentityLifecycleInvalidatedException
import ru.vitrina.sdk.purchase.IdentityOperationLease
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
import ru.vitrina.sdk.purchase.VitrinaKitHostedProfileOperation
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseAdapter
import ru.vitrina.sdk.purchase.VitrinaKitHostedPurchaseRequest
import ru.vitrina.sdk.purchase.VitrinaKitHostedRestoreRequest

/** Configuration for the high-level VitrinaKit SDK facade. */
class VitrinaKitConfig private constructor(
    internal val publicApiKey: String,
    internal val httpClient: VitrinaHttpClient?,
    internal val installationIdStorage: VitrinaKitInstallationIdStorage,
    internal val purchaseAdapters: List<VitrinaKitPurchaseAdapter>,
    internal val hostedMigrationAdapters: List<VitrinaKitHostedMigrationAdapter>,
) {
    /** Builder for [VitrinaKitConfig]. */
    class Builder(
        private val publicApiKey: String,
    ) {
        private var httpClient: VitrinaHttpClient? = null
        private var installationIdStorage: VitrinaKitInstallationIdStorage? = null
        private val purchaseAdapters = mutableListOf<VitrinaKitPurchaseAdapter>()
        private val hostedMigrationAdapters = mutableListOf<VitrinaKitHostedMigrationAdapter>()

        /** Sets a custom transport for tests or advanced integrations. */
        fun withHttpClient(httpClient: VitrinaHttpClient): Builder = apply {
            this.httpClient = httpClient
        }

        /** Overrides local installation identifier storage for custom platform integrations. */
        fun withInstallationIdStorage(storage: VitrinaKitInstallationIdStorage): Builder = apply {
            installationIdStorage = storage
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

        /** Adds the hosted checkout adapter used by provider-neutral purchase and restore calls. */
        @VitrinaKitPurchaseAdapterApi
        fun withHostedCheckoutAdapter(adapter: VitrinaKitHostedPurchaseAdapter): Builder = apply {
            hostedMigrationAdapters += adapter
        }

        /** Builds the facade configuration. */
        fun build(): VitrinaKitConfig = VitrinaKitConfig(
            publicApiKey = publicApiKey,
            httpClient = httpClient,
            installationIdStorage = installationIdStorage ?: defaultInstallationIdStorage(),
            purchaseAdapters = purchaseAdapters.toList(),
            hostedMigrationAdapters = hostedMigrationAdapters.toList(),
        )
    }
}

/** Identity-bound singleton facade for VitrinaKit mobile integrations. */
@OptIn(VitrinaKitPurchaseAdapterApi::class)
object VitrinaKit {
    private val lifecycleState = MutableStateFlow(VitrinaKitLifecycleState())
    private val lifecycleLease = ReentrantLifecycleLease()

    /** Activates the SDK with one public API key and exactly one purchase adapter. */
    fun activate(config: VitrinaKitConfig): VitrinaKitResult<Unit> = lifecycleLease.tryBlocking {
        if (config.publicApiKey.isBlank()) {
            return@tryBlocking VitrinaKitResult.Failure(VitrinaKitError.Configuration("publicApiKey is required."))
        }
        if (config.purchaseAdapters.size + config.hostedMigrationAdapters.size != RequiredPurchaseAdapterCount) {
            return@tryBlocking VitrinaKitResult.Failure(
                VitrinaKitError.Configuration("Exactly one purchase adapter is required."),
            )
        }
        val installationId = loadOrCreateInstallationId(config.installationIdStorage).getOrElse {
            return@tryBlocking VitrinaKitResult.Failure(
                VitrinaKitError.Configuration("Installation identifier could not be persisted."),
            )
        }
        val client = VitrinaClient(
            config = VitrinaConfig(
                publishableKey = config.publicApiKey,
                installationId = installationId,
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
            hostedPurchaseAdapter = config.hostedMigrationAdapters.singleOrNull() as? VitrinaKitHostedPurchaseAdapter,
            hostedOperationGuard = config.hostedMigrationAdapters.singleOrNull()?.let { Mutex() },
            environment = VitrinaKitApiEnvironment.name,
            installationIdStorage = config.installationIdStorage,
            backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val installationIdentity = installationIdentity(runtime = nextRuntime, installationId = installationId)
        val previous = replaceRuntime(nextRuntime = nextRuntime, identity = installationIdentity)
        previous.runtime?.closeRuntime()
        seedCheckingProfile(runtime = nextRuntime, identity = installationIdentity)
        scheduleAutomaticRestore(runtime = nextRuntime, identity = installationIdentity)
        VitrinaKitResult.Success(Unit)
    } ?: lifecycleBusy()

    /**
     * Associates the current installation with an application user and returns the merge outcome.
     *
     * A merge changes the canonical subscriber identifier used for paywall variant assignment.
     * Call [getPaywall] again after this method completes, even when a placement was already loaded.
     */
    suspend fun identify(userId: String): VitrinaKitResult<VitrinaKitIdentifyResult> =
        lifecycleLease.runTransitionExclusive(
            onNested = { lifecycleBusy() },
            block = { identifyUserLeased(userId = userId) },
        )

    /**
     * Uses an opaque subscriber session minted by the integrating application's backend.
     *
     * The session becomes the sole authorization authority; the publishable key is not sent on
     * requests authenticated with it. Sessions are retained in memory only.
     */
    suspend fun setSubscriberSession(session: String): VitrinaKitResult<VitrinaKitProfile> =
        lifecycleLease.runTransitionExclusive(
            onNested = { lifecycleBusy() },
            block = { setSubscriberSessionLeased(session = session) },
        )

    /** Requests a one-time email verification code without exposing whether the address owns access. */
    suspend fun requestEmailVerification(email: String): VitrinaKitResult<Unit> {
        val current = lifecycleState.value
        val active = current.runtime ?: return notActivated()
        return lifecycleLease.runSideEffect {
            if (!isSameIdentityLifecycle(current)) {
                return@runSideEffect staleIdentityTransition()
            }
            active.client.requestEmailVerification(email = email).toKitResult()
        }
    }

    /** Confirms an email verification code, binds the returned session, and returns its profile. */
    suspend fun confirmEmailVerification(email: String, code: String): VitrinaKitResult<VitrinaKitProfile> =
        lifecycleLease.runTransitionExclusive(
            onNested = { lifecycleBusy() },
            block = { confirmEmailVerificationLeased(email = email, code = code) },
        )

    /**
     * Clears subscriber state and rotates the installation identifier before returning to tier one.
     *
     * The previous installation is never reused after logout, so it cannot restore access belonging
     * to the person who signed out. This intentionally resets paywall assignment and device analytics.
     */
    fun logout(): VitrinaKitResult<Unit> = lifecycleLease.tryBlocking {
        val current = lifecycleState.value
        val active = current.runtime ?: return@tryBlocking notActivated()
        val installationId = rotateInstallationId(active.installationIdStorage).getOrElse {
            return@tryBlocking VitrinaKitResult.Failure(
                VitrinaKitError.Configuration("Installation identifier could not be rotated."),
            )
        }
        active.client.replaceInstallationId(installationId)
        val replacement = installationIdentity(runtime = active, installationId = installationId)
        replaceIdentity(identity = replacement) ?: return@tryBlocking notActivated()
        active.closePurchaseResources()
        active.cache.clearAll()
        seedCheckingProfile(runtime = active, identity = replacement)
        scheduleAutomaticRestore(runtime = active, identity = replacement)
        VitrinaKitResult.Success(Unit)
    } ?: lifecycleBusy()

    /** Fetches a paywall for the currently identified subscriber. */
    suspend fun getPaywall(placementId: String): VitrinaKitResult<VitrinaKitPaywall> {
        val current = lifecycleState.value
        val active = current.runtime ?: return notActivated()
        val bound = current.identity ?: return identityRequired()
        val cacheGeneration = active.cache.generation
        val result = lifecycleLease.runSideEffect {
            if (!isSameIdentityLifecycle(current)) {
                return@runSideEffect null
            }
            active.client.fetchPaywall(
                placementKey = placementId,
                externalUserId = bound.externalUserId,
                subscriberSession = bound.sessionToken,
            ).toKitResult()
        } ?: return staleIdentityTransition()
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
        val cacheGeneration = active.cache.generation
        val placementId = active.cache.placementForProduct(key = bound.cacheKey, product = product)
            ?: return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.INVALID_REQUEST,
                message = "The product must come from a VitrinaKit paywall.",
            )
        active.hostedPurchaseAdapter?.let { adapter ->
            return purchaseHosted(
                current = current,
                active = active,
                bound = bound,
                product = product,
                placementId = placementId,
                adapter = adapter,
                cacheGeneration = cacheGeneration,
            )
        }
        val coordinator = active.coordinator ?: return purchaseFailure(
            code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            message = "The configured adapter does not support native purchase orchestration.",
        )
        return coordinator.purchase(
            scope = bound.scope,
            placementId = placementId,
            product = product,
            expectedCacheGeneration = cacheGeneration,
            identityLease = identityOperationLease(expected = current, bound = bound),
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
        val cacheGeneration = active.cache.generation
        active.hostedPurchaseAdapter?.let { adapter ->
            return restoreHosted(
                current = current,
                active = active,
                bound = bound,
                adapter = adapter,
                cacheGeneration = cacheGeneration,
            )
        }
        val coordinator = active.coordinator ?: return restoreFailure(
            code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            message = "The configured adapter does not support native restore orchestration.",
        )
        return coordinator.restore(
            scope = bound.scope,
            expectedCacheGeneration = cacheGeneration,
            identityLease = identityOperationLease(expected = current, bound = bound),
        )
    }

    /**
     * Reconciles one identity-bound pending native purchase when the app returns to foreground.
     *
     * Returns `null` without querying a provider when the subscriber has no pending attempt.
     * A non-null result has the same server-authoritative semantics as [purchase]; provider state
     * alone never grants access.
     */
    suspend fun onForeground(): VitrinaKitPurchaseResult? {
        val current = lifecycleState.value
        val active = current.runtime ?: return null
        val bound = current.identity ?: return null
        val coordinator = active.coordinator ?: return null
        val cacheGeneration = active.cache.generation
        return coordinator.onForeground(
            scope = bound.scope,
            expectedCacheGeneration = cacheGeneration,
            identityLease = identityOperationLease(expected = current, bound = bound),
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
        val result = lifecycleLease.runSideEffect {
            if (!isSameIdentityLifecycle(current)) {
                return@runSideEffect null
            }
            active.client.refreshSubscriber(
                externalUserId = bound.externalUserId,
                subscriberSession = bound.sessionToken,
            ).toKitResult()
        } ?: return staleIdentityTransition()
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
        message = "Call identify(userId), then getPaywall(placementId). Per-call user IDs are no longer accepted.",
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
        message = "Call identify(userId), then purchase(product). Hosted details move to hosted adapter configuration.",
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
        message = "Call identify(userId), then getProfile(). Per-call user IDs are no longer accepted.",
        replaceWith = ReplaceWith("getProfile()"),
    )
    suspend fun getProfile(userId: String): VitrinaKitResult<VitrinaKitProfile> {
        return getProfile()
    }

    /** Blocking legacy variant of [getPaywall]. */
    @Deprecated(
        message = "Call identify(userId), then getPaywall(placementId) from a coroutine.",
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
        message = "Call identify(userId), then purchase(product) from a coroutine.",
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
        message = "Call identify(userId), then getProfile() from a coroutine.",
        replaceWith = ReplaceWith("getProfile()"),
    )
    fun getProfileBlocking(userId: String): VitrinaKitResult<VitrinaKitProfile> = runBlocking {
        getProfile()
    }

    internal fun resetForTesting() {
        runBlocking {
            lifecycleLease.runTransitionExclusive(
                onNested = { false },
                block = {
                    val previous = replaceRuntime(nextRuntime = null, identity = null)
                    previous.runtime?.closeRuntime()
                    true
                },
            )
        }
    }

    private suspend fun identifyUserLeased(userId: String): VitrinaKitResult<VitrinaKitIdentifyResult> {
        if (userId.isBlank()) {
            return VitrinaKitResult.Failure(VitrinaKitError.Auth("User ID is required."))
        }
        val transition = beginIdentityTransition() ?: return notActivated()
        val active = transition.state.runtime ?: return notActivated()
        active.closePurchaseResources()
        active.cache.clearAll()
        active.client.clearPurchaseAttempts()
        val cacheGeneration = active.cache.generation
        return when (val result = active.client.identifySubscriber(userId = userId)) {
            is VitrinaResult.Failure -> VitrinaKitResult.Failure(result.error.toKitError())
            is VitrinaResult.Success -> {
                val bound = externalIdentity(runtime = active, userId = userId)
                when (
                    val binding = bindIdentityIfCurrent(
                        runtime = active,
                        expectedLifecycle = transition.state,
                        cacheGeneration = cacheGeneration,
                        bound = bound,
                        profile = result.value.profile,
                    )
                ) {
                    is VitrinaKitResult.Failure -> binding
                    is VitrinaKitResult.Success -> VitrinaKitResult.Success(
                        result.value.copy(profile = binding.value),
                    )
                }
            }
        }
    }

    private suspend fun setSubscriberSessionLeased(session: String): VitrinaKitResult<VitrinaKitProfile> {
        if (session.isBlank()) {
            return VitrinaKitResult.Failure(VitrinaKitError.Auth("Subscriber session is required."))
        }
        val transition = beginIdentityTransition() ?: return notActivated()
        val active = transition.state.runtime ?: return notActivated()
        active.closePurchaseResources()
        active.cache.clearAll()
        active.client.clearPurchaseAttempts()
        val cacheGeneration = active.cache.generation
        val profile = active.client.refreshSubscriber(externalUserId = null, subscriberSession = session)
        return when (profile) {
            is VitrinaResult.Failure -> VitrinaKitResult.Failure(profile.error.toKitError())
            is VitrinaResult.Success -> bindIdentityIfCurrent(
                runtime = active,
                expectedLifecycle = transition.state,
                cacheGeneration = cacheGeneration,
                bound = sessionIdentity(runtime = active, session = session, profile = profile.value),
                profile = profile.value,
            )
        }
    }

    private suspend fun confirmEmailVerificationLeased(
        email: String,
        code: String,
    ): VitrinaKitResult<VitrinaKitProfile> {
        val transition = beginIdentityTransition() ?: return notActivated()
        val active = transition.state.runtime ?: return notActivated()
        active.closePurchaseResources()
        active.cache.clearAll()
        active.client.clearPurchaseAttempts()
        val cacheGeneration = active.cache.generation
        return when (val confirmation = active.client.confirmEmailVerification(email = email, code = code)) {
            is VitrinaResult.Failure -> VitrinaKitResult.Failure(confirmation.error.toKitError())
            is VitrinaResult.Success -> bindIdentityIfCurrent(
                runtime = active,
                expectedLifecycle = transition.state,
                cacheGeneration = cacheGeneration,
                bound = sessionIdentity(
                    runtime = active,
                    session = confirmation.value.sessionToken,
                    profile = confirmation.value.profile,
                ),
                profile = confirmation.value.profile,
            )
        }
    }

    private suspend fun purchaseHosted(
        current: VitrinaKitLifecycleState,
        active: VitrinaKitRuntime,
        bound: BoundIdentity,
        product: VitrinaKitPaywallProduct,
        placementId: String,
        adapter: VitrinaKitHostedPurchaseAdapter,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult {
        val guard = active.hostedOperationGuard ?: return purchaseFailure(
            code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            message = "The configured adapter does not support hosted purchase orchestration.",
        )
        if (!guard.tryLock()) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS,
                message = "A purchase is already in progress.",
            )
        }
        return try {
            purchaseHostedGuarded(
                current = current,
                active = active,
                bound = bound,
                product = product,
                placementId = placementId,
                adapter = adapter,
                cacheGeneration = cacheGeneration,
            )
        } finally {
            guard.unlock()
        }
    }

    private suspend fun purchaseHostedGuarded(
        current: VitrinaKitLifecycleState,
        active: VitrinaKitRuntime,
        bound: BoundIdentity,
        product: VitrinaKitPaywallProduct,
        placementId: String,
        adapter: VitrinaKitHostedPurchaseAdapter,
        cacheGeneration: Long,
    ): VitrinaKitPurchaseResult {
        val identityLease = identityOperationLease(expected = current, bound = bound)
        val refreshedProfile = MutableStateFlow<VitrinaKitProfile?>(null)
        val request = VitrinaKitHostedPurchaseRequest(
            product = product,
            checkout = hostedCheckoutOperation(
                current = current,
                active = active,
                bound = bound,
                product = product,
                placementId = placementId,
                cacheGeneration = cacheGeneration,
                identityLease = identityLease,
            ),
            profile = hostedProfileOperation(
                current = current,
                active = active,
                bound = bound,
                cacheGeneration = cacheGeneration,
                identityLease = identityLease,
                onRefresh = { profile -> refreshedProfile.value = profile },
            ),
        )
        val result = runCatching {
            identityLease.run { adapter.purchase(request = request) }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            if (throwable is IdentityLifecycleInvalidatedException) {
                return purchaseFailure(
                    code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
                    message = "Subscriber identity changed while the purchase was in progress.",
                )
            }
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                message = "The hosted purchase adapter could not complete checkout.",
            )
        }
        if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
                message = "Subscriber identity changed while the purchase was in progress.",
            )
        }
        if (result is VitrinaKitPurchaseResult.Success &&
            (refreshedProfile.value != result.profile || !result.profile.grantsAccessFor(product = product))
        ) {
            return purchaseFailure(
                code = VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED,
                message = "The server did not return an authoritative subscriber profile.",
            )
        }
        return result
    }

    private suspend fun restoreHosted(
        current: VitrinaKitLifecycleState,
        active: VitrinaKitRuntime,
        bound: BoundIdentity,
        adapter: VitrinaKitHostedPurchaseAdapter,
        cacheGeneration: Long,
    ): VitrinaKitRestoreResult {
        val guard = active.hostedOperationGuard ?: return restoreFailure(
            code = VitrinaKitPurchaseErrorCode.PROVIDER_NOT_SUPPORTED_BY_BUILD,
            message = "The configured adapter does not support hosted restore orchestration.",
        )
        if (!guard.tryLock()) {
            return restoreFailure(
                code = VitrinaKitPurchaseErrorCode.PURCHASE_IN_PROGRESS,
                message = "A purchase is already in progress.",
            )
        }
        return try {
            restoreHostedGuarded(
                current = current,
                active = active,
                bound = bound,
                adapter = adapter,
                cacheGeneration = cacheGeneration,
            )
        } finally {
            guard.unlock()
        }
    }

    private suspend fun restoreHostedGuarded(
        current: VitrinaKitLifecycleState,
        active: VitrinaKitRuntime,
        bound: BoundIdentity,
        adapter: VitrinaKitHostedPurchaseAdapter,
        cacheGeneration: Long,
    ): VitrinaKitRestoreResult {
        val identityLease = identityOperationLease(expected = current, bound = bound)
        val refreshedProfile = MutableStateFlow<VitrinaKitProfile?>(null)
        val request = VitrinaKitHostedRestoreRequest(
            profile = hostedProfileOperation(
                current = current,
                active = active,
                bound = bound,
                cacheGeneration = cacheGeneration,
                identityLease = identityLease,
                onRefresh = { profile -> refreshedProfile.value = profile },
            ),
        )
        val result = runCatching {
            identityLease.run { adapter.restore(request = request) }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            if (throwable is IdentityLifecycleInvalidatedException) {
                return restoreFailure(
                    code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
                    message = "Subscriber identity changed while restore was in progress.",
                )
            }
            return restoreFailure(
                code = VitrinaKitPurchaseErrorCode.ADAPTER_FAILURE,
                message = "The hosted purchase adapter could not refresh purchases.",
            )
        }
        if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
            return restoreFailure(
                code = VitrinaKitPurchaseErrorCode.IDENTITY_SESSION_INVALID,
                message = "Subscriber identity changed while restore was in progress.",
            )
        }
        val returnedProfile = when (result) {
            is VitrinaKitRestoreResult.Success -> result.profile
            is VitrinaKitRestoreResult.NoPurchases -> result.profile
            is VitrinaKitRestoreResult.Failure -> null
        }
        if (returnedProfile != null && refreshedProfile.value != returnedProfile) {
            return restoreFailure(
                code = VitrinaKitPurchaseErrorCode.SERVER_VALIDATION_FAILED,
                message = "The server did not return an authoritative subscriber profile.",
            )
        }
        return result
    }

    private fun hostedCheckoutOperation(
        current: VitrinaKitLifecycleState,
        active: VitrinaKitRuntime,
        bound: BoundIdentity,
        product: VitrinaKitPaywallProduct,
        placementId: String,
        cacheGeneration: Long,
        identityLease: IdentityOperationLease,
    ): VitrinaKitHostedCheckoutOperation = VitrinaKitHostedCheckoutOperation { _, returnUrl ->
        val result = runCatching {
            identityLease.run {
                active.client.createCheckoutSession(
                    request = CheckoutSessionRequest(
                        placementKey = placementId,
                        productReference = product.productKey,
                        returnUrl = returnUrl,
                    ),
                    externalUserId = bound.hostedExternalUserId,
                    subscriberSession = bound.sessionToken,
                ).toKitResult()
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            if (throwable is IdentityLifecycleInvalidatedException) {
                return@VitrinaKitHostedCheckoutOperation staleIdentityTransition()
            }
            return@VitrinaKitHostedCheckoutOperation VitrinaKitResult.Failure(
                VitrinaKitError.Network("Network request failed."),
            )
        }
        if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
            staleIdentityTransition()
        } else {
            result
        }
    }

    private fun hostedProfileOperation(
        current: VitrinaKitLifecycleState,
        active: VitrinaKitRuntime,
        bound: BoundIdentity,
        cacheGeneration: Long,
        identityLease: IdentityOperationLease,
        onRefresh: (VitrinaKitProfile) -> Unit,
    ): VitrinaKitHostedProfileOperation = VitrinaKitHostedProfileOperation {
        val result = runCatching {
            identityLease.run {
                active.client.refreshSubscriber(
                    externalUserId = bound.externalUserId,
                    subscriberSession = bound.sessionToken,
                ).toKitResult()
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            if (throwable is IdentityLifecycleInvalidatedException) {
                return@VitrinaKitHostedProfileOperation staleIdentityTransition()
            }
            return@VitrinaKitHostedProfileOperation VitrinaKitResult.Failure(
                VitrinaKitError.Network("Network request failed."),
            )
        }
        if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
            return@VitrinaKitHostedProfileOperation staleIdentityTransition()
        }
        if (result is VitrinaKitResult.Success) {
            active.cache.replaceProfileIfCurrent(
                key = bound.cacheKey,
                profile = result.value,
                generation = cacheGeneration,
            )
            onRefresh(result.value)
        }
        result
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
        val placementId = active.cache.placementForProduct(key = bound.cacheKey, product = product)
            ?: return VitrinaKitResult.Failure(
                VitrinaKitError.Configuration("The product must come from a VitrinaKit paywall."),
            )
        val cacheGeneration = active.cache.generation
        val identityLease = identityOperationLease(expected = current, bound = bound)
        val checkout = VitrinaKitHostedCheckoutOperation { _, returnUrl ->
            val result = runCatching {
                identityLease.run {
                    active.client.createCheckoutSession(
                        request = CheckoutSessionRequest(
                            placementKey = placementId,
                            productReference = product.productKey,
                            returnUrl = returnUrl,
                        ),
                        externalUserId = bound.hostedExternalUserId,
                        subscriberSession = bound.sessionToken,
                    ).toKitResult()
                }
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                if (throwable is IdentityLifecycleInvalidatedException) {
                    return@VitrinaKitHostedCheckoutOperation staleIdentityTransition()
                }
                return@VitrinaKitHostedCheckoutOperation VitrinaKitResult.Failure(
                    VitrinaKitError.Network("Network request failed."),
                )
            }
            if (!isSameIdentityLifecycle(current) || active.cache.generation != cacheGeneration) {
                staleIdentityTransition()
            } else {
                result
            }
        }
        val result = runCatching {
            identityLease.run {
                adapter.purchaseForBoundIdentity(
                    VitrinaKitHostedMigrationRequest(
                        product = product,
                        externalUserId = bound.hostedExternalUserId,
                        checkout = checkout,
                    ),
                )
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }
            if (throwable is IdentityLifecycleInvalidatedException) {
                return staleIdentityTransition()
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

    private fun installationIdentity(
        runtime: VitrinaKitRuntime,
        installationId: String,
    ): BoundIdentity.Installation {
        val cacheKey = SubscriberCacheKey(
            environment = runtime.environment,
            subscriberReference = installationId,
        )
        return BoundIdentity.Installation(
            installationId = installationId,
            cacheKey = cacheKey,
            scope = SubscriberScope(
                cacheKey = cacheKey,
                purchaseAttemptGeneration = runtime.client.purchaseAttemptGeneration,
            ),
        )
    }

    private fun externalIdentity(runtime: VitrinaKitRuntime, userId: String): BoundIdentity.External {
        val cacheKey = SubscriberCacheKey(
            environment = runtime.environment,
            subscriberReference = userId,
        )
        return BoundIdentity.External(
            externalUserId = userId,
            cacheKey = cacheKey,
            scope = SubscriberScope(
                cacheKey = cacheKey,
                subscriberId = userId,
                purchaseAttemptGeneration = runtime.client.purchaseAttemptGeneration,
            ),
        )
    }

    private fun sessionIdentity(
        runtime: VitrinaKitRuntime,
        session: String,
        profile: VitrinaKitProfile,
    ): BoundIdentity.Session {
        val cacheKey = SubscriberCacheKey(
            environment = runtime.environment,
            subscriberReference = profile.externalUserId.ifBlank { "subscriber-session" },
            subscriberSession = session,
        )
        return BoundIdentity.Session(
            subscriberExternalUserId = profile.externalUserId,
            sessionToken = session,
            cacheKey = cacheKey,
            scope = SubscriberScope(
                cacheKey = cacheKey,
                sessionToken = session,
                purchaseAttemptGeneration = runtime.client.purchaseAttemptGeneration,
            ),
        )
    }

    private fun seedCheckingProfile(runtime: VitrinaKitRuntime, identity: BoundIdentity) {
        if (runtime.coordinator == null) {
            return
        }
        runtime.cache.replaceProfile(
            key = identity.cacheKey,
            profile = VitrinaKitProfile(
                externalUserId = identity.externalUserId.orEmpty(),
                hasAccess = false,
                entitlements = emptyList(),
                accessResolution = VitrinaKitAccessResolution.CHECKING,
            ),
        )
    }

    private fun scheduleAutomaticRestore(runtime: VitrinaKitRuntime, identity: BoundIdentity) {
        val coordinator = runtime.coordinator ?: return
        val expected = lifecycleState.value
        val cacheGeneration = runtime.cache.generation
        runtime.automaticRestoreJob?.cancel()
        runtime.automaticRestoreJob = runtime.backgroundScope.launch {
            val result = coordinator.restore(
                scope = identity.scope,
                expectedCacheGeneration = cacheGeneration,
                identityLease = identityOperationLease(expected = expected, bound = identity),
            )
            if (isSameIdentityLifecycle(expected)) {
                runtime.cache.profile(key = identity.cacheKey)?.let { profile ->
                    runtime.cache.replaceProfileIfCurrent(
                        key = identity.cacheKey,
                        profile = profile.copy(
                            accessResolution = if (result is VitrinaKitRestoreResult.Failure) {
                                VitrinaKitAccessResolution.UNAVAILABLE
                            } else {
                                VitrinaKitAccessResolution.CURRENT
                            },
                        ),
                        generation = cacheGeneration,
                    )
                }
            }
        }
    }

    private fun replaceRuntime(
        nextRuntime: VitrinaKitRuntime?,
        identity: BoundIdentity?,
    ): VitrinaKitLifecycleState {
        while (true) {
            val current = lifecycleState.value
            val next = VitrinaKitLifecycleState(
                revision = current.revision + 1,
                runtime = nextRuntime,
                identity = identity,
            )
            if (lifecycleState.compareAndSet(expect = current, update = next)) {
                return current
            }
        }
    }

    private fun replaceIdentity(identity: BoundIdentity): VitrinaKitLifecycleState? {
        while (true) {
            val current = lifecycleState.value
            if (current.runtime == null) {
                return null
            }
            val next = current.copy(revision = current.revision + 1, identity = identity)
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
                return IdentityTransition(state = next)
            }
        }
    }

    private fun identityOperationLease(
        expected: VitrinaKitLifecycleState,
        bound: BoundIdentity,
    ): IdentityOperationLease = object : IdentityOperationLease {
        override suspend fun <T> run(sideEffect: suspend () -> T): T = lifecycleLease.runSideEffect {
            val current = lifecycleState.value
            if (current.revision != expected.revision ||
                current.runtime !== expected.runtime ||
                current.identity !== bound
            ) {
                throw IdentityLifecycleInvalidatedException()
            }
            sideEffect()
        }

        override suspend fun <T> runConcurrent(sideEffect: suspend () -> T): T =
            lifecycleLease.runConcurrentSideEffect {
                val current = lifecycleState.value
                if (current.revision != expected.revision ||
                    current.runtime !== expected.runtime ||
                    current.identity !== bound
                ) {
                    throw IdentityLifecycleInvalidatedException()
                }
                sideEffect()
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
    val hostedPurchaseAdapter: VitrinaKitHostedPurchaseAdapter?,
    val hostedOperationGuard: Mutex?,
    val environment: String,
    val installationIdStorage: VitrinaKitInstallationIdStorage,
    val backgroundScope: CoroutineScope,
    var automaticRestoreJob: Job? = null,
) {
    suspend fun closePurchaseResources() {
        automaticRestoreJob?.cancelAndJoin()
        automaticRestoreJob = null
        client.clearPurchaseAttempts()
        coordinator?.close()
        hostedMigrationAdapter?.close()
    }

    suspend fun closeRuntime() {
        closePurchaseResources()
        backgroundScope.cancel()
    }
}

private data class VitrinaKitLifecycleState(
    val revision: Long = 0,
    val runtime: VitrinaKitRuntime? = null,
    val identity: BoundIdentity? = null,
)

/**
 * A coroutine-reentrant lifecycle mutex.
 *
 * Reentrancy requires admission from an active lexical ownership token so a hosted adapter and its
 * structured children may call the core-owned checkout callback without taking the lease twice.
 * Identity-validated foreground recovery may also borrow an active side-effect token so it can
 * resolve a provider waiter; transition tokens are never externally borrowable. Closing a token
 * rejects new borrowers and drains admitted work before unlocking.
 * Synchronous transitions use [Mutex.tryLock] and fail without state changes while a side effect is
 * suspended; callers can retry instead of blocking a Main/single-thread dispatcher.
 */
private class ReentrantLifecycleLease {
    private val mutex = Mutex()
    private val activeSideEffectLease = MutableStateFlow<HeldLifecycleLease?>(null)

    suspend fun <T> runSideEffect(block: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldLifecycleLease]
        if (held?.owner === this && held.tryBorrow()) {
            return try {
                block()
            } finally {
                held.releaseBorrower()
            }
        }
        mutex.lock()
        return try {
            runLocked(block = block, externallyBorrowable = true)
        } finally {
            mutex.unlock()
        }
    }

    suspend fun <T> runConcurrentSideEffect(block: suspend () -> T): T {
        val contextual = currentCoroutineContext()[HeldLifecycleLease]
        if (contextual?.owner === this && contextual.tryBorrow()) {
            return try {
                block()
            } finally {
                contextual.releaseBorrower()
            }
        }
        val active = activeSideEffectLease.value
        if (active?.tryBorrow() == true) {
            return try {
                block()
            } finally {
                active.releaseBorrower()
            }
        }
        return runSideEffect(block = block)
    }

    suspend fun <T> runTransitionExclusive(
        onNested: () -> T,
        block: suspend () -> T,
    ): T {
        val held = currentCoroutineContext()[HeldLifecycleLease]
        if (held?.owner === this && held.isActive()) {
            return onNested()
        }
        mutex.lock()
        return try {
            runLocked(block = block, externallyBorrowable = false)
        } finally {
            mutex.unlock()
        }
    }

    fun <T> tryBlocking(block: suspend () -> T): T? {
        if (!mutex.tryLock()) {
            return null
        }
        return try {
            runBlocking { runLocked(block = block, externallyBorrowable = false) }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun <T> runLocked(
        block: suspend () -> T,
        externallyBorrowable: Boolean,
    ): T {
        val held = HeldLifecycleLease(owner = this)
        if (externallyBorrowable) {
            activeSideEffectLease.value = held
        }
        return try {
            withContext(held) {
                try {
                    block()
                } finally {
                    withContext(NonCancellable) {
                        held.closeAndDrain()
                    }
                }
            }
        } finally {
            activeSideEffectLease.compareAndSet(expect = held, update = null)
        }
    }
}

private class HeldLifecycleLease(
    val owner: ReentrantLifecycleLease,
) : AbstractCoroutineContextElement(HeldLifecycleLease) {
    private val admission = MutableStateFlow(LeaseAdmission())

    fun tryBorrow(): Boolean {
        while (true) {
            val current = admission.value
            if (!current.open) {
                return false
            }
            if (admission.compareAndSet(current, current.copy(borrowers = current.borrowers + 1))) {
                return true
            }
        }
    }

    fun isActive(): Boolean = admission.value.active

    fun releaseBorrower() {
        while (true) {
            val current = admission.value
            val released = current.copy(borrowers = current.borrowers - 1)
            if (admission.compareAndSet(current, released)) {
                return
            }
        }
    }

    suspend fun closeAndDrain() {
        while (true) {
            val current = admission.value
            if (!current.open || admission.compareAndSet(current, current.copy(open = false))) {
                break
            }
        }
        admission.first { state -> state.borrowers == 0 }
        while (true) {
            val current = admission.value
            if (!current.active || admission.compareAndSet(current, current.copy(active = false))) {
                return
            }
        }
    }

    companion object : CoroutineContext.Key<HeldLifecycleLease>
}

private data class LeaseAdmission(
    val active: Boolean = true,
    val open: Boolean = true,
    val borrowers: Int = 0,
)

private data class IdentityTransition(
    val state: VitrinaKitLifecycleState,
)

private sealed interface BoundIdentity {
    val externalUserId: String?
    val hostedExternalUserId: String
    val sessionToken: String?
    val cacheKey: SubscriberCacheKey
    val scope: SubscriberScope

    data class Installation(
        val installationId: String,
        override val cacheKey: SubscriberCacheKey,
        override val scope: SubscriberScope,
    ) : BoundIdentity {
        override val externalUserId: String? = null
        override val hostedExternalUserId: String = ""
        override val sessionToken: String? = null
    }

    data class Session(
        val subscriberExternalUserId: String,
        override val sessionToken: String,
        override val cacheKey: SubscriberCacheKey,
        override val scope: SubscriberScope,
    ) : BoundIdentity {
        override val externalUserId: String? = null
        override val hostedExternalUserId: String = subscriberExternalUserId

        override fun toString(): String =
            "Session(externalUserId=<redacted>, sessionToken=<redacted>, cacheKey=$cacheKey)"
    }

    data class External(
        override val externalUserId: String,
        override val cacheKey: SubscriberCacheKey,
        override val scope: SubscriberScope,
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

private fun <T> lifecycleBusy(): VitrinaKitResult<T> = VitrinaKitResult.Failure(
    VitrinaKitError.Configuration("A subscriber lifecycle operation is in progress. Retry this call."),
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

private fun VitrinaKitProfile.grantsAccessFor(product: VitrinaKitPaywallProduct): Boolean {
    val entitlementKeys = product.entitlements.map { entitlement -> entitlement.key }.toSet()
    return hasAccess && entitlements.any { entitlement ->
        entitlement.hasAccess &&
            (entitlement.productKey == product.productKey || entitlement.key in entitlementKeys)
    }
}

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
