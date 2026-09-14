package ru.vitrina.sdk.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The server reports a card summary it may not have captured.
 *
 * A payment confirmed before the platform started storing the summary comes back with `null`
 * fields, and a payload may omit them entirely; both describe a real subscriber whose profile
 * must keep decoding. Declaring these fields non-null made the whole profile fail to decode for
 * exactly those subscribers — the case a mock with a filled-in card never exercises.
 */
class SubscriberPaymentMethodDecodingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesExplicitNullsAsUnknown() {
        val method = json.decodeFromString<SubscriberPaymentMethod>("""{"brand":null,"last4":null}""")

        assertNull(method.brand)
        assertNull(method.last4)
    }

    @Test
    fun decodesWhenBothFieldsAreAbsent() {
        val method = json.decodeFromString<SubscriberPaymentMethod>("{}")

        assertNull(method.brand)
        assertNull(method.last4)
    }

    @Test
    fun keepsAPartialSummary() {
        val method = json.decodeFromString<SubscriberPaymentMethod>("""{"brand":"mir","last4":null}""")

        assertEquals("mir", method.brand)
        assertNull(method.last4)
    }
}
