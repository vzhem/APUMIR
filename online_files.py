import re

# ═══════════════════════════════════════════════════════════════════
# 1. Файловый передатчик: ТОЛЬКО прямой QUIC, НИКОГДА через relay
# ═══════════════════════════════════════════════════════════════════
p = 'android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferSender.kt'
s = open(p, encoding='utf-8').read()

# Меняем sendItem: если QUIC недоступен → НЕ отправляем (ждём получателя)
old = '''            // Параллельный QUIC-поток: сначала пробуем БЕЗ relay queue (быстро, без
            // блокировки текстовой очереди); если получатель недоступен напрямую —
            // обычный sendMessage (durable relay, медленно но надёжно).
            val directOk = directTransport?.invoke(transfer.peerNodeId, text) ?: false
            if (!directOk) {
                transport.send(messageIdFor(fragmentIndex), transfer.chatId, transfer.peerNodeId, text)
            }
            sleeper(PACKET_GAP_MS)'''
new = '''            // Архитектурное решение владельца: файлы ТОЛЬКО напрямую (QUIC), НИКОГДА через
            // relay queue. Если получатель офлайн — передача ставится на паузу (WAITING_RECIPIENT)
            // и возобновляется когда получатель появится. Текст ходит через relay как раньше.
            val directOk = directTransport?.invoke(transfer.peerNodeId, text) ?: false
            if (!directOk) {
                Log.i(TAG, "Recipient not directly reachable — pausing transfer ${transfer.transferId}")
                throw RecipientOfflineException(transfer.transferId)
            }
            sleeper(PACKET_GAP_MS)'''
assert old in s, "sendItem anchor"
s = s.replace(old, new, 1)

# Добавляем exception класс
anchor = '''    data class PumpSummary('''
addition = '''    /** Получатель офлайн — файл ждёт когда он появится (только прямая доставка). */
    class RecipientOfflineException(val transferId: String) : Exception("Recipient offline")

    data class PumpSummary('''
assert anchor in s, "PumpSummary anchor"
s = s.replace(anchor, addition, 1)

# pumpTransfer: обрабатываем RecipientOfflineException → ставим WAITING_RECIPIENT
old = '''        val result = runCatching { pumpTransfer(transfer) }
        result.getOrNull()?.let { pumped++; packets += it }
        if (result.isFailure) {
            failures++
            Log.w(TAG, "File transfer pump failed for ${transfer.transferId}: ${result.exceptionOrNull()?.message}")
        }'''
new = '''        val result = runCatching { pumpTransfer(transfer) }
        result.getOrNull()?.let { pumped++; packets += it }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is RecipientOfflineException) {
                // Получатель офлайн: ставим на паузу, НЕ считаем ошибкой.
                // Возобновится автоматически когда получатель появится (peer_discovered → pump).
                advance(transfer, newState = "WAITING_RECIPIENT")
                Log.i(TAG, "Transfer ${transfer.transferId} waiting for recipient to come online")
            } else {
                failures++
                Log.w(TAG, "File transfer pump failed for ${transfer.transferId}: ${error?.message}")
            }
        }'''
assert old in s, "pump failure handler anchor"
s = s.replace(old, new, 1)

# getActiveOutgoing: добавить WAITING_RECIPIENT в список активных (чтобы возобновлялись)
# DAO уже включает PREPARED/TRANSFERRING/SENT — WAITING_RECIPIENT тоже нужно
# Но мы НЕ хотим чтобы pump цикл постоянно пытался слать WAITING_RECIPIENT...
# Решение: sender сам решает — WAITING_RECIPIENT возобновляется только по peer_discovered
# Значит DAO НЕ должен включать WAITING_RECIPIENT в getActiveOutgoing

open(p, 'w', encoding='utf-8').write(s)
print("Sender: files ONLY direct, offline → WAITING_RECIPIENT")

# ═══════════════════════════════════════════════════════════════════
# 2. DAO: getWaitingRecipient — для возобновления по peer_discovered
# ═══════════════════════════════════════════════════════════════════
p = 'android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/FileTransferDao.kt'
s = open(p, encoding='utf-8').read()
anchor = '''    @Query("SELECT * FROM file_transfers WHERE state = 'CANCELLED'")'''
addition = '''    @Query("SELECT * FROM file_transfers WHERE direction = 'OUTGOING' AND state = 'WAITING_RECIPIENT'")
    suspend fun getWaitingRecipient(): List<FileTransferEntity>

    @Query(
        """
        UPDATE file_transfers
        SET state = 'TRANSFERRING', updatedAtMs = :nowMs
        WHERE direction = 'OUTGOING' AND state = 'WAITING_RECIPIENT'
        """
    )
    suspend fun resumeAllWaitingRecipient(nowMs: Long): Int

    @Query("SELECT * FROM file_transfers WHERE state = 'CANCELLED'")'''
assert anchor in s, "DAO anchor"
s = s.replace(anchor, addition, 1)
open(p, 'w', encoding='utf-8').write(s)
print("DAO: getWaitingRecipient + resumeAllWaitingRecipient")

