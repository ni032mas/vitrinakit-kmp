package ru.vitrina.sdk.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.vitrina.sdk.model.VitrinaKitProfile
import ru.vitrina.sdk.model.VitrinaKitPaywall
import ru.vitrina.sdk.purchase.PendingPurchase

internal data class SubscriberCacheKey(
    val environment: String,
    val subscriberReference: String,
    val subscriberSession: String? = null,
) {
    override fun toString(): String =
        "SubscriberCacheKey(environment=$environment, " +
            "subscriberReference=$subscriberReference, subscriberSession=<redacted>)"
}

internal data class SubscriberCacheEntry(
    val profile: VitrinaKitProfile?,
    val resume: PendingPurchase?,
    val paywalls: Map<String, VitrinaKitPaywall>,
)

private data class SubscriberCacheState(
    val generation: Long,
    val entries: Map<SubscriberCacheKey, SubscriberCacheEntry>,
)

internal class SubscriberCache {
    private val state = MutableStateFlow(
        SubscriberCacheState(generation = 0, entries = emptyMap()),
    )

    val generation: Long
        get() = state.value.generation

    fun profile(key: SubscriberCacheKey): VitrinaKitProfile? = state.value.entries[key]?.profile

    fun resume(key: SubscriberCacheKey): PendingPurchase? = state.value.entries[key]?.resume

    fun replaceProfile(key: SubscriberCacheKey, profile: VitrinaKitProfile) {
        state.update { current ->
            val previous = current.entries[key]
            current.copy(
                entries = current.entries + (key to SubscriberCacheEntry(
                    profile = profile,
                    resume = previous?.resume,
                    paywalls = previous?.paywalls.orEmpty(),
                )),
            )
        }
    }

    fun replaceProfileIfCurrent(
        key: SubscriberCacheKey,
        profile: VitrinaKitProfile,
        generation: Long,
    ) {
        state.update { current ->
            if (current.generation != generation) {
                current
            } else {
                val previous = current.entries[key]
                current.copy(
                    entries = current.entries + (key to SubscriberCacheEntry(
                        profile = profile,
                        resume = previous?.resume,
                        paywalls = previous?.paywalls.orEmpty(),
                    )),
                )
            }
        }
    }

    fun storeResume(key: SubscriberCacheKey, resume: PendingPurchase) {
        state.update { current ->
            val previous = current.entries[key]
            current.copy(
                entries = current.entries + (key to SubscriberCacheEntry(
                    profile = previous?.profile,
                    resume = resume,
                    paywalls = previous?.paywalls.orEmpty(),
                )),
            )
        }
    }

    fun storeResumeIfCurrent(
        key: SubscriberCacheKey,
        resume: PendingPurchase,
        generation: Long,
    ) {
        state.update { current ->
            if (current.generation != generation) {
                current
            } else {
                val previous = current.entries[key]
                current.copy(
                    entries = current.entries + (key to SubscriberCacheEntry(
                        profile = previous?.profile,
                        resume = resume,
                        paywalls = previous?.paywalls.orEmpty(),
                    )),
                )
            }
        }
    }

    fun clearResume(key: SubscriberCacheKey) {
        state.update { current ->
            val previous = current.entries[key] ?: return@update current
            current.copy(entries = current.entries + (key to previous.copy(resume = null)))
        }
    }

    fun clearResumeIfCurrent(key: SubscriberCacheKey, generation: Long) {
        state.update { current ->
            if (current.generation != generation) {
                current
            } else {
                val previous = current.entries[key] ?: return@update current
                current.copy(entries = current.entries + (key to previous.copy(resume = null)))
            }
        }
    }

    fun replacePaywall(
        key: SubscriberCacheKey,
        placementId: String,
        paywall: VitrinaKitPaywall,
    ) {
        state.update { current ->
            val previous = current.entries[key]
            current.copy(
                entries = current.entries + (key to SubscriberCacheEntry(
                    profile = previous?.profile,
                    resume = previous?.resume,
                    paywalls = previous?.paywalls.orEmpty() + (placementId to paywall),
                )),
            )
        }
    }

    fun replacePaywallIfCurrent(
        key: SubscriberCacheKey,
        placementId: String,
        paywall: VitrinaKitPaywall,
        generation: Long,
    ) {
        state.update { current ->
            if (current.generation != generation) {
                current
            } else {
                val previous = current.entries[key]
                current.copy(
                    entries = current.entries + (key to SubscriberCacheEntry(
                        profile = previous?.profile,
                        resume = previous?.resume,
                        paywalls = previous?.paywalls.orEmpty() + (placementId to paywall),
                    )),
                )
            }
        }
    }

    fun placementForProduct(key: SubscriberCacheKey, product: ru.vitrina.sdk.model.PaywallProduct): String? =
        state.value.entries[key]?.paywalls?.entries?.firstOrNull { (_, paywall) ->
            paywall.products.any { candidate -> candidate === product }
        }?.key

    fun clear(key: SubscriberCacheKey) {
        state.update { current -> current.copy(entries = current.entries - key) }
    }

    fun clearIfCurrent(key: SubscriberCacheKey, generation: Long) {
        state.update { current ->
            if (current.generation == generation) {
                current.copy(entries = current.entries - key)
            } else {
                current
            }
        }
    }

    fun clearAll() {
        state.update { current ->
            current.copy(generation = current.generation + 1, entries = emptyMap())
        }
    }
}
