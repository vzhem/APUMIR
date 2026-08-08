package com.vladimir.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * MTProto прокси для Telegram.
 * 
 * source: MANUAL / BOT / CHANNEL / WEB
 * lastCheck: timestamp последней проверки
 * failCount: количество последовательных провалов
 * isActive: выбран как активный для использования
 */
@Entity(tableName = "mtproto_proxies")
data class MtProtoProxyEntity(
    @PrimaryKey val id: String,          // hash(host:port:secret)
    val host: String,
    val port: Int,
    val secret: String,
    val username: String = "",
    val password: String = "",
    val type: String = "MTProto",
    val source: String = "MANUAL",        // MANUAL/BOT/CHANNEL/WEB
    val addedAt: Long = System.currentTimeMillis(),
    val lastCheck: Long = 0L,
    val failCount: Int = 0,
    val successCount: Int = 0,
    val isActive: Boolean = false,
    val disabledAt: Long = 0L,            // когда был помечен как нерабочий
) {
    fun toTgUri(): String = "tg://proxy?server=$host&port=$port&secret=$secret"
    
    fun isLikelyDead(): Boolean = 
        failCount >= 3 && (System.currentTimeMillis() - lastCheck) > 3600_000 // 1 час
}
