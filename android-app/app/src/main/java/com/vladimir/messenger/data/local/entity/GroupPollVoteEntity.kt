package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Голос участника в опросе (р250): одна строка на пару «опрос — участник».
 *
 * Повторное голосование ЗАМЕНЯЕТ прежний выбор (`OnConflictStrategy.REPLACE`):
 * человек передумал - его голос переезжает, а не удваивается. Пустой
 * [choicesCsv] означает отозванный голос.
 */
@Entity(
    tableName = "group_poll_votes",
    primaryKeys = ["pollId", "voterId"],
    indices = [Index(value = ["pollId"], name = "index_group_poll_votes_pollId")],
)
data class GroupPollVoteEntity(
    val pollId: String,
    val voterId: String,
    /**
     * Имя проголосовавшего на момент голосования: список участников
     * расходится по сети часами, а итоги должны показывать имена сразу.
     * В анонимном опросе имена не показываются (но хранятся - иначе нельзя
     * отличить свой голос от чужого при переголосовании).
     */
    val voterName: String = "",
    /** Номера выбранных вариантов через запятую (с нуля); пусто - голос отозван. */
    val choicesCsv: String = "",
    val atMs: Long = 0L,
) {
    /** Отмеченные варианты числами: пустой список - голос отозван. */
    fun choices(): List<Int> =
        if (choicesCsv.isBlank()) {
            emptyList()
        } else {
            choicesCsv.split(',').mapNotNull { cell -> cell.trim().toIntOrNull() }
        }

    /** Голос отозван (или ещё не отмечен ни один вариант). */
    fun isEmptyVote(): Boolean = choices().isEmpty()
}
