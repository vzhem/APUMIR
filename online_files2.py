import re

# ═══ Sender: файлы ТОЛЬКО напрямую, офлайн → WAITING_RECIPIENT ═══
p = 'android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferSender.kt'
s = open(p, encoding='utf-8').read()

# 1. Exception class
if 'RecipientOfflineException' not in s:
    anchor = '    data class PumpSummary('
    addition = '''    /** Получатель офлайн — файл ждёт когда он появится (только прямая доставка). */
    class RecipientOfflineException(val transferId: String) : Exception("Recipient offline")

    data class PumpSummary('''
    assert anchor in s
    s = s.replace(anchor, addition, 1)
    print("exception class added")

# 2. sendItem: только прямой QUIC
if 'RecipientOfflineException' not in s.split('sendItem')[1][:500]:
    # Заменяем блок с directTransport/fallback
    old_block = '''val directOk = directTransport?.invoke(transfer.peerNodeId, text) ?: false
            if (!directOk) {
                transport.send(messageIdFor(fragmentIndex), transfer.chatId, transfer.peerNodeId, text)
            }
            sleeper(PACKET_GAP_MS)'''
    new_block = '''val directOk = directTransport?.invoke(transfer.peerNodeId, text) ?: false
            if (!directOk) {
                Log.i(TAG, "Recipient not directly reachable — pausing transfer")
                throw RecipientOfflineException(transfer.transferId)
            }
            sleeper(PACKET_GAP_MS)'''
    if old_block in s:
        s = s.replace(old_block, new_block, 1)
        print("sendItem: direct only")
    else:
        # fallback: ищем по частям
        idx = s.find('directTransport?.invoke')
        if idx > 0:
            # Заменяем от directTransport до sleeper
            end_idx = s.find('sleeper(PACKET_GAP_MS)', idx)
            if end_idx > 0:
                old_segment = s[idx:end_idx]
                new_segment = '''directTransport?.invoke(transfer.peerNodeId, text) ?: false
            if (!directOk) {
                Log.i(TAG, "Recipient not directly reachable — pausing transfer")
                throw RecipientOfflineException(transfer.transferId)
            }
            '''
                s = s[:idx] + new_segment + s[end_idx:]
                print("sendItem: direct only (fallback method)")
        else:
            print("WARNING: directTransport invoke not found!")

# 3. pump failure: RecipientOfflineException → WAITING_RECIPIENT
if 'WAITING_RECIPIENT' not in s:
    # Ищем блок if (result.isFailure)
    old_fail = '''if (result.isFailure) {
                failures++
                Log.w(TAG, "File transfer pump failed for ${transfer.transferId}: ${result.exceptionOrNull()?.message}")
            }'''
    new_fail = '''if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is RecipientOfflineException) {
                    advance(transfer, newState = "WAITING_RECIPIENT")
                    Log.i(TAG, "Transfer ${transfer.transferId} waiting for recipient online")
                } else {
                    failures++
                    Log.w(TAG, "File transfer pump failed for ${transfer.transferId}: ${error?.message}")
                }
            }'''
    if old_fail in s:
        s = s.replace(old_fail, new_fail, 1)
        print("pump failure: WAITING_RECIPIENT handling")
    else:
        print("WARNING: failure block not found, trying broader match")
        # Broader match
        idx = s.find('if (result.isFailure)')
        if idx > 0:
            end = s.find('}', s.find('}', idx)+1) + 1  # find closing brace of the if block
            old_seg = s[idx:end]
            new_seg = new_fail
            s = s[:idx] + new_seg + s[end:]
            print("pump failure: WAITING_RECIPIENT (broad match)")

open(p, 'w', encoding='utf-8').write(s)

