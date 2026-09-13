package com.vladimir.messenger.data.backup

/**
 * Что лежит внутри файла резервной копии (`.apubak`) и под какими именами.
 *
 * Файл = [BackupCipher] поверх обычного ZIP. Первой записью всегда идёт
 * манифест ([BackupManifest]), чтобы восстановление могло проверить пароль и
 * совместимость, не распаковывая остальное. Дальше, в этом порядке:
 *
 * ```
 * manifest.txt                       - формат, версия базы, кто и когда
 * prefs/<имя>.txt                    - файлы настроек (PrefsCodec), см. PREFS_NAMES
 * secrets/file_exchange_x25519       - 32 байта: статический секрет обмена файлами
 * secrets/identity_signing_seed      - 32 байта: seed подписи личности
 * db/messenger_database              - Room-база целиком (чаты, контакты, сообщества, посты…)
 * avatar/<имя>                       - свои аватары (files/avatar)
 * preview/<transferId>.jpg           - превью отправленных фото
 * received/<transferId>/<имя>        - полученные файлы (по желанию, могут быть большими)
 * ```
 *
 * Секреты на телефоне хранятся завёрнутыми ключом Android Keystore, который
 * не покидает устройство и пропадает при удалении приложения. Поэтому в копию
 * они кладутся в открытом виде - её защищает пароль всего файла, - а при
 * восстановлении заворачиваются заново ключом уже нового устройства. Без этого
 * собеседники увидели бы после переустановки «сменившийся ключ» и отказались
 * бы принимать файлы (строгий TOFU).
 *
 * Что в копию НЕ попадает и почему:
 *  - куски незавершённых передач (`file_transfers/v1`) - ключи к ним заперты
 *    Keystore пофайлово, а сами передачи после переустановки всё равно
 *    начинают заново; такие передачи в восстановленной базе помечаются FAILED;
 *  - `apu_relay.sqlite` (хранение чужих сообщений для ретрансляции) - зашифрован
 *    ключом устройства и содержит не наши данные; на новом телефоне создаётся пустым;
 *  - `apu_relay_at_rest` и завёрнутые (`wrapped_*`) значения секретов - бесполезны
 *    без Keystore того телефона.
 *
 * Имена записей проверяются при чтении: никаких `..`, слэшей и посторонних
 * знаков внутри сегментов - файл с чужого устройства не должен уметь писать
 * куда попало.
 */
object BackupLayout {
    const val FILE_EXTENSION = ".apubak"
    const val MANIFEST = "manifest.txt"
    const val DB = "db/messenger_database"
    /** Хвост WAL рядом с базой - только у копий со старых Android без `VACUUM INTO`. */
    const val DB_WAL = "db/messenger_database-wal"
    const val PREFS_DIR = "prefs/"
    const val PREFS_SUFFIX = ".txt"
    const val SECRET_FILE_EXCHANGE = "secrets/file_exchange_x25519"
    const val SECRET_SIGNING_SEED = "secrets/identity_signing_seed"
    const val AVATAR_DIR = "avatar/"
    const val PREVIEW_DIR = "preview/"
    const val RECEIVED_DIR = "received/"

    /** Файлы SharedPreferences, которые едут в копию (порядок = порядок записи и восстановления). */
    val PREFS_NAMES: List<String> = listOf(
        "apu_message_sealer",          // публичные привязки собеседников для запечатывания сообщений
        "apu_peer_ratings",            // оценки узлов
        "apu_pending_referral",        // приглашение, ожидающее подтверждения
        "apu_referral_attribution",    // кто нас пригласил
        "apu_promo_codes",             // введённые промокоды
        "apu_referral_qualification",  // счётчик подтверждённых приглашений = ранг
        "apu_file_exchange",           // подписанная публичная привязка обмена файлами (без секрета)
        "apu_identity_signing",        // привязка личности (без seed)
        "p2p_prefs",                   // личность, имя, ник, тема, обои, аватар, квоты - последним
        // НЕ входят: `apu_relay_at_rest` (ключ Keystore старого телефона),
        // `apu_backup_schedule` (адрес файла и пароль автообновления - только этого телефона).
    )

    /** Значения, привязанные к Keystore этого телефона: на другом устройстве не расшифруются. */
    val PREFS_KEYS_NOT_PORTABLE: Map<String, Set<String>> = mapOf(
        "apu_file_exchange" to setOf("wrapped_x25519_secret_v1"),
        "apu_identity_signing" to setOf("wrapped_seed_v1"),
    )

    sealed interface Entry {
        data object Manifest : Entry
        data object Database : Entry
        data object DatabaseWal : Entry
        data class Prefs(val name: String) : Entry
        data object FileExchangeSecret : Entry
        data object SigningSeed : Entry
        data class Avatar(val name: String) : Entry
        data class Preview(val transferId: String) : Entry
        data class Received(val transferId: String, val name: String) : Entry
    }

    fun prefsEntry(name: String): String = PREFS_DIR + name + PREFS_SUFFIX

    /** Разобрать имя записи. null = незнакомая или небезопасная запись, её пропускают. */
    fun classify(entryName: String): Entry? {
        if (entryName.isEmpty() || entryName.length > 512) return null
        return when {
            entryName == MANIFEST -> Entry.Manifest
            entryName == DB -> Entry.Database
            entryName == DB_WAL -> Entry.DatabaseWal
            entryName == SECRET_FILE_EXCHANGE -> Entry.FileExchangeSecret
            entryName == SECRET_SIGNING_SEED -> Entry.SigningSeed
            entryName.startsWith(PREFS_DIR) && entryName.endsWith(PREFS_SUFFIX) -> {
                val name = entryName.substring(PREFS_DIR.length, entryName.length - PREFS_SUFFIX.length)
                if (isPrefsName(name)) Entry.Prefs(name) else null
            }
            entryName.startsWith(AVATAR_DIR) -> {
                val name = entryName.substring(AVATAR_DIR.length)
                if (isSafeName(name)) Entry.Avatar(name) else null
            }
            entryName.startsWith(PREVIEW_DIR) -> {
                val name = entryName.substring(PREVIEW_DIR.length)
                if (!name.endsWith(".jpg")) return null
                val id = name.removeSuffix(".jpg")
                if (isTransferId(id)) Entry.Preview(id) else null
            }
            entryName.startsWith(RECEIVED_DIR) -> {
                val rest = entryName.substring(RECEIVED_DIR.length)
                val slash = rest.indexOf('/')
                if (slash <= 0) return null
                val id = rest.substring(0, slash)
                val name = rest.substring(slash + 1)
                if (isTransferId(id) && isSafeName(name)) Entry.Received(id, name) else null
            }
            else -> null
        }
    }

    /** Один сегмент пути: буквы, цифры, точка, дефис, подчёркивание; не «.», не «..», не скрытый. */
    fun isSafeName(segment: String): Boolean =
        segment.isNotEmpty() && segment.length <= 200 &&
            !segment.startsWith('.') &&
            segment.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }

    fun isPrefsName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    fun isTransferId(id: String): Boolean =
        id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }
}
