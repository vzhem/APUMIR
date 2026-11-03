package com.vladimir.messenger.ui.screens.chat

import android.content.Context
import com.vladimir.messenger.data.rank.FeatureRankPolicy
import com.vladimir.messenger.data.rank.RankCapability
import com.vladimir.messenger.data.rank.RankEntitlementText
import com.vladimir.messenger.data.referral.ReferralRankStore

/**
 * Ранговые ворота ИСТОРИЙ: публикация своей открыта с «Круга друзей» (3
 * подтверждённых приглашения), просмотр чужих — всегда.
 *
 * Отдельный файл, а не методы списка чатов: `ChatListViewModel.kt` упирается в
 * потолок god-файлов, а решение «можно ли публиковать» от его логики не
 * зависит — это то же правило [FeatureRankPolicy], что у чата, групп и звонков.
 */
internal object StoryRankGate {

    fun canPublish(context: Context): Boolean =
        FeatureRankPolicy.isAllowed(
            ReferralRankStore.qualifiedDirectCount(context),
            RankCapability.PUBLISH_STORY,
        )

    /** Готовый текст «Истории открываются с ранга «Круг друзей»…». */
    fun lockedNotice(context: Context): String =
        RankEntitlementText.lockedFor(context, RankCapability.PUBLISH_STORY)
}
