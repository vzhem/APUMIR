package com.vladimir.messenger.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Строит ссылку-приглашение для ЛЮБОГО контакта (не только своего профиля):
 * получатель открывает её → экран «Добавить контакт» с предзаполненным узлом.
 * Формат совпадает с p2pmessenger://add, который уже понимает InviteLinkParser.
 */
object ContactShareLink {
    private const val MAX_NAME_CHARS = 128

    fun build(nodeId: String, displayName: String): String {
        require(nodeId.matches(Regex("^pk_[0-9a-fA-F]{32}([0-9a-fA-F]{32})?$"))) {
            "Invalid contact node ID"
        }
        val name = displayName.trim().take(MAX_NAME_CHARS)
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
        return "p2pmessenger://add?node_id=$nodeId&name=$encodedName"
    }
}
