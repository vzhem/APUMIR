package com.vladimir.messenger.data.group

/**
 * Слова первого окна, которое видит человек, открывший ссылку-приглашение
 * извне (Telegram, WhatsApp, браузер).
 *
 * Раньше окно на любую ссылку спрашивало «Войти в группу? Отправить заявку на
 * вступление?». Для пересланной ссылки на пост канала это сбивало с толку:
 * человек шёл читать запись, а его звали в какую-то группу (владелец,
 * 2026-09-08: «нужно чтобы не путал людей»). Признаки канала, поста и
 * одобрения едут в самой ссылке - по ним и говорим.
 *
 * Чистый Kotlin без Android: проверяется обычным JUnit-тестом.
 */
data class InvitePrompt(
    val title: String,
    val body: String,
    val confirm: String,
)

/**
 * Что спросить у человека по признакам, которые приехали в ссылке.
 * `null` - ссылку разобрать не удалось: остаются прежние слова про группу.
 */
fun invitePromptFor(target: GroupInviteLinks.InviteTarget?): InvitePrompt {
    if (target == null) return groupPrompt(needsApproval = false)
    return when {
        // Ссылка на пост: человек пришёл ради записи, канал - лишь путь к ней.
        target.postTopicId != null -> postPrompt(target.needsApproval)
        target.isChannel -> channelPrompt(target.needsApproval)
        else -> groupPrompt(target.needsApproval)
    }
}

private fun postPrompt(needsApproval: Boolean): InvitePrompt {
    val tail = if (needsApproval) {
        "Чтобы его увидеть, нужна подписка: заявку одобрит владелец канала."
    } else {
        "Если вы ещё не подписаны, канал добавится в ваш список."
    }
    return InvitePrompt(
        title = "Открыть пост?",
        body = "Ссылка ведёт к посту в канале. $tail",
        confirm = "Открыть",
    )
}

private fun channelPrompt(needsApproval: Boolean): InvitePrompt {
    val tail = if (needsApproval) "Подписку одобряет владелец: отправить заявку?" else "Подписаться?"
    return InvitePrompt(
        title = "Подписаться на канал?",
        body = "Открыта ссылка-приглашение в канал. $tail",
        confirm = if (needsApproval) "Отправить заявку" else "Подписаться",
    )
}

private fun groupPrompt(needsApproval: Boolean): InvitePrompt {
    val tail = if (needsApproval) "Отправить заявку на вступление?" else "Войти?"
    return InvitePrompt(
        title = "Войти в группу?",
        body = "Открыта ссылка-приглашение в группу. $tail",
        confirm = if (needsApproval) "Отправить заявку" else "Войти",
    )
}

/**
 * Второе окно - итог попытки войти. Те же правила: канал называем каналом,
 * подписку - подпиской, а пришедшему за постом обещаем пост.
 */
fun joinedMessage(outcome: JoinOutcome.Joined): String {
    val title = "«" + outcome.title + "»"
    // Кнопка под текстом сама скажет «Открыть пост» или «Открыть чат».
    return if (outcome.isChannel) "Вы подписаны на канал $title" else "Вы вошли в группу $title"
}

fun requestSentMessage(outcome: JoinOutcome.RequestSent, forPost: Boolean): String {
    val what = if (outcome.isChannel) "канал" else "группа"
    // Без названия (владелец ещё не ответил) подставляем слово в нужном
    // падеже: «в группу», а не «в группа», как выходило раньше.
    val where = if (outcome.title.isBlank()) (if (outcome.isChannel) "канал" else "группу") else "«" + outcome.title + "»"
    // Пост сам не откроется: когда владелец выйдет на связь, канал появится в
    // списке, и запись найдётся в его ленте.
    val then = if (forPost) "канал появится в списке, а пост - в его ленте" else "$what появится в списке"
    // Ссылка без одобрения: владелец принимает сразу, и заявки не будет.
    // Обещать заявку в таком случае нельзя - ровно это и выглядело как
    // «заявки в канале не появляются».
    return if (outcome.needsApproval) {
        val ask = if (outcome.isChannel) "Заявка на подписку" else "Заявка"
        "$ask в $where отправлена владельцу. Как только он её одобрит, $then."
    } else {
        val doing = if (outcome.isChannel) "Подписываемся на $where" else "Входим в $where"
        "$doing: одобрение не требуется. Как только владелец будет на связи, $then."
    }
}
