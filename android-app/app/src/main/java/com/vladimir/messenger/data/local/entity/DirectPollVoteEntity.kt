package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Голос в личном опросе: одна строка на пару «опрос — голосующий».
 *
 * [voterId] — «self» для своего голоса или узел собеседника. Пустой
 * [choicesCsv] — голос отозван (повторное нажатие на свой вариант).
 */
@Entity(
    tableName = "direct_poll_votes",
    primaryKeys = ["pollId", "voterId"],
    indices = [Index(value = ["pollId"], name = "index_direct_poll_votes_pollId")],
)
data class DirectPollVoteEntity(
    val pollId: String,
    val voterId: String,
    /** Номера выбранных вариантов через запятую (с нуля); пусто — отозван. */
    val choicesCsv: String = "",
    val atMs: Long = 0L,
) {
    fun choices(): List<Int> =
        if (choicesCsv.isBlank()) emptyList()
        else choicesCsv.split(',').mapNotNull { it.trim().toIntOrNull() }
}
