@file:OptIn(ru.vitrina.sdk.purchase.VitrinaKitPurchaseAdapterApi::class)

package ru.vitrina.sdk.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import ru.vitrina.sdk.model.SubscriberState
import ru.vitrina.sdk.purchase.PendingPurchase
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseCapability
import ru.vitrina.sdk.purchase.VitrinaKitPurchaseResumeData

class SubscriberCacheTest {
    @Test
    fun profileAndPendingStateAreIsolatedByEnvironmentAppAndSubscriber() {
        val cache = SubscriberCache()
        val first = key(environment = "production", app = "app-1", subscriber = "subscriber-1")
        val second = key(environment = "production", app = "app-1", subscriber = "subscriber-2")
        val profile = SubscriberState(externalUserId = "user-1", hasAccess = true, entitlements = emptyList())
        val pending = pending()

        cache.replaceProfile(first, profile)
        cache.storeResume(first, pending)

        assertSame(profile, cache.profile(first))
        assertEquals(pending, cache.resume(first))
        assertNull(cache.profile(second))
        assertNull(cache.resume(second))
    }

    @Test
    fun trustedSessionsForTheSameSubscriberUseDistinctCacheKeysWithoutLoggingTokens() {
        val first = key(
            environment = "production",
            app = "app-1",
            subscriber = "subscriber-1",
            session = "session-secret-1",
        )
        val second = key(
            environment = "production",
            app = "app-1",
            subscriber = "subscriber-1",
            session = "session-secret-2",
        )

        assertFalse(first == second)
        assertFalse(first.toString().contains("session-secret-1"))
    }

    @Test
    fun replacingProfileIsAtomicAndPendingNeverElevatesAccess() {
        val cache = SubscriberCache()
        val key = key(environment = "sandbox", app = "app-1", subscriber = "subscriber-1")
        val inactive = SubscriberState(externalUserId = "user-1", hasAccess = false, entitlements = emptyList())
        val active = SubscriberState(externalUserId = "user-1", hasAccess = true, entitlements = emptyList())

        cache.replaceProfile(key, inactive)
        cache.storeResume(key, pending())
        assertSame(inactive, cache.profile(key))

        cache.replaceProfile(key, active)
        assertSame(active, cache.profile(key))
    }

    @Test
    fun identityClearRemovesProfileAndResumeData() {
        val cache = SubscriberCache()
        val key = key(environment = "production", app = "app-1", subscriber = "subscriber-1")
        cache.replaceProfile(key, SubscriberState("user-1", true, emptyList()))
        cache.storeResume(key, pending())

        cache.clear(key)

        assertNull(cache.profile(key))
        assertNull(cache.resume(key))
    }

    @Test
    fun staleGenerationCannotRepopulateCacheAfterIdentityClear() {
        val cache = SubscriberCache()
        val key = key(environment = "production", app = "app-1", subscriber = "subscriber-1")
        val staleGeneration = cache.generation

        cache.clearAll()
        cache.replaceProfileIfCurrent(
            key = key,
            profile = SubscriberState("user-1", true, emptyList()),
            generation = staleGeneration,
        )

        assertNull(cache.profile(key))
    }
}

private fun key(
    environment: String,
    app: String,
    subscriber: String,
    session: String? = null,
): SubscriberCacheKey = SubscriberCacheKey(
    environment = environment,
    appId = app,
    subscriberReference = subscriber,
    subscriberSession = session,
)

private fun pending(): PendingPurchase = PendingPurchase(
    attemptReference = "attempt-1",
    placementId = "main",
    productReference = "premium_monthly",
    capability = VitrinaKitPurchaseCapability.GOOGLE_PLAY,
    startIdempotencyKey = "start-1",
    confirmationIdempotencyKey = "confirm-1",
    expiresAt = "2026-08-06T12:00:00Z",
    resumeData = VitrinaKitPurchaseResumeData("resume-secret"),
)
