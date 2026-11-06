package com.vladimir.messenger.data.profile

import android.content.Context
import com.vladimir.messenger.data.rank.FeatureRankPolicy
import com.vladimir.messenger.data.rank.RankCapability
import com.vladimir.messenger.data.rank.RankEntitlementText
import com.vladimir.messenger.data.referral.ReferralRankStore

/**
 * Ранговые ворота видеоаватара: живое видео в профиле открывается с «Круга
 * друзей» (3 подтверждённых приглашения) — там же, где публикация историй.
 *
 * Основание: видеоаватар отправляет ваше содержимое наружу (кадр-постер
 * уходит собеседникам обычным аватаром), а до третьего ранга приложение живёт
 * в минимальном режиме (`docs/RANK_ENTITLEMENTS.md`). Статичный аватар при этом
 * остаётся бесплатным: здесь закрывается только видео.
 *
 * Отдельный файл по образцу `StoryRankGate`: правило проверяется не только на
 * кнопке, но и там, где файл реально сохраняется ([VideoAvatarInstaller]),
 * иначе старый экран или прямой вызов обошли бы ранг.
 */
object VideoAvatarRankGate {

    /** Открыт ли видеоаватар на текущем ранге. */
    fun canSet(context: Context): Boolean =
        FeatureRankPolicy.isAllowed(
            ReferralRankStore.qualifiedDirectCount(context),
            RankCapability.VIDEO_AVATAR,
        )

    /** Готовый текст отказа: та же строка `rank_feature_locked`, что и везде. */
    fun lockedNotice(context: Context): String =
        RankEntitlementText.lockedFor(context, RankCapability.VIDEO_AVATAR)

    /**
     * Строгий рубеж для установщика: UI может быть обойдён, поэтому проверка
     * повторяется там, где видеоаватар сохраняется. Сообщение техническое —
     * человеку показывается [lockedNotice].
     */
    fun requireCanSet(context: Context) {
        check(canSet(context)) {
            "VIDEO_AVATAR opens at rank " +
                "\"${FeatureRankPolicy.rankNameFor(RankCapability.VIDEO_AVATAR)}\" " +
                "(${RankCapability.VIDEO_AVATAR.minimumQualifiedReferrals} qualified referrals); " +
                "confirmed: ${ReferralRankStore.qualifiedDirectCount(context)}"
        }
    }
}
