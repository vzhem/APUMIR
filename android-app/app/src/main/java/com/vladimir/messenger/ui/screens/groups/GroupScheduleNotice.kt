package com.vladimir.messenger.ui.screens.groups

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.vladimir.messenger.data.group.GroupQuietSchedule
import com.vladimir.messenger.ui.i18n.AppLanguageHolder
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Recompute on minute/phone-zone/language changes without doing timezone work on the UI thread. */
@Composable
internal fun rememberGroupScheduleNotice(schedule: GroupQuietSchedule?): String {
    val language by AppLanguageHolder.language.collectAsState()
    val text by produceState("", schedule, language) {
        value = ""
        if (schedule == null) return@produceState
        var previousKey = ""
        while (true) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val key = "${now / 60_000}:${zone.id}"
            if (key != previousKey) {
                value = withContext(Dispatchers.Default) {
                    schedule.notice(now, zone, Locale.forLanguageTag(language.code))
                }
                previousKey = key
            }
            delay(1_000)
        }
    }
    return text
}
