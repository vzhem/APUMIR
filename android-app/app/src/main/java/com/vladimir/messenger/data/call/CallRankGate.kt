package com.vladimir.messenger.data.call

import android.content.Context
import com.vladimir.messenger.data.rank.FeatureRankPolicy
import com.vladimir.messenger.data.rank.RankCapability
import com.vladimir.messenger.data.rank.RankEntitlementText
import com.vladimir.messenger.data.referral.ReferralRankStore

/**
 * Ранговые ворота ИСХОДЯЩЕГО звонка.
 *
 * Вынесено из `CallManager.kt` отдельным файлом: менеджер звонков упирается в
 * потолок god-файлов, а решение «можно ли звонить» от его внутренностей не
 * зависит — это тот же [FeatureRankPolicy], что у чата и групп.
 *
 * Правило (решение владельца 2026-11-03): звонки и видеозвонки открываются с
 * третьего ранга «Круг друзей». Входящие принимаются всегда и рангом не
 * ограничены: чужой ранг не должен решать, ответит человек или нет. Проверка
 * стоит здесь, а не на кнопке, потому что экран звонка открывается ещё и по
 * маршруту `call?peerId=…` — deep link кнопку обходит.
 */
object CallRankGate {

    /**
     * null — звонить можно. Иначе готовый текст отказа для экрана звонка.
     *
     * @param video исходящий видеозвонок (тот же порог, отдельное право)
     */
    fun rejection(context: Context, video: Boolean): String? {
        val qualified = ReferralRankStore.qualifiedDirectCount(context)
        val capability = if (video) RankCapability.CALL_VIDEO else RankCapability.CALL_VOICE
        if (FeatureRankPolicy.isAllowed(qualified, capability)) return null
        return RankEntitlementText.locked(context, qualified, capability)
    }
}
