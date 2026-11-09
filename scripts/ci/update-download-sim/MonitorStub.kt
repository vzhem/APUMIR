package com.vladimir.messenger.worker
import android.content.Context
internal object OfficialUpdateMonitor {
    val watched = mutableListOf<Long>()
    fun watch(context: Context, id: Long) { watched += id }
}
