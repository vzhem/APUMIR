package com.vladimir.messenger.data.file

import android.content.Context
import android.net.Uri
import com.vladimir.messenger.data.local.dao.FileTransferDao
import com.vladimir.messenger.data.local.entity.FileTransferChunkEntity
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.p2p_core.FileTransferManifestFfi
import uniffi.p2p_core.createFileTransferManifest
import uniffi.p2p_core.createFileKeyEnvelope
import uniffi.p2p_core.encryptFileTransferChunk

/** Prepares encrypted durable chunks locally. It does not publish or claim delivery. */
class OutgoingFilePreparationService private constructor(
    @ApplicationContext private val context: Context,
    private val transferDao: FileTransferDao,
    private val store: FileTransferChunkStore,
    private val keyAccess: KeyAccess,
    private val exchangeAccess: ExchangeAccess?,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        transferDao: FileTransferDao,
        peerStore: FileExchangePeerStore,
    ) : this(
        context.applicationContext,
        transferDao,
        FileTransferChunkStore.forApplication(context.applicationContext),
        ProductionKeyAccess(context.applicationContext),
        ProductionExchangeAccess(context.applicationContext, peerStore),
    )

    internal constructor(
        context: Context,
        transferDao: FileTransferDao,
        store: FileTransferChunkStore,
        keyAccess: KeyAccess,
        isolatedTest: Boolean,
    ) : this(context.applicationContext, transferDao, store, keyAccess, null) {
        require(isolatedTest) { "Custom file preparation dependencies are test-only" }
    }

    internal interface KeyAccess {
        fun create(transferId: String)
        fun <T> withExisting(transferId: String, operation: (ByteArray) -> T): T
    }

    internal interface ExchangeAccess {
        fun ownBinding(): ByteArray
        suspend fun recipientBinding(nodeId: String): ByteArray
        fun <T> withSecret(operation: (ByteArray) -> T): T
    }

    private class ProductionExchangeAccess(
        private val context: Context,
        private val peerStore: FileExchangePeerStore,
    ) : ExchangeAccess {
        override fun ownBinding(): ByteArray =
            FileExchangeKeyStore.publicBinding(context) ?: error("Local file exchange binding unavailable")

        override suspend fun recipientBinding(nodeId: String): ByteArray =
            peerStore.bindingFor(nodeId) ?: error("Recipient file exchange binding is not pinned")

        override fun <T> withSecret(operation: (ByteArray) -> T): T =
            FileExchangeKeyStore.withExistingSecret(context, operation)
    }

    private class ProductionKeyAccess(private val context: Context) : KeyAccess {
        override fun create(transferId: String) {
            FileTransferKeyVault.withOrCreateKey(context, transferId) { key ->
                check(key.size == FileTransferKeyEnvelope.KEY_BYTES)
            }
        }

        override fun <T> withExisting(transferId: String, operation: (ByteArray) -> T): T =
            FileTransferKeyVault.withExistingKey(context, transferId, operation)
    }

    data class PreparedTransfer(
        val transferId: String,
        val messageId: String,
        val displayName: String,
        val mediaType: String,
        val totalBytes: Long,
        val chunkCount: Long,
        val fileSha256: String,
    )

    suspend fun prepare(
        source: Uri,
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        qualifiedDirectReferrals: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): PreparedTransfer = prepareSource(
        Source.Content(source),
        messageId,
        chatId,
        recipientNodeId,
        qualifiedDirectReferrals,
        nowMs,
    )

    /**
     * То же для файла из своей папки приложения (рой, этап 9: автор раздаёт
     * копию файла группы, участник - полученный файл). Имя и тип берутся из
     * визитки файла, а не из провайдера. Ранг не проверяется: раздача
     * группе - не «отправка вложения», а пересылка того, что группа уже
     * приняла; сам файл прошёл проверку ранга у автора при отправке.
     */
    suspend fun prepareFromFile(
        source: java.io.File,
        displayName: String,
        mediaType: String,
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PreparedTransfer = prepareSource(
        Source.Local(source, displayName, mediaType),
        messageId,
        chatId,
        recipientNodeId,
        qualifiedDirectReferrals = null,
        nowMs = nowMs,
    )

    /**
     * Общая копия файла ГРУППЫ (K2, v11.70.25): манифест с меткой группы
     * вместо получателя (`grp_<id>`, ядро `create_group_file_manifest`), один
     * ключ файла и куски, зашифрованные ОДИН раз. Строка передачи -
     * `OUTGOING`/`SEEDING` (обычный передатчик её не трогает); куски из неё
     * отдаёт любому участнику GroupFileSeeder, а ключ каждому просителю
     * заворачивает [wrapGroupKey]. Повторный вызов для того же файла и
     * группы возвращает готовую копию, не шифруя заново.
     *
     * Возвращает null, если ядро групповых манифестов не умеет (старое
     * `.so`): тогда рой отдаёт файл по-старому, копией на просителя.
     */
    suspend fun prepareGroupCopy(
        source: java.io.File,
        displayName: String,
        mediaType: String,
        messageId: String,
        groupId: String,
        expectedSha256: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PreparedTransfer? = withContext(Dispatchers.IO) {
        require(messageId.isNotBlank() && groupId.isNotBlank()) { "Missing file message binding" }
        transferDao.getForFile(groupId, expectedSha256)
            .firstOrNull { it.direction == "OUTGOING" && it.state == "SEEDING" && it.expiresAtMs > nowMs }
            ?.let { row ->
                if (store.readManifest(row.transferId) != null && transferDao.countChunks(row.transferId) == row.chunkCount) {
                    return@withContext PreparedTransfer(
                        transferId = row.transferId,
                        messageId = row.messageId,
                        displayName = row.displayName,
                        mediaType = row.mediaType,
                        totalBytes = row.totalBytes,
                        chunkCount = row.chunkCount,
                        fileSha256 = row.fileSha256,
                    )
                }
                // Недошифрованная копия (приложение убили посреди подготовки): заново.
                runCatching { store.deleteTransfer(row.transferId) }
                transferDao.deleteTransfer(row.transferId)
            }
        val senderNodeId = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getString("node_id", null)
            ?: throw IllegalStateException("Local identity is unavailable")
        val local = Source.Local(source, displayName, mediaType)
        val inspected = FileTransferSourceInspector.inspect(
            providerDisplayName = displayName,
            providerMediaType = mediaType,
            declaredSize = source.length().takeIf { source.isFile },
        ) { local.open(context) }
        check(inspected.sha256 == expectedSha256) { "Group file copy does not match its card" }
        val expiresAtMs = Math.addExact(nowMs, TRANSFER_TTL_MS)
        val manifest = try {
            uniffi.p2p_core.createGroupFileManifest(
                senderNodeId,
                com.vladimir.messenger.util.GroupFileMarker.scope(groupId),
                inspected.displayName,
                inspected.mediaType,
                inspected.sizeBytes.toULong(),
                inspected.sha256.hexToBytes(),
                nowMs,
                expiresAtMs,
            )
        } catch (error: Throwable) {
            // Ядро без K2 (не должно случаться: APK несёт своё ядро) - рой
            // отдаст по-старому, копией на каждого просителя.
            android.util.Log.w("OutgoingFilePreparation", "group manifest unavailable: ${error.message}")
            return@withContext null
        }
        val entity = manifest.toEntity(messageId, groupId, "", nowMs).copy(state = "PREPARING")
        check(transferDao.insertNewTransfer(entity)) { "Transfer ID collision" }
        try {
            check(store.storeManifest(manifest.transferIdHex, manifest.manifestBytes)) {
                "New transfer unexpectedly reused a manifest"
            }
            keyAccess.create(manifest.transferIdHex)
            // Сразу в SEEDING, минуя PREPARED: иначе обычный передатчик успел
            // бы подхватить строку как личную передачу без конверта.
            stageChunks(local, manifest, inspected.sha256, store, finalState = "SEEDING")
            PreparedTransfer(
                transferId = manifest.transferIdHex,
                messageId = messageId,
                displayName = manifest.displayName,
                mediaType = manifest.mediaType,
                totalBytes = manifest.fileSize.toLong(),
                chunkCount = manifest.chunkCount.toLong(),
                fileSha256 = manifest.fileSha256Hex,
            )
        } catch (error: Exception) {
            val filesRemoved = runCatching { store.deleteTransfer(manifest.transferIdHex) }
                .getOrDefault(false)
            if (filesRemoved) transferDao.deleteTransfer(manifest.transferIdHex)
            throw error
        }
    }

    /**
     * Конверт с ключом общей копии для конкретного просителя (K2): тот же
     * `create_file_key_envelope`, что и у личной передачи, - подписан моим
     * ключом обмена, вскрывается только его ключом, привязан к байтам
     * общего манифеста. Бросает «binding is not pinned», если ключ просителя
     * ещё не закреплён (рой тогда просит HELLO и повторяет позже).
     */
    suspend fun wrapGroupKey(transferId: String, recipientNodeId: String): ByteArray = withContext(Dispatchers.IO) {
        val exchange = exchangeAccess ?: error("Exchange access unavailable")
        val manifestBytes = store.readManifest(transferId) ?: error("Group copy manifest missing")
        val ownBinding = exchange.ownBinding()
        val recipientBinding = exchange.recipientBinding(recipientNodeId)
        try {
            exchange.withSecret { exchangeSecret ->
                keyAccess.withExisting(transferId) { fileKey ->
                    createFileKeyEnvelope(ownBinding, recipientBinding, exchangeSecret, manifestBytes, fileKey)
                }
            }
        } finally {
            ownBinding.fill(0)
            recipientBinding.fill(0)
        }
    }

    /** Откуда читать файл: системный провайдер (выбор из окна) или файл приложения. */
    private sealed class Source {
        abstract fun open(context: Context): java.io.InputStream

        class Content(val uri: Uri) : Source() {
            override fun open(context: Context): java.io.InputStream =
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot reopen selected file")
        }

        class Local(val file: java.io.File, val displayName: String, val mediaType: String) : Source() {
            override fun open(context: Context): java.io.InputStream {
                require(file.isFile) { "Source file is missing" }
                return file.inputStream()
            }
        }
    }

    private suspend fun prepareSource(
        source: Source,
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        /** null - проверка ранга не нужна (раздача файла группы). */
        qualifiedDirectReferrals: Int?,
        nowMs: Long,
    ): PreparedTransfer = withContext(Dispatchers.IO) {
        require(messageId.isNotBlank() && chatId.isNotBlank()) { "Missing file message binding" }
        val senderNodeId = context.getSharedPreferences("p2p_prefs", Context.MODE_PRIVATE)
            .getString("node_id", null)
            ?: throw IllegalStateException("Local identity is unavailable")
        val inspected = when (source) {
            is Source.Content -> AndroidFileSelection.inspect(context.contentResolver, source.uri)
            is Source.Local -> FileTransferSourceInspector.inspect(
                providerDisplayName = source.displayName,
                providerMediaType = source.mediaType,
                declaredSize = source.file.length().takeIf { source.file.isFile },
            ) { source.open(context) }
        }
        if (qualifiedDirectReferrals != null) {
            FileTransferRankPolicy.requireCanSend(
                qualifiedDirectReferrals = qualifiedDirectReferrals,
                mediaType = inspected.mediaType,
                sizeBytes = inspected.sizeBytes,
            )
        }
        val expiresAtMs = Math.addExact(nowMs, TRANSFER_TTL_MS)
        val manifest = createFileTransferManifest(
            senderNodeId,
            recipientNodeId,
            inspected.displayName,
            inspected.mediaType,
            inspected.sizeBytes.toULong(),
            inspected.sha256.hexToBytes(),
            nowMs,
            expiresAtMs,
        )
        val entity = manifest.toEntity(messageId, chatId, recipientNodeId, nowMs)
        check(transferDao.insertNewTransfer(entity)) { "Transfer ID collision" }
        try {
            check(store.storeManifest(manifest.transferIdHex, manifest.manifestBytes)) {
                "New transfer unexpectedly reused a manifest"
            }
            keyAccess.create(manifest.transferIdHex)
            exchangeAccess?.let { exchange ->
                val ownBinding = exchange.ownBinding()
                val recipientBinding = exchange.recipientBinding(recipientNodeId)
                val keyEnvelope = exchange.withSecret { exchangeSecret ->
                    keyAccess.withExisting(manifest.transferIdHex) { fileKey ->
                        createFileKeyEnvelope(
                            ownBinding,
                            recipientBinding,
                            exchangeSecret,
                            manifest.manifestBytes,
                            fileKey,
                        )
                    }
                }
                try {
                    check(store.storeKeyEnvelope(manifest.transferIdHex, keyEnvelope))
                } finally {
                    keyEnvelope.fill(0)
                    ownBinding.fill(0)
                    recipientBinding.fill(0)
                }
            }
            stageChunks(source, manifest, inspected.sha256, store)
            // Раунд 43: для картинок кладём превью рядом - пузырь в чате
            // показывает фото, а не только имя файла и размер. Превью - вся
            // картинка целиком (уменьшенная, с теми же пропорциями), а не
            // квадрат из середины: отправитель, открыв своё фото на весь экран,
            // должен видеть то же, что и получатель.
            if (inspected.mediaType.startsWith("image/")) {
                runCatching {
                    val dir = java.io.File(context.noBackupFilesDir, "file_preview/v1")
                    val previewUri = when (source) {
                        is Source.Content -> source.uri
                        is Source.Local -> Uri.fromFile(source.file)
                    }
                    com.vladimir.messenger.util.PhotoPreview.write(
                        context, previewUri, java.io.File(dir, manifest.transferIdHex + ".jpg"),
                    )
                }
            }
            PreparedTransfer(
                transferId = manifest.transferIdHex,
                messageId = messageId,
                displayName = manifest.displayName,
                mediaType = manifest.mediaType,
                totalBytes = manifest.fileSize.toLong(),
                chunkCount = manifest.chunkCount.toLong().also {
                    check(it >= 0) { "Chunk count exceeds Android durable range" }
                },
                fileSha256 = manifest.fileSha256Hex,
            )
        } catch (error: Exception) {
            val filesRemoved = runCatching { store.deleteTransfer(manifest.transferIdHex) }
                .getOrDefault(false)
            if (filesRemoved) transferDao.deleteTransfer(manifest.transferIdHex)
            throw error
        }
    }

    private suspend fun stageChunks(
        source: Source,
        manifest: FileTransferManifestFfi,
        expectedSha256: String,
        store: FileTransferChunkStore,
        /** Состояние строки после последнего куска: PREPARED (личная) или SEEDING (общая копия группы, K2). */
        finalState: String = "PREPARED",
    ) {
        val input = source.open(context)
        val digest = MessageDigest.getInstance("SHA-256")
        var transferred = 0L
        input.use { stream ->
            val chunkCount = manifest.chunkCount.toLong().also {
                check(it >= 0) { "Chunk count exceeds Android durable range" }
            }
            var index = 0L
            while (index < chunkCount) {
                val expected = minOf(
                    manifest.chunkSize.toLong(),
                    manifest.fileSize.toLong() - transferred,
                ).toInt()
                val plaintext = ByteArray(expected)
                try {
                    readExactly(stream, plaintext)
                    digest.update(plaintext)
                    val ciphertext = keyAccess.withExisting(manifest.transferIdHex) { key ->
                        encryptFileTransferChunk(
                            manifest.manifestBytes,
                            key,
                            index.toULong(),
                            plaintext,
                        )
                    }
                    try {
                        val stored = store.storeEncryptedChunk(
                            manifest.transferIdHex,
                            index,
                            ciphertext,
                        )
                        transferDao.upsertChunk(
                            FileTransferChunkEntity(
                                transferId = manifest.transferIdHex,
                                chunkIndex = index,
                                state = "STAGED",
                                ciphertextBytes = stored.ciphertextBytes,
                                chunkSha256 = stored.sha256,
                                updatedAtMs = System.currentTimeMillis(),
                            )
                        )
                    } finally {
                        ciphertext.fill(0)
                    }
                } finally {
                    plaintext.fill(0)
                }
                transferred += expected
                check(
                    transferDao.advanceProgress(
                        transferId = manifest.transferIdHex,
                        state = if (index + 1 == chunkCount) finalState else "PREPARING",
                        completedChunks = index + 1,
                        transferredBytes = transferred,
                        updatedAtMs = System.currentTimeMillis(),
                        errorCode = null,
                    ) == 1
                ) { "Cannot persist monotonic file preparation progress" }
                index++
            }
            check(stream.read() == -1) { "Selected file grew during preparation" }
        }
        check(transferred == manifest.fileSize.toLong()) { "Selected file was truncated" }
        check(digest.digest().toHex() == expectedSha256) { "Selected file changed after inspection" }
        if (manifest.chunkCount.toLong() == 0L) {
            check(
                transferDao.advanceProgress(
                    transferId = manifest.transferIdHex,
                    state = finalState,
                    completedChunks = 0,
                    transferredBytes = 0,
                    updatedAtMs = System.currentTimeMillis(),
                    errorCode = null,
                ) == 1
            ) { "Cannot mark empty file prepared" }
        }
    }

    private fun readExactly(input: java.io.InputStream, output: ByteArray) {
        var offset = 0
        while (offset < output.size) {
            val read = input.read(output, offset, output.size - offset)
            check(read > 0) { "Selected file was truncated" }
            offset += read
        }
    }

    private fun FileTransferManifestFfi.toEntity(
        messageId: String,
        chatId: String,
        recipientNodeId: String,
        nowMs: Long,
    ): FileTransferEntity = FileTransferEntity(
        transferId = transferIdHex,
        messageId = messageId,
        chatId = chatId,
        peerNodeId = recipientNodeId,
        direction = "OUTGOING",
        displayName = displayName,
        mediaType = mediaType,
        totalBytes = fileSize.toLong(),
        chunkSize = chunkSize.toInt(),
        chunkCount = chunkCount.toLong().also {
            check(it >= 0) { "Chunk count exceeds Android durable range" }
        },
        fileSha256 = fileSha256Hex,
        state = "PREPARING",
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
        updatedAtMs = nowMs,
    )

    private fun String.hexToBytes(): ByteArray {
        require(length == 64 && all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(32) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TRANSFER_TTL_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
