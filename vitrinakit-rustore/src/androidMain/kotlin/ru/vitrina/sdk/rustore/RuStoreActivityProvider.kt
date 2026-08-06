package ru.vitrina.sdk.rustore

import android.app.Activity

/**
 * Supplies the currently resumed Android activity at the instant RuStore Pay UI is requested.
 *
 * The adapter uses the activity only as a foreground-presentation signal and never stores the
 * returned [Activity]. Return `null` while no activity is safe for presentation.
 */
fun interface RuStoreActivityProvider {
    /** Returns the activity that may present payment UI now, or `null` while backgrounded. */
    fun currentActivity(): Activity?
}