# ═══════════════════════════════════════════════════════════════════
# 3. Fake DAO для тестов
# ═══════════════════════════════════════════════════════════════════
p = 'android-app/app/src/test/java/com/vladimir/messenger/data/file/FakeFileTransferDao.kt'
s = open(p, encoding='utf-8').read()
anchor = '''    override suspend fun getCancelled(): List<FileTransferEntity> ='''
addition = '''    override suspend fun getWaitingRecipient(): List<FileTransferEntity> =
        transfers.values.filter { it.direction == "OUTGOING" && it.state == "WAITING_RECIPIENT" }

    override suspend fun resumeAllWaitingRecipient(nowMs: Long): Int {
        var n = 0
        transfers.replaceAll { _, e ->
            if (e.direction == "OUTGOING" && e.state == "WAITING_RECIPIENT") {
                n++; e.copy(state = "TRANSFERRING", updatedAtMs = nowMs)
            } else e
        }
        observe.value += 1
        return n
    }

    override suspend fun getCancelled(): List<FileTransferEntity> ='''
assert anchor in s, "Fake DAO anchor"
s = s.replace(anchor, addition, 1)
open(p, 'w', encoding='utf-8').write(s)
print("Fake DAO: waiting/resume")

# ═══════════════════════════════════════════════════════════════════
# 4. Router: resumeAllWaiting когда получатель появляется
# ═══════════════════════════════════════════════════════════════════
p = 'android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferRouter.kt'
s = open(p, encoding='utf-8').read()

anchor = '''    /** Drives all resumable outgoing transfers plus contact key handshakes; safe to call periodically. */'''
addition = '''    /**
     * Получатель появился онлайн: возобновить все передачи, которые ждали его.
     * Вызывается из CoreServerService на peer_discovered.
     */
    suspend fun resumeWaitingForRecipient() {
        val resumed = transferDao.resumeAllWaitingRecipient(System.currentTimeMillis())
        if (resumed > 0) {
            Log.i(TAG, "Resumed $resumed file transfer(s) waiting for recipient")
            pumpOutgoing()
        }
    }

    /** Drives all resumable outgoing transfers plus contact key handshakes; safe to call periodically. */'''
assert anchor in s, "Router resume anchor"
s = s.replace(anchor, addition, 1)
open(p, 'w', encoding='utf-8').write(s)
print("Router: resumeWaitingForRecipient")

# ═══════════════════════════════════════════════════════════════════
# 5. CoreServerService: resume на peer_discovered
# ═══════════════════════════════════════════════════════════════════
p = 'android-app/app/src/main/java/com/vladimir/messenger/service/CoreServerService.kt'
s = open(p, encoding='utf-8').read()

# На peer_discovered добавить resume
anchor = '''                            try {
                                fileTransferRouter.pumpOutgoing()
                            } catch (e: Exception) {
                                Log.w(TAG, "File pump after peer discovery failed: ${e.message}")
                            }'''
addition = '''                            try {
                                fileTransferRouter.pumpOutgoing()
                                fileTransferRouter.resumeWaitingForRecipient()
                            } catch (e: Exception) {
                                Log.w(TAG, "File pump after peer discovery failed: ${e.message}")
                            }'''
assert anchor in s, "peer_discovered anchor"
s = s.replace(anchor, addition, 1)
open(p, 'w', encoding='utf-8').write(s)
print("CoreServerService: resume on peer_discovered")

# ═══════════════════════════════════════════════════════════════════
# 6. UI: понятный текст для WAITING_RECIPIENT
# ═══════════════════════════════════════════════════════════════════
p = 'android-app/app/src/main/java/com/vladimir/messenger/ui/components/FileTransferBubble.kt'
s = open(p, encoding='utf-8').read()
s = s.replace(
    '"EXPIRED" -> "Срок истёк"',
    '"EXPIRED" -> "Срок истёк"\n        "WAITING_RECIPIENT" -> "Ждём получателя онлайн"',
    1,
)
open(p, 'w', encoding='utf-8').write(s)
print("UI: WAITING_RECIPIENT label")

# ═══════════════════════════════════════════════════════════════════
# Проверка
# ═══════════════════════════════════════════════════════════════════
ok = True
for f in [
    'android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferSender.kt',
    'android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/FileTransferDao.kt',
    'android-app/app/src/test/java/com/vladimir/messenger/data/file/FakeFileTransferDao.kt',
    'android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferRouter.kt',
    'android-app/app/src/main/java/com/vladimir/messenger/service/CoreServerService.kt',
    'android-app/app/src/main/java/com/vladimir/messenger/ui/components/FileTransferBubble.kt',
]:
    src = open(f, encoding='utf-8').read()
    ss = re.sub(r'"""[\s\S]*?"""', '', src)
    ss = re.sub(r'"(\\.|[^"\\])*"', '""', ss)
    ss = re.sub(r'//.*', '', ss)
    for a, b in (('{', '}'), ('(', ')'), ('[', ']')):
        if ss.count(a) != ss.count(b):
            print(f, a, "FAIL"); ok = False
print("BALANCE OK" if ok else "BALANCE FAIL")
