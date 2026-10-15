package ru.vitrina.sdk.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads a closed server enumeration at the response boundary without letting one value this SDK
 * version does not know fail the whole response.
 *
 * An unrecognized wire value decodes as [unknown]. Every recognized value, and all encoding, go
 * through the enum's own generated [known] serializer, so the `@SerialName` annotations on its
 * entries stay the single source of truth for the wire format.
 *
 * Deliberately not `coerceInputValues`: that only reaches properties declared with a default,
 * replaces the value silently, and would also turn an explicit `null` into that default for every
 * other property the client decodes.
 */
internal abstract class UnknownTolerantEnumSerializer<T : Enum<T>>(
    private val known: KSerializer<T>,
    private val entries: List<T>,
    private val unknown: T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor
        get() = known.descriptor

    override fun serialize(encoder: Encoder, value: T) {
        known.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T {
        // The generated enum descriptor lists its elements in declaration order, so an element
        // index is the entry's ordinal.
        val index = known.descriptor.getElementIndex(decoder.decodeString())
        return if (index == CompositeDecoder.UNKNOWN_NAME) unknown else entries[index]
    }
}

internal object BillingIntervalUnitSerializer : UnknownTolerantEnumSerializer<BillingIntervalUnit>(
    known = BillingIntervalUnit.serializer(),
    entries = BillingIntervalUnit.entries,
    unknown = BillingIntervalUnit.UNKNOWN,
)

internal object SubscriptionStatusSerializer : UnknownTolerantEnumSerializer<SubscriptionStatus>(
    known = SubscriptionStatus.serializer(),
    entries = SubscriptionStatus.entries,
    unknown = SubscriptionStatus.UNKNOWN,
)

internal object EntitlementSourceSerializer : UnknownTolerantEnumSerializer<EntitlementSource>(
    known = EntitlementSource.serializer(),
    entries = EntitlementSource.entries,
    unknown = EntitlementSource.UNKNOWN,
)