# ═══ DAO: getWaitingRecipient + resume ═══
p = 'android-app/app/src/main/java/com/vladimir/messenger/data/local/dao/FileTransferDao.kt'
s = open(p, encoding='utf-8').read()
if 'getWaitingRecipient' not in s:
    anchor = '@Query("SELECT * FROM file_transfers WHERE state = \'CANCELLED\'")'
    addition = '''@Query("SELECT * FROM file_transfers WHERE direction = 'OUTGOING' AND state = 'WAITING_RECIPIENT'")
    suspend fun getWaitingRecipient(): List<FileTransferEntity>

    @Query(
        """
        UPDATE file_transfers
        SET state = 'TRANSFERRING', updatedAtMs = :nowMs
        WHERE direction = 'OUTGOING' AND state = 'WAITING_RECIPIENT'
        """
    )
    suspend fun resumeAllWaitingRecipient(nowMs: Long): Int

    ''' + anchor
    assert anchor in s
    s = s.replace(anchor, addition, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print("DAO: waiting/resume queries")

# ═══ Fake DAO ═══
p = 'android-app/app/src/test/java/com/vladimir/messenger/data/file/FakeFileTransferDao.kt'
s = open(p, encoding='utf-8').read()
if 'getWaitingRecipient' not in s:
    anchor = '    override suspend fun getCancelled(): List<FileTransferEntity> ='
    addition = '''    override suspend fun getWaitingRecipient(): List<FileTransferEntity> =
        transfers.values.filter { it.direction == "OUTGOING" && it.state == "WAITING_RECIPIENT" }

    override suspend fun resumeAllWaitingRecipient(nowMs: Long): Int {
        var n = 0
        transfers.replaceAll { _, e ->
            if (e.direction == "OUTGOING" && e.state == "WAITING_RECIPIENT") {
                n++; e.copy(state = "TRANSFERRING", updatedAtMs = nowMs)
            } else e
        }
        return n
    }

''' + anchor
    assert anchor in s
    s = s.replace(anchor, addition, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print("Fake DAO: waiting/resume")

# ═══ Router: resume при появлении получателя ═══
p = 'android-app/app/src/main/java/com/vladimir/messenger/data/file/FileTransferRouter.kt'
s = open(p, encoding='utf-8').read()
if 'resumeWaitingForRecipient' not in s:
    anchor = '    /** Drives all resumable outgoing transfers plus contact key handshakes; safe to call periodically. */'
    addition = '''    /**
     * Получатель появился: возобновить все передачи, которые его ждали.
     */
    suspend fun resumeWaitingForRecipient() {
        val resumed = transferDao.resumeAllWaitingRecipient(System.currentTimeMillis())
        if (resumed > 0) {
            Log.i(TAG, "Resumed $resumed file transfer(s) waiting for recipient")
            pumpOutgoing()
        }
    }

''' + anchor
    assert anchor in s
    s = s.replace(anchor, addition, 1)
    open(p, 'w', encoding='utf-8').write(s)
    print("Router: resumeWaitingForRecipient")

# ═══ CoreServerService: resume на peer_discovered ═══
p = 'android-app/app/src/main/java/com/vladimir/messenger/service/CoreServerService.kt'
s = open(p, encoding='utf-8').read()
if 'resumeWaitingForRecipient' not in s:
    anchor = 'fileTransferRouter.pumpOutgoing()'
    addition = '''fileTransferRouter.pumpOutgoing()
                                fileTransferRouter.resumeWaitingForRecipient()'''
    # Только в peer_discovered блоке (не в pump loop)
    idx = s.find(anchor, s.find('peer_discovered'))
    if idx > 0:
        s = s[:idx] + addition + s[idx+len(anchor):]
        open(p, 'w', encoding='utf-8').write(s)
        print("CoreServerService: resume on peer_discovered")
    else:
        print("WARNING: peer_discovered pump anchor not found")

# ═══ UI: WAITING_RECIPIENT label ═══
p = 'android-app/app/src/main/java/com/vladimir/messenger/ui/components/FileTransferBubble.kt'
s = open(p, encoding='utf-8').read()
if 'WAITING_RECIPIENT' not in s:
    s = s.replace(
        '"EXPIRED" -> "Срок истёк"',
        '"EXPIRED" -> "Срок истёк"\n        "WAITING_RECIPIENT" -> "Ждём получателя онлайн"',
        1,
    )
    open(p, 'w', encoding='utf-8').write(s)
    print("UI: WAITING_RECIPIENT label")

# ═══ Проверка ═══
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

# Подтверждение что ключевые изменения на месте
checks = [
    ('FileTransferSender.kt', 'RecipientOfflineException'),
    ('FileTransferSender.kt', 'WAITING_RECIPIENT'),
    ('FileTransferDao.kt', 'getWaitingRecipient'),
    ('FileTransferRouter.kt', 'resumeWaitingForRecipient'),
    ('CoreServerService.kt', 'resumeWaitingForRecipient'),
    ('FileTransferBubble.kt', 'WAITING_RECIPIENT'),
]
base = 'android-app/app/src/main/java/com/vladimir/messenger/'
for f, marker in checks:
    src = open(base + f, encoding='utf-8').read()
    status = "OK" if marker in src else "MISSING"
    print(f"  {f}: {marker} -> {status}")
