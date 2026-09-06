package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Сердечко профилю: кто кому поставил.
 *
 * Пара «чей профиль» + «кто поставил» — первичный ключ, поэтому один человек
 * может поднять рейтинг другому ровно один раз, сколько бы раз ни нажимал.
 * Повторное нажатие снимает сердечко.
 *
 * Храним не счётчик, а сами голоса: иначе один и тот же голос, пришедший
 * дважды (роевая рассылка, повтор после разрыва связи), накручивал бы рейтинг.
 */
@Entity(
    tableName = "profile_hearts",
    primaryKeys = ["ownerId", "voterId"],
    indices = [Index("ownerId")],
)
data class ProfileHeartEntity(
    /** Чей профиль оценили. */
    val ownerId: String,
    /** Кто поставил сердечко. */
    val voterId: String,
    val atMs: Long,
)
