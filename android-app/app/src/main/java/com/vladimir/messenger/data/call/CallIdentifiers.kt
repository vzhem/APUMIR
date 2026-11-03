package com.vladimir.messenger.data.call

import java.security.SecureRandom

/**
 * Ключи и идентификаторы звонка: 16 случайных байт, ключ — как есть,
 * идентификатор — шестнадцатеричной строкой.
 *
 * Вынесено из `CallManager.kt` по шву: менеджер звонков упирается в потолок
 * god-файлов, а порождение случайных байт не зависит ни от машины состояний,
 * ни от медиа. Генератор передаётся снаружи — у менеджера он один на всё
 * приложение, и второй SecureRandom здесь не нужен.
 */
internal object CallIdentifiers {

    fun randomKey(random: SecureRandom): ByteArray {
        val key = ByteArray(16)
        random.nextBytes(key)
        return key
    }

    fun newCallId(random: SecureRandom): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
