package com.vladimir.messenger.data.rank

import android.content.Context
import com.vladimir.messenger.R
import com.vladimir.messenger.data.file.FileTransferRankPolicy

/**
 * Готовый текст отказа «эта возможность ещё закрыта ран­гом».
 *
 * Одна строка (`rank_feature_locked`) на все экраны: раньше личный чат, группа
 * и звонок собирали сообщение каждый по-своему, и формулировки расходились.
 * Здесь же решается, каким именем назвать ступень — оно берётся из общей
 * лестницы рангов [FileTransferRankPolicy], поэтому подсказка в чате и карточка
 * на экране «Ранги и возможности» говорят одно и то же.
 *
 * Android-зависимость (Context) терпима: это текст, а не правило. Само правило
 * остаётся чистым в [FeatureRankPolicy] и проверяется JVM-тестом.
 */
object RankEntitlementText {

    /**
     * «Отправка видео открывается с ранга «Проводник» — это 10 подтверждённых
     * приглашений. Сейчас подтверждено: 3.»
     */
    fun locked(context: Context, qualifiedDirectReferrals: Int, capability: RankCapability): String =
        context.getString(
            R.string.rank_feature_locked,
            RankFeatureSummary.titleOf(capability),
            FileTransferRankPolicy.entitlement(capability.minimumQualifiedReferrals).rankName,
            capability.minimumQualifiedReferrals,
            qualifiedDirectReferrals,
        )

    /** То же, но с готовым снимком ранга: callers считают его один раз на действие. */
    fun lockedFor(context: Context, capability: RankCapability): String =
        locked(
            context,
            com.vladimir.messenger.data.referral.ReferralRankStore.qualifiedDirectCount(context),
            capability,
        )
}
