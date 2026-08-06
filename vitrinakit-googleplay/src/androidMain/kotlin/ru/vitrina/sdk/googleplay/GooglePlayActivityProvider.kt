package ru.vitrina.sdk.googleplay

import android.app.Activity

/**
 * Supplies the currently resumed Android activity at the instant Google Play UI is launched.
 *
 * The adapter never stores the returned [Activity]. Return `null` while no activity is safe for
 * presentation; the purchase then fails with a retryable adapter error.
 */
fun interface GooglePlayActivityProvider {
    /** Returns the activity that may launch billing UI now, or `null` while the app is backgrounded. */
    fun currentActivity(): Activity?
}
