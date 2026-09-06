package com.vladimir.messenger.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Строит ссылку-приглашение для ЛЮБОГО контакта (не только своего профиля):
 * получатель открывает её → экран «Добавить контакт» с предзаполненным узлом.
 *
 * Отдаёт короткий вид `apu://a/<узел>[/<никнейм>]`: адрес узла упакован в
 * плотную запись и втрое короче прежнего `p2pmessenger://add?node_id=pk_…`,
 * где сорок символов шестнадцатеричного ключа лезли в глаза и переносились
 * на три строки. Длинный вид остаётся только запасным - для узлов непривычной
 * длины, которые в плотную запись не укладываются.
 */
object ContactShareLink {
    private const val MAX_NAME_CHARS = 128

    fun build(nodeId: String, displayName: String, username: String = ""): String {
        require(nodeId.matches(Regex("^pk_[0-9a-fA-F]{32}([0-9a-fA-F]{32})?$"))) {
            "Invalid contact node ID"
        }
        // Никнейм едет в ссылке, если он известен: получатель сразу увидит
        // человека под его @именем, а не под заглушкой.
        ApuLink.build(nodeId, username.takeIf { it.isNotBlank() })?.let { return it }

        val name = displayName.trim().take(MAX_NAME_CHARS)
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
        return "p2pmessenger://add?node_id=$nodeId&name=$encodedName"
    }
}
