package com.vladimir.messenger.data.rank

/**
 * Русские названия возможностей для экрана «Ранги и возможности».
 *
 * Живут в data, а не в ui, потому что список возможностей — это ДАННЫЕ
 * политики ранга, а не подпись одного экрана: из него же строятся подсказки
 * «откроется на следующем ранге» и таблица ступеней. В ui эти же строки
 * подставляются в готовом виде, поэтому контракт локализации
 * (`check-hardcoded-strings.py`) не затрагивается.
 */
object RankFeatureSummary {

    /** Название возможности по-русски, в том же порядке, что [RankCapability]. */
    fun titleOf(capability: RankCapability): String = when (capability) {
        RankCapability.SEND_TEXT -> "Текстовые сообщения"
        RankCapability.RECEIVE_MEDIA -> "Получение файлов, фото и видео"
        RankCapability.JOIN_COMMUNITIES -> "Вступление в группы и каналы"
        RankCapability.HEART_GAME -> "Игра «Сердечки»"
        RankCapability.SHARE_CONTACT -> "Отправка контакта"
        RankCapability.PROXY_MANUAL -> "Ручное добавление прокси"
        RankCapability.SEND_PHOTO -> "Отправка фото"
        RankCapability.SEND_VOICE -> "Голосовые сообщения"
        RankCapability.SEND_GIF -> "Отправка GIF"
        RankCapability.SEND_STICKER -> "Отправка стикеров"
        RankCapability.SEND_DOCUMENT -> "Отправка файлов"
        RankCapability.SHARE_LOCATION -> "Отправка геолокации"
        RankCapability.PUBLISH_STORY -> "Публикация историй"
        RankCapability.CALL_VOICE -> "Голосовые звонки"
        RankCapability.CALL_VIDEO -> "Видеозвонки"
        RankCapability.CREATE_POLL -> "Создание опросов"
        RankCapability.SEND_VIDEO -> "Отправка видео"
        RankCapability.CREATE_GROUP -> "Создание групп"
        RankCapability.PROXY_AUTOMATIC -> "Автосбор и автоматический выбор прокси"
        RankCapability.CREATE_CHANNEL -> "Создание каналов"
    }

    /** Все возможности, открытые на этом ранге, по-русски. */
    fun describe(qualifiedDirectReferrals: Int): List<String> =
        FeatureRankPolicy.unlockedFeatures(qualifiedDirectReferrals).map(::titleOf)

    /** Только те возможности, которые открывает именно эта ступень. */
    fun describeNewAt(qualifiedDirectReferrals: Int): List<String> {
        val previous = FeatureRankPolicy.unlockedFeatures(qualifiedDirectReferrals - 1).toSet()
        return FeatureRankPolicy.unlockedFeatures(qualifiedDirectReferrals)
            .filterNot { it in previous }
            .map(::titleOf)
    }
}
