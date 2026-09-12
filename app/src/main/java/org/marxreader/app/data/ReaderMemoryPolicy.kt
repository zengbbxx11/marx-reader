package org.marxreader.app.data

import android.app.ActivityManager
import android.content.Context

/** Character weights are a conservative cache budget, not a measurement of heap bytes. */
internal fun readerPageCacheBudget(memoryClass: Int?, lowRam: Boolean): Int = when {
    memoryClass == null || lowRam || memoryClass <= 128 -> 600_000
    memoryClass >= 256 -> 1_200_000
    else -> 900_000
}

internal fun readerPageCacheBudget(context: Context): Int {
    val manager = context.getSystemService(ActivityManager::class.java)
    return readerPageCacheBudget(manager?.memoryClass, manager?.isLowRamDevice ?: true)
}
