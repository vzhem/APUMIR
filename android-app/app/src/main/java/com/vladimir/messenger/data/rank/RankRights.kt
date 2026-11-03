package com.vladimir.messenger.data.rank

import android.content.Context
import android.net.Uri
import com.vladimir.messenger.R
import com.vladimir.messenger.data.referral.ReferralRankStore

/**
 * Права ранга для одного личного чата: скрепка, видео, звонки.
 *
 * Правила живут в [FeatureRankPolicy], тексты — в [RankEntitlementText]; здесь
 * только сборка снимка под состояние экрана. Отдельный класс, а не методы
 * ViewModel-и: `ChatDetailViewModel.kt` упирается в потолок god-файлов, и это
 * ровно тот шов, который можно проверять отдельно.
 */
class RankRights(private val context: Context) {

    /**
     * @param qualified сколько единиц ранга подтверждено сейчас
     * @param canAttach фото, файлы, голосовые, GIF и стикеры («Круг друзей»)
     * @param canSendVideo видео («Проводник»)
     * @param canCall исходящий голосовой звонок («Круг друзей»)
     * @param canVideoCall исходящий видеозвонок («Круг друзей»)
     */
    data class State(
        val qualified: Int,
        val canAttach: Boolean,
        val canSendVideo: Boolean,
        val canCall: Boolean,
        val canVideoCall: Boolean,
    )

    /** Текущий ранг: единственное место, где чат читает счётчик. */
    fun qualified(): Int = ReferralRankStore.qualifiedDirectCount(context)

    fun snapshot(): State {
        val qualified = qualified()
        return State(
            qualified = qualified,
            canAttach = FeatureRankPolicy.isAllowed(qualified, RankCapability.SEND_PHOTO),
            canSendVideo = FeatureRankPolicy.isAllowed(qualified, RankCapability.SEND_VIDEO),
            canCall = FeatureRankPolicy.isAllowed(qualified, RankCapability.CALL_VOICE),
            canVideoCall = FeatureRankPolicy.isAllowed(qualified, RankCapability.CALL_VIDEO),
        )
    }

    /** Открыта ли возможность на текущем ранге. */
    fun isAllowed(capability: RankCapability): Boolean =
        FeatureRankPolicy.isAllowed(qualified(), capability)

    /** Текст отказа по возможности на текущем ранге. */
    fun lockedHint(capability: RankCapability): String =
        RankEntitlementText.locked(context, qualified(), capability)

    /**
     * Подсказка под скрепкой: к отказу по вложениям добавляется напоминание,
     * что текст доступен всегда, — иначе человек думает, что не может писать.
     */
    fun attachLockedHint(state: State): String =
        if (state.canAttach) "" else
            RankEntitlementText.locked(context, state.qualified, RankCapability.SEND_PHOTO) +
                " " + context.getString(R.string.cdv_text_unlimited)

    /** Открыто ли видео («Проводник», 10). */
    fun videoLockedHint(state: State): String =
        if (state.canSendVideo) "" else
            RankEntitlementText.locked(context, state.qualified, RankCapability.SEND_VIDEO)

    /** Открыты ли звонки («Круг друзей», 3). */
    fun callLockedHint(state: State): String =
        if (state.canCall) "" else
            RankEntitlementText.locked(context, state.qualified, RankCapability.CALL_VOICE)

    /**
     * Видео раньше «Проводника»: true — отправлять нельзя. Проверка стоит ДО
     * подготовки файла — иначе человек ждёт разбор хэша тяжёлого видео, чтобы
     * увидеть отказ в самом конце.
     */
    fun isVideoLocked(uri: Uri?): Boolean {
        if (uri == null) return false
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return mime.startsWith("video/") && !isAllowed(RankCapability.SEND_VIDEO)
    }
}
