package com.vladimir.messenger.data.referral

import android.content.Context
import java.util.Locale

/**
 * Промокоды: разовая прибавка к рангу.
 *
 * Промокод НЕ подделывает приглашения. Он кладётся в отдельную копилку, и ранг
 * считается как «подтверждённые друзья + промо-бонус». Так видно, что пришло от
 * живых приглашений, а что подарено кодом, и приглашения остаются честными.
 *
 * Проверка кода целиком на телефоне: сервера нет, сверить код не с чем.
 * Отсюда границы, которые важно понимать:
 *  - код нельзя отозвать после выпуска - он живёт в уже установленных сборках;
 *  - узнавший код применит его у себя, поэтому коды стоит раздавать адресно;
 *  - повторно на ОДНОМ телефоне код не сработает, но на новой установке - да.
 * Для подарочных бонусов этого достаточно; для платных - понадобится сервер.
 */
object PromoCodes {

    /** Прибавка за один код. */
    const val BONUS_PER_CODE = 10

    /** Сколько всего можно набрать промокодами - защита от бесконечной накрутки. */
    const val MAX_PROMO_BONUS = 100

    private const val PREFS = "apu_promo_codes"
    private const val KEY_BONUS = "promo_bonus_v1"
    private const val KEY_USED = "promo_used_v1"

    /**
     * Выпущенные коды.
     *
     * Хранятся в верхнем регистре без разделителей: при вводе строка
     * приводится к тому же виду, поэтому «apu-start-2026», «APU START 2026» и
     * «ApuStart2026» - один и тот же код.
     */
    private val ISSUED = setOf(
        "APUSTART2026",
        "APUFRIENDS10",
        "APUMESHGOLD",
        "APUPIONEER26",
        "APUSIGNAL10",
    )

    /** Итог ввода - экран показывает по нему понятное человеку сообщение. */
    enum class Result {
        /** Код принят, бонус начислен. */
        APPLIED,

        /** Такого кода нет. */
        UNKNOWN,

        /** Этот код на этом телефоне уже использован. */
        ALREADY_USED,

        /** Промокодами набран предел. */
        LIMIT_REACHED,
    }

    /** Приведение к единому виду: регистр и разделители не важны. */
    fun normalize(raw: String): String =
        raw.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Текущий промо-бонус этого телефона. */
    fun bonus(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BONUS, 0)
            .coerceIn(0, MAX_PROMO_BONUS)

    /** Коды, уже использованные на этом телефоне. */
    fun usedCodes(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_USED, emptySet())
            .orEmpty()

    /**
     * Применить код.
     *
     * `@Synchronized` и `commit()` намеренно: два быстрых нажатия не должны
     * начислить бонус дважды, а запись обязана лечь на диск до возврата - иначе
     * при мгновенном закрытии приложения бонус потерялся бы.
     */
    @Synchronized
    fun redeem(context: Context, rawCode: String): Result {
        val code = normalize(rawCode)
        if (code.isBlank() || code !in ISSUED) return Result.UNKNOWN

        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val used = prefs.getStringSet(KEY_USED, emptySet()).orEmpty()
        if (code in used) return Result.ALREADY_USED

        val current = prefs.getInt(KEY_BONUS, 0).coerceIn(0, MAX_PROMO_BONUS)
        if (current >= MAX_PROMO_BONUS) return Result.LIMIT_REACHED

        val next = (current + BONUS_PER_CODE).coerceAtMost(MAX_PROMO_BONUS)
        prefs.edit()
            .putInt(KEY_BONUS, next)
            // Новый набор, а не изменение прежнего: SharedPreferences хранит
            // ссылку на тот же объект, и правка на месте не сохранилась бы.
            .putStringSet(KEY_USED, used + code)
            .commit()
        // Ранг вырос - экраны должны показать новый, не дожидаясь перезапуска.
        ReferralRankStore.notifyChanged()
        return Result.APPLIED
    }
}
