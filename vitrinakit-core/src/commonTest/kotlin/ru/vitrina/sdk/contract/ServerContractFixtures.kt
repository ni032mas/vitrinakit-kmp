package ru.vitrina.sdk.contract

/**
 * Representative server responses with synthetic identifiers.
 *
 * Each constant is the body of one response shape the SDK is expected to read. Keep them in step
 * with the server's published response shapes; [ServerContractDecodingTest] decodes every one.
 */
internal object ServerContractFixtures {
    const val SUBSCRIBER_ME_ONE_TIME = """
{
  "entitlements": [
    {
      "auto_renew_enabled": false,
      "expires_at": "2026-11-14T12:00:00Z",
      "has_access": true,
      "key": "premium",
      "plan_key": "premium",
      "product_key": "premium_monthly",
      "source": "yookassa",
      "state_version": 4,
      "status": "active"
    }
  ],
  "external_user_id": "customer-1",
  "has_access": true,
  "subscription": {
    "auto_renew_enabled": false,
    "billing_mode": "one_time",
    "current_period_end": "2026-11-14T12:00:00Z",
    "next_charge_at": null,
    "payment_method": null,
    "status": "active"
  }
}
"""

    const val SUBSCRIBER_ME_EXTENDED = """
{
  "entitlements": [
    {
      "auto_renew_enabled": false,
      "expires_at": "2026-12-14T12:00:00Z",
      "has_access": true,
      "key": "premium",
      "plan_key": "premium",
      "product_key": "premium_monthly",
      "source": "yookassa",
      "state_version": 5,
      "status": "active"
    }
  ],
  "external_user_id": "customer-1",
  "has_access": true,
  "subscription": {
    "auto_renew_enabled": false,
    "billing_mode": "one_time",
    "current_period_end": "2026-12-14T12:00:00Z",
    "next_charge_at": null,
    "payment_method": null,
    "status": "active"
  }
}
"""

    const val SUBSCRIBER_ME_RECURRING = """
{
  "entitlements": [
    {
      "auto_renew_enabled": true,
      "expires_at": "2026-11-14T12:00:00Z",
      "has_access": true,
      "key": "premium",
      "plan_key": "premium",
      "product_key": "premium_monthly",
      "source": "yookassa",
      "state_version": 4,
      "status": "active"
    }
  ],
  "external_user_id": "customer-1",
  "has_access": true,
  "subscription": {
    "auto_renew_enabled": true,
    "billing_mode": "recurring",
    "current_period_end": "2026-11-14T12:00:00Z",
    "next_charge_at": "2026-11-14T12:00:00Z",
    "payment_method": {
      "brand": "Visa",
      "last4": "4242"
    },
    "status": "active"
  }
}
"""

    const val SUBSCRIBER_ME_TWO_ROWS = """
{
  "entitlements": [
    {
      "auto_renew_enabled": false,
      "expires_at": "2026-11-14T12:00:00Z",
      "has_access": true,
      "key": "premium",
      "plan_key": "premium",
      "product_key": "premium_monthly",
      "source": "yookassa",
      "state_version": 4,
      "status": "active"
    }
  ],
  "external_user_id": "customer-1",
  "has_access": true,
  "subscription": {
    "auto_renew_enabled": true,
    "billing_mode": "recurring",
    "current_period_end": "2026-12-01T12:00:00Z",
    "next_charge_at": "2026-12-01T12:00:00Z",
    "payment_method": {
      "brand": "Visa",
      "last4": "4242"
    },
    "status": "active"
  }
}
"""

    const val PAYWALL_MAIN = """
{
  "placement_key": "main",
  "products": [
    {
      "amount_minor": 29900,
      "currency": "RUB",
      "entitlements": [
        {
          "app_id": "11111111-1111-1111-1111-111111111111",
          "created_at": "0001-01-01T00:00:00Z",
          "id": "77777777-7777-7777-7777-777777777777",
          "key": "premium",
          "name": "Premium",
          "updated_at": "0001-01-01T00:00:00Z"
        }
      ],
      "highlighted": true,
      "interval_count": 1,
      "interval_unit": "month",
      "plan_id": "55555555-5555-5555-5555-555555555555",
      "plan_key": "premium",
      "plan_name": "Premium",
      "price_id": "66666666-6666-6666-6666-666666666666",
      "product_id": "44444444-4444-4444-4444-444444444444",
      "product_key": "premium_monthly",
      "product_name": "Premium",
      "sort_order": 10
    }
  ]
}
"""

    const val PURCHASE_START_GOOGLE_FRESH = """
{
  "capability": "google_play",
  "expires_at": "2026-10-14T12:15:00Z",
  "presentation": {
    "account_binding": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "package_name": "com.example.app",
    "price_id": "monthly",
    "product_id": "premium.monthly"
  },
  "reference": "33333333-3333-3333-3333-333333333333",
  "status": "provider_ready"
}
"""

    const val PURCHASE_START_GOOGLE_REUSE = """
{
  "capability": "google_play",
  "expires_at": "2026-10-14T12:10:00Z",
  "presentation": {
    "account_binding": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "package_name": "com.example.app",
    "price_id": "monthly",
    "product_id": "premium.monthly"
  },
  "reference": "33333333-3333-3333-3333-333333333333",
  "status": "provider_ready"
}
"""

    const val PURCHASE_START_GOOGLE_REPLAY = """
{
  "capability": "google_play",
  "expires_at": "2026-10-14T12:15:00Z",
  "presentation": {
    "account_binding": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "package_name": "com.example.app",
    "price_id": "monthly",
    "product_id": "premium.monthly"
  },
  "reference": "33333333-3333-3333-3333-333333333333",
  "status": "provider_ready"
}
"""

    const val PURCHASE_START_RUSTORE_FRESH = """
{
  "capability": "rustore",
  "expires_at": "2026-10-14T12:15:00Z",
  "presentation": {
    "account_binding": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "package_name": "com.example.app",
    "product_id": "premium.monthly"
  },
  "reference": "33333333-3333-3333-3333-333333333333",
  "status": "provider_ready"
}
"""

    const val PURCHASE_ATTEMPT_VIEW_GOOGLE = """
{
  "amount_minor": 29900,
  "billing_mode": "store",
  "blocking_until": "2026-10-17T12:00:00Z",
  "capability": "google_play",
  "currency": "RUB",
  "expires_at": "2026-10-14T12:15:00Z",
  "intent": "purchase",
  "interval_count": 1,
  "interval_unit": "month",
  "presentation": {
    "account_binding": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "package_name": "com.example.app",
    "price_id": "monthly",
    "product_id": "premium.monthly"
  },
  "reference": "33333333-3333-3333-3333-333333333333",
  "status": "pending"
}
"""

    const val CHECKOUT_SESSION_HOSTED = """
{
  "confirmation_url": "https://pay.test",
  "expires_at": "2026-12-31T18:00:00Z",
  "id": "00000000-0000-4000-8000-000000000001",
  "payment_id": "00000000-0000-4000-8000-000000000002",
  "provider_payment_id": "pay_1",
  "purchase_attempt_reference": "33333333-3333-3333-3333-333333333333",
  "reused": false,
  "status": "pending"
}
"""
}
