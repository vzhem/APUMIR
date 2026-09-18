package com.vladimir.messenger.data.group

/**
 * Наследование владения группой или каналом, когда владелец удалился
 * (просьба владельца 2026-09-11, `docs/AI_HANDOFF.md` пункт 10).
 *
 * Сеть без сервера: «владелец удалился» на чужом телефоне видно только по
 * молчанию - как давно этот телефон не получал от него ничего
 * (presence, сообщения, пакеты группы - всё пишется в рейтинг узлов,
 * `PeerRatingStore`). Правила:
 *
 *  - администратор может стать владельцем, если владелец молчал дольше
 *    [ADMIN_SILENCE_MS];
 *  - если администраторов в группе нет вовсе, то же может любой участник,
 *    но при более долгом молчании - [MEMBER_SILENCE_MS];
 *  - пока владелец на связи, права ни у кого не принимаются;
 *  - спор двух претендентов решает меньший nodeId - детерминированно,
 *    чтобы все телефоны сошлись на одном новом владельце.
 *
 * Функции чистые: только роль, состав и время - без базы и сети.
 */
object GroupOwnership {

    /** Сколько владелец может молчать, прежде чем администратор заберёт права: 90 дней. */
    const val ADMIN_SILENCE_MS: Long = 90L * 24 * 60 * 60 * 1000

    /**
     * Сколько владелец может молчать, прежде чем права заберёт участник
     * (только если администраторов в группе нет): 180 дней - вдвое дольше,
     * чтобы у живого владельца всегда было время вернуться раньше.
     */
    const val MEMBER_SILENCE_MS: Long = 180L * 24 * 60 * 60 * 1000

    /**
     * Сколько молчания нужно для захвата прав при моей роли [myRole].
     * Администратору - короткий срок, обычному участнику (когда админов нет) -
     * длинный. Владелец и неизвестные роли не получают ничего (null).
     */
    fun requiredSilenceMs(myRole: String, adminsExist: Boolean): Long? = when {
        GroupRole.isOwner(myRole) -> null
        myRole == GroupRole.ADMIN -> ADMIN_SILENCE_MS
        myRole == GroupRole.MEMBER && !adminsExist -> MEMBER_SILENCE_MS
        else -> null
    }

    /**
     * Могу ли я забрать владение при такой картине.
     *
     * [ownerLastSeenMs] - когда владелец последний раз выходил на связь
     * по наблюдениям ЭТОГО телефона; null - ни разу не видели. Незнакомого
     * владельца молчавшим не считаем: тот, кто вступил по ссылке большого
     * канала, мог просто ещё не получить от него ни одного пакета - отдавать
     * такому телефону права нельзя. Срок должен ПРОЙТИ: молчание ровно
     * [ADMIN_SILENCE_MS] ещё не считается - «дольше срока» (AI_HANDOFF 10).
     */
    fun canTakeOver(
        myRole: String,
        adminsExist: Boolean,
        ownerLastSeenMs: Long?,
        nowMs: Long,
    ): Boolean {
        val required = requiredSilenceMs(myRole, adminsExist) ?: return false
        if (ownerLastSeenMs == null || ownerLastSeenMs <= 0L) return false
        val silence = nowMs - ownerLastSeenMs
        return silence > required
    }

    /**
     * Спор претендентов: применять ли входящую заявку, если у меня владельцем
     * уже стоит [currentOwnerId] (не прежний владелец). Принимаем только
     * «меньшего» - тогда все телефоны в итоге сходятся на одном.
     */
    fun shouldReplaceClaimer(currentOwnerId: String, incomingClaimerId: String): Boolean =
        incomingClaimerId.isNotBlank() && incomingClaimerId < currentOwnerId

    /**
     * Кем становится прежний владелец после смены власти. При добровольной
     * передаче и при наследовании - администратором: уходит красиво, но не
     * остаётся без прав, а наследник при желании снимет его как обычного
     * администратора.
     */
    const val FORMER_OWNER_ROLE: String = GroupRole.ADMIN
}
