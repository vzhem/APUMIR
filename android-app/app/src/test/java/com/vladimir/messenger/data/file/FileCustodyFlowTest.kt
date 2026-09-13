package com.vladimir.messenger.data.file

import com.vladimir.messenger.data.local.entity.FileTransferEntity
import java.io.File
import uniffi.p2p_core.FileTransferManifestFfi
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Хранение у третьего телефона (этап 7 роя) на трёх «телефонах» в одной JVM:
 * отправитель (FileCustodySender) → хранитель (FileTransferReceiver с
 * политикой «принимаю от контактов») → получатель (FileTransferReceiver).
 * Подтверждения ходят через общий фейковый транспорт, куски - через фейковый
 * прямой канал.
 */
class FileCustodyFlowTest {
    private val transferIdHex = "0123456789abcdef0123456789abcdef"
    private val originId = "pk_" + "ab".repeat(16)
    private val custodianId = "pk_" + "cd".repeat(16)
    private val secondCustodianId = "pk_" + "dd".repeat(16)
    private val recipientId = "pk_" + "ef".repeat(16)
    private val chatId = "chat-origin"

    private var plaintext = ByteArray(2500) { (it % 253).toByte() }
    private val chunkSize = 1024
    private val chunkCount: Long get() = ((plaintext.size + chunkSize - 1) / chunkSize).toLong()

    private lateinit var dirs: MutableList<File>

    // Отправитель
    private lateinit var originDao: FakeFileTransferDao
    private lateinit var originStore: FileTransferChunkStore
    private lateinit var originSender: FileCustodySender
    private lateinit var originReceiver: FileTransferReceiver

    // Хранитель
    private lateinit var custodianDao: FakeFileTransferDao
    private lateinit var custodianStore: FileTransferChunkStore
    private lateinit var custodianReceiver: FileTransferReceiver
    private lateinit var custodianSender: FileCustodySender
    private var custodianAcceptsContacts = true
    private var custodianHeadroom = Long.MAX_VALUE

    // Второй хранитель (этап 8)
    private lateinit var secondDao: FakeFileTransferDao
    private lateinit var secondStore: FileTransferChunkStore
    private lateinit var secondReceiver: FileTransferReceiver
    private lateinit var secondSender: FileCustodySender

    // Получатель
    private lateinit var recipientDao: FakeFileTransferDao
    private lateinit var recipientStore: FileTransferChunkStore
    private lateinit var recipientReceivedStore: ReceivedFileStore
    private lateinit var recipientReceiver: FileTransferReceiver
    private lateinit var recipientNotifier: RecordingNotifier
    private var recipientChatKnown = true

    /** Кто сейчас в сети (по мнению всех трёх). */
    private val online = HashSet<String>()
    private var now = 10_000_000L

    /** Надёжный транспорт: журнал (от кого, кому, текст). */
    private val reliable = mutableListOf<Triple<String, String, String>>()
    /** Прямой канал: журнал (от кого, кому, текст). */
    private val direct = mutableListOf<Triple<String, String, String>>()
    private var directDown = false
    /**
     * «Сеть»: отправленное не разбирается адресатом синхронно (в жизни оно
     * приходит с другого телефона, в другом потоке), а лежит в очереди, пока
     * тест не вызовет [drain]. Иначе подтверждение возвращалось бы внутрь
     * ещё не отпущенного замка отправителя.
     */
    private val inFlight = ArrayDeque<Triple<String, String, String>>()

    /** Фейковая криптография читает размер и хеш файла лениво: тест может подменить [plaintext] после setUp. */
    private fun crypto(sender: String, recipient: String): FileCryptoGateway = object : FileCryptoGateway {
        private fun inner() = FakeFileCryptoGateway(
            senderNodeId = sender,
            recipientNodeId = recipient,
            transferIdHex = transferIdHex,
            fileSha256Hex = sha256(plaintext),
            fileSizeBytes = plaintext.size.toLong(),
            chunkSizeBytes = chunkSize,
        )
        override fun parseManifest(manifestBytes: ByteArray): FileTransferManifestFfi = inner().parseManifest(manifestBytes)
        override fun verifyBinding(binding: ByteArray): Boolean = inner().verifyBinding(binding)
        override fun bindingNodeId(binding: ByteArray): String = inner().bindingNodeId(binding)
        override fun openKeyEnvelope(envelope: ByteArray, myBinding: ByteArray, secret: ByteArray, manifest: ByteArray): ByteArray =
            inner().openKeyEnvelope(envelope, myBinding, secret, manifest)
        override fun decryptChunk(manifestBytes: ByteArray, fileKey: ByteArray, chunkIndex: Long, ciphertext: ByteArray): ByteArray =
            inner().decryptChunk(manifestBytes, fileKey, chunkIndex, ciphertext)
    }

    private fun newDir(prefix: String): File = TestDirs.newDir(prefix).also { dirs += it }

    @Before
    fun setUp() {
        dirs = mutableListOf()
        originDao = FakeFileTransferDao()
        originStore = FileTransferChunkStore(newDir("apu-custody-origin-"))
        custodianDao = FakeFileTransferDao()
        custodianStore = FileTransferChunkStore(newDir("apu-custody-holder-"))
        secondDao = FakeFileTransferDao()
        secondStore = FileTransferChunkStore(newDir("apu-custody-holder2-"))
        recipientDao = FakeFileTransferDao()
        recipientStore = FileTransferChunkStore(newDir("apu-custody-recipient-"))
        recipientReceivedStore = ReceivedFileStore(newDir("apu-custody-received-"))
        recipientNotifier = RecordingNotifier()

        fun reliableFrom(me: String) = PacketTransport { _, _, to, text ->
            reliable += Triple(me, to, text)
            inFlight.addLast(Triple(me, to, text))
            true
        }
        fun directFrom(me: String): (String, String) -> Boolean = { to, text ->
            if (directDown || to !in online) {
                false
            } else {
                direct += Triple(me, to, text)
                inFlight.addLast(Triple(me, to, text))
                true
            }
        }

        // ── отправитель ──
        originSender = FileCustodySender(
            transferDao = originDao,
            chunkStore = originStore,
            directTransport = directFrom(originId),
            ownBindingProvider = { ByteArray(96) { 3 } },
            myNodeId = { originId },
            candidates = { recipient, _ -> online.filter { it != recipient && it != originId }.sorted() },
            isOnline = { it in online },
            nowMs = { now },
        )
        originReceiver = FileTransferReceiver(
            transferDao = originDao,
            chunkStore = originStore,
            receivedStore = ReceivedFileStore(newDir("apu-custody-origin-rx-")),
            pinner = RecordingPinner(),
            crypto = crypto(originId, recipientId),
            keyVault = FakeTransferKeyVault(),
            identity = FakeLocalExchangeIdentity(originId),
            transport = reliableFrom(originId),
            ackSink = { _, _ -> },
            notifier = RecordingNotifier(),
            nowMs = { now },
            custody = FileTransferReceiver.CustodyPolicy(
                onCustodianAck = { id, from, contiguous, status -> originSender.onCustodianAck(id, from, contiguous, status) },
            ),
        )

        // ── хранитель ──
        custodianSender = FileCustodySender(
            transferDao = custodianDao,
            chunkStore = custodianStore,
            directTransport = directFrom(custodianId),
            ownBindingProvider = { ByteArray(96) { 4 } },
            myNodeId = { custodianId },
            candidates = { _, _ -> emptyList() },
            isOnline = { it in online },
            nowMs = { now },
        )
        custodianReceiver = FileTransferReceiver(
            transferDao = custodianDao,
            chunkStore = custodianStore,
            receivedStore = ReceivedFileStore(newDir("apu-custody-holder-rx-")),
            pinner = RecordingPinner(),
            crypto = crypto(originId, recipientId),
            keyVault = FakeTransferKeyVault(),
            identity = FakeLocalExchangeIdentity(custodianId),
            transport = reliableFrom(custodianId),
            ackSink = { _, _ -> },
            notifier = RecordingNotifier(),
            nowMs = { now },
            custody = FileTransferReceiver.CustodyPolicy(
                acceptsFrom = { custodianAcceptsContacts },
                headroomBytes = { custodianHeadroom },
                onRecipientAck = { id, from, contiguous, status -> custodianSender.onRecipientAck(id, from, contiguous, status) },
                onRecipientWant = { id, from, contiguous, seq, ranges -> custodianSender.onRecipientWant(id, from, contiguous, seq, ranges) },
                onOriginRelease = { id, from -> custodianSender.onOriginRelease(id, from) },
            ),
        )

        // ── второй хранитель (этап 8) ──
        secondSender = FileCustodySender(
            transferDao = secondDao,
            chunkStore = secondStore,
            directTransport = directFrom(secondCustodianId),
            ownBindingProvider = { ByteArray(96) { 6 } },
            myNodeId = { secondCustodianId },
            candidates = { _, _ -> emptyList() },
            isOnline = { it in online },
            nowMs = { now },
        )
        secondReceiver = FileTransferReceiver(
            transferDao = secondDao,
            chunkStore = secondStore,
            receivedStore = ReceivedFileStore(newDir("apu-custody-holder2-rx-")),
            pinner = RecordingPinner(),
            crypto = crypto(originId, recipientId),
            keyVault = FakeTransferKeyVault(),
            identity = FakeLocalExchangeIdentity(secondCustodianId),
            transport = reliableFrom(secondCustodianId),
            ackSink = { _, _ -> },
            notifier = RecordingNotifier(),
            nowMs = { now },
            custody = FileTransferReceiver.CustodyPolicy(
                acceptsFrom = { true },
                headroomBytes = { Long.MAX_VALUE },
                onRecipientAck = { id, from, contiguous, status -> secondSender.onRecipientAck(id, from, contiguous, status) },
                onRecipientWant = { id, from, contiguous, seq, ranges -> secondSender.onRecipientWant(id, from, contiguous, seq, ranges) },
                onOriginRelease = { id, from -> secondSender.onOriginRelease(id, from) },
            ),
        )

        // ── получатель ──
        recipientReceiver = FileTransferReceiver(
            transferDao = recipientDao,
            chunkStore = recipientStore,
            receivedStore = recipientReceivedStore,
            pinner = RecordingPinner(),
            crypto = crypto(originId, recipientId),
            keyVault = FakeTransferKeyVault(),
            identity = FakeLocalExchangeIdentity(recipientId),
            transport = reliableFrom(recipientId),
            ackSink = { _, _ -> },
            notifier = recipientNotifier,
            nowMs = { now },
            custody = FileTransferReceiver.CustodyPolicy(
                chatIdFor = { origin -> if (recipientChatKnown && origin == originId) "chat-with-origin" else null },
            ),
        )
    }

    @After
    fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    /** Доставить всё, что в пути, включая то, что породит сама доставка. */
    private suspend fun drain() {
        var guard = 0
        while (inFlight.isNotEmpty()) {
            val (from, to, text) = inFlight.removeFirst()
            val receiver = when (to) {
                originId -> originReceiver
                custodianId -> custodianReceiver
                secondCustodianId -> secondReceiver
                recipientId -> recipientReceiver
                else -> error("unknown node $to")
            }
            assertTrue(receiver.onIncomingText(from, FileTransferChatRouting.CUSTODY_SCOPE, "m-${guard}", text))
            check(++guard < 10_000) { "network never settles" }
        }
    }

    private suspend fun offerOrigin(): FileCustodySender.Summary {
        val summary = originSender.pumpOrigin()
        drain()
        return summary
    }

    private suspend fun forward(): FileCustodySender.Summary {
        val summary = custodianSender.pumpForwarding()
        drain()
        return summary
    }

    private suspend fun stageOutgoing(state: String = "WAITING_RECIPIENT", createdAgoMs: Long = FileCustodySender.CUSTODY_AFTER_MS + 1) {
        originDao.insertNewTransfer(
            FileTransferEntity(
                transferId = transferIdHex,
                messageId = "m-$transferIdHex",
                chatId = chatId,
                peerNodeId = recipientId,
                direction = "OUTGOING",
                displayName = "photo.png",
                mediaType = "image/png",
                totalBytes = plaintext.size.toLong(),
                chunkSize = chunkSize,
                chunkCount = chunkCount,
                fileSha256 = sha256(plaintext),
                state = state,
                completedChunks = chunkCount,
                transferredBytes = plaintext.size.toLong(),
                createdAtMs = now - createdAgoMs,
                expiresAtMs = now + 7L * 24 * 3_600_000,
                updatedAtMs = now - 1_000,
            )
        )
        originStore.storeManifest(transferIdHex, ByteArray(96) { 1 })
        originStore.storeKeyEnvelope(transferIdHex, ByteArray(220) { 2 })
        for (index in 0 until chunkCount.toInt()) {
            val end = minOf(plaintext.size, (index + 1) * chunkSize)
            originStore.storeEncryptedChunk(
                transferIdHex,
                index.toLong(),
                FakeFileCryptoGateway.fakeEncrypt(plaintext.copyOfRange(index * chunkSize, end)),
            )
        }
    }

    @Test
    fun fileTravelsOriginToCustodianToRecipientAndEveryoneCleansUp() = runTest {
        stageOutgoing()
        online += custodianId // получатель не в сети

        val summary = offerOrigin()
        assertEquals(1, summary.originPumped)
        // Сначала только предложение; после подтверждения хранителя - окно:
        // предложение ещё раз плюс три куска.
        assertEquals(1, summary.packets)
        assertEquals(5, direct.count { it.first == originId && it.second == custodianId })

        // Хранитель всё принял и подтвердил; отправитель отметил «у хранителя».
        val held = custodianDao.getTransfer(transferIdHex)
        assertNotNull(held)
        assertEquals("CUSTODY", held!!.direction)
        assertEquals("HOLDING", held.state)
        assertEquals(3L, held.completedChunks)
        assertEquals(originId, held.originNodeId)
        assertEquals(recipientId, held.peerNodeId)
        assertNotNull(custodianStore.readOriginBinding(transferIdHex))
        val origin = originDao.getTransfer(transferIdHex)!!
        assertEquals("CUSTODIED", origin.state)
        assertEquals(custodianId, origin.custodianNodeId)
        assertNull(originSender.currentCustodian(transferIdHex))
        // Ключ файла хранителю недоступен: у него нет своего ключа в хранилище.
        assertTrue(custodianDao.getChunks(transferIdHex).all { it.state == "HELD" })

        // Пока получателя нет - хранитель молчит.
        assertEquals(0, forward().forwarded)
        assertTrue(direct.none { it.second == recipientId })

        // Получатель появился.
        online += recipientId
        val forwarded = forward()
        assertEquals(1, forwarded.forwarded)
        assertEquals(5, direct.count { it.first == custodianId && it.second == recipientId })

        // Получатель собрал файл, проверил, положил в чат с ОТПРАВИТЕЛЕМ.
        val received = recipientDao.getTransfer(transferIdHex)!!
        assertEquals("COMPLETE", received.state)
        assertEquals("INCOMING", received.direction)
        assertEquals(originId, received.peerNodeId)
        assertEquals("chat-with-origin", received.chatId)
        assertEquals(custodianId, received.custodianNodeId)
        val file = recipientReceivedStore.receivedFile(transferIdHex, "photo.png")
        assertNotNull(file)
        assertTrue(file!!.readBytes().contentEquals(plaintext))
        assertEquals(1, recipientNotifier.events.size)
        assertTrue(recipientNotifier.events.single().startsWith("chat-with-origin|$originId|"))

        // Хранитель освободил место после итогового подтверждения получателя.
        assertNull(custodianDao.getTransfer(transferIdHex))
        assertTrue(custodianStore.storedChunkIndices(transferIdHex).isEmpty())

        // Отправителю ушло только итоговое обычное подтверждение получателя
        // (через надёжный транспорт; повтор с тем же id сеть отсеет), а
        // промежуточные - хранителю.
        val toOrigin = reliable.filter { it.first == recipientId && it.second == originId }
        assertTrue(toOrigin.isNotEmpty())
        for ((_, _, text) in toOrigin) {
            val ack = FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(text))
            assertEquals(FileTransferPacketCodec.Type.ACK, ack.type)
            assertEquals(3L, ack.itemIndex)
        }
        val toCustodian = reliable.filter { it.first == recipientId && it.second == custodianId }
            .map { FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(it.third)) }
        assertTrue(toCustodian.all { it.type == FileTransferPacketCodec.Type.CUSTODY_ACK })
        assertTrue(toCustodian.any { it.itemIndex == 1L } && toCustodian.any { it.itemIndex == 3L })
    }

    @Test
    fun custodianWithoutSpaceAnswersFullAndOriginMovesOn() = runTest {
        stageOutgoing()
        online += custodianId
        custodianHeadroom = 100L

        offerOrigin()

        // Хранитель отказал по месту: у него ничего не осталось, отправитель ждёт другого.
        assertNull(custodianDao.getTransfer(transferIdHex))
        assertEquals("WAITING_RECIPIENT", originDao.getTransfer(transferIdHex)!!.state)
        assertNull(originSender.currentCustodian(transferIdHex))
        // Кусков в пустоту не лили: ушло только предложение.
        assertEquals(1, direct.count { it.first == originId })
        // Тот же кандидат больше не предлагается (остывание), других нет.
        assertEquals(0, offerOrigin().originPumped)
    }

    @Test
    fun strangerIsRefusedAsCustodianAndOriginKeepsWaiting() = runTest {
        stageOutgoing()
        online += custodianId
        custodianAcceptsContacts = false

        offerOrigin()

        assertNull(custodianDao.getTransfer(transferIdHex))
        assertEquals("WAITING_RECIPIENT", originDao.getTransfer(transferIdHex)!!.state)
        assertNull(originSender.currentCustodian(transferIdHex))
    }

    @Test
    fun unreachableCandidateGetsShortCooldownThenSucceeds() = runTest {
        stageOutgoing()
        online += custodianId
        directDown = true
        assertEquals(0, offerOrigin().originPumped)
        // Недостижимый напрямую - короткое остывание, потом пробуем снова.
        assertNull(originSender.currentCustodian(transferIdHex))
        now += 1_000
        assertEquals(0, offerOrigin().originPumped)
        now += FileCustodySender.UNREACHABLE_COOLDOWN_MS
        directDown = false
        assertEquals(1, offerOrigin().originPumped)
        assertEquals("CUSTODIED", originDao.getTransfer(transferIdHex)!!.state)
    }

    @Test
    fun silentOldVersionCandidateGetsOnlyOffersThenIsRetired() = runTest {
        stageOutgoing()
        online += custodianId
        // Хранитель «старой версии»: пакеты доходят, но он их не понимает и
        // молчит. Изображаем это, выбрасывая всё, что летит к нему.
        val summary = originSender.pumpOrigin()
        assertEquals(1, summary.packets)
        inFlight.clear()
        assertEquals(custodianId, originSender.currentCustodian(transferIdHex))

        // Повторы по таймеру - снова только предложение, кусков нет.
        now += FileCustodySender.REPUMP_INTERVAL_MS + 1
        originSender.pumpOrigin()
        inFlight.clear()
        assertEquals(2, direct.count { it.first == originId })

        // Молчит дольше срока рукопожатия - кандидат снят, других нет.
        now += FileCustodySender.ATTEMPT_TIMEOUT_MS
        assertEquals(0, originSender.pumpOrigin().originPumped)
        assertNull(originSender.currentCustodian(transferIdHex))
        assertEquals("WAITING_RECIPIENT", originDao.getTransfer(transferIdHex)!!.state)
    }

    @Test
    fun recipientWithoutChatForOriginRefusesForwardedFile() = runTest {
        stageOutgoing()
        online += custodianId
        offerOrigin()
        assertEquals("CUSTODIED", originDao.getTransfer(transferIdHex)!!.state)

        recipientChatKnown = false
        online += recipientId
        forward()

        // Получатель отказал - хранитель освободил место, получатель ничего не завёл,
        // куски получателю не уходили (только предложение).
        assertNull(recipientDao.getTransfer(transferIdHex))
        assertNull(custodianDao.getTransfer(transferIdHex))
        assertEquals(1, direct.count { it.first == custodianId })
    }

    @Test
    fun originDoesNotSeekCustodyBeforeGraceOrWhenAlreadyCustodied() = runTest {
        stageOutgoing(createdAgoMs = 10_000L)
        online += custodianId
        assertEquals(0, offerOrigin().originPumped)

        now += FileCustodySender.CUSTODY_AFTER_MS
        assertEquals(1, offerOrigin().originPumped)
        assertEquals("CUSTODIED", originDao.getTransfer(transferIdHex)!!.state)
        // Повторный насос ничего не предлагает: файл уже у единственного
        // кандидата, а второго кандидата (этап 8) в сети нет.
        assertEquals(0, offerOrigin().originPumped)
    }

    @Test
    fun staleCustodyIsSweptAndRepeatedOfferReportsProgress() = runTest {
        stageOutgoing()
        online += custodianId
        offerOrigin()
        assertNotNull(custodianDao.getTransfer(transferIdHex))

        // Повторное предложение тому же хранителю - напоминание о прогрессе, не дубликат строки.
        val offer = FileCustodyPdu.encode(originId, recipientId, ByteArray(96) { 1 }, ByteArray(220) { 2 }, ByteArray(96) { 3 })
        for (fragment in FileTransferPacketCodec.fragment(FileTransferPacketCodec.Type.CUSTODY_OFFER, hexToBytes(transferIdHex), 0L, offer)) {
            inFlight.addLast(Triple(originId, custodianId, FileTransferWire.encodeEncodedPacket(fragment)))
        }
        drain()
        assertEquals(1, custodianDao.getActiveCustody(now).size)
        assertEquals(3L, custodianDao.getTransfer(transferIdHex)!!.completedChunks)

        // Срок вышел - уборка удаляет чужой файл.
        now += FileCustodySender.CUSTODY_TTL_MS + 1
        assertEquals(1, custodianSender.sweep(force = true))
        assertNull(custodianDao.getTransfer(transferIdHex))
        assertTrue(custodianStore.storedChunkIndices(transferIdHex).isEmpty())
    }

    // ── Этап 8: несколько хранителей ─────────────────────────────────────────

    /** Кто получил куски от кого: (хранитель → номера кусков) по журналу прямого канала. */
    private fun chunksToRecipientBy(holder: String): List<Long> =
        direct.filter { it.first == holder && it.second == recipientId }
            .map { FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(it.third)) }
            .filter { it.type == FileTransferPacketCodec.Type.CUSTODY_CHUNK }
            .map { it.itemIndex }
            .distinct()

    @Test
    fun originSpreadsCopiesToTwoHoldersOneAtATime() = runTest {
        stageOutgoing()
        online += custodianId
        online += secondCustodianId

        // Первый насос: одному кандидату (первому по порядку), не обоим сразу.
        assertEquals(1, offerOrigin().originPumped)
        val afterFirst = originDao.getTransfer(transferIdHex)!!
        assertEquals("CUSTODIED", afterFirst.state)
        assertEquals(listOf(custodianId), FileCustodyPdu.holders(afterFirst.custodianNodeId))
        assertTrue(direct.none { it.first == originId && it.second == secondCustodianId })

        // Второй насос: файл уже у одного, отправитель в сети - раздаёт вторую копию.
        assertEquals(1, offerOrigin().originPumped)
        val afterSecond = originDao.getTransfer(transferIdHex)!!
        assertEquals("CUSTODIED", afterSecond.state)
        assertEquals(listOf(custodianId, secondCustodianId), FileCustodyPdu.holders(afterSecond.custodianNodeId))
        assertEquals(chunkCount, secondDao.getTransfer(transferIdHex)!!.completedChunks)
        assertEquals(chunkCount, custodianDao.getTransfer(transferIdHex)!!.completedChunks)

        // Достаточно: третьего не ищем, даже если кандидаты есть.
        assertEquals(0, offerOrigin().originPumped)
    }

    @Test
    fun recipientPullsDifferentChunksFromTwoHoldersAndBothRelease() = runTest {
        // Файл на 200 кусков: окно прямого канала - 120 сообщений (кусок
        // 1 КиБ = один фрагмент). Первое окно первого хранителя (0..119)
        // получатель за ним и оставляет, а остальное делит полосами между
        // обоими - так куски идут с двух телефонов разом.
        plaintext = ByteArray(200 * chunkSize - 100) { (it % 251).toByte() }
        stageOutgoing()
        online += custodianId
        online += secondCustodianId
        offerOrigin()
        offerOrigin()
        assertEquals(2, FileCustodyPdu.holders(originDao.getTransfer(transferIdHex)!!.custodianNodeId).size)

        // Получатель появился. Оба хранителя шлют предложения; куски идут
        // после подтверждений и инвентаря. Второе предложение приходит,
        // когда первое окно уже в пути - как и бывает на телефонах.
        online += recipientId
        custodianSender.pumpForwarding()
        secondSender.pumpForwarding()
        drain()

        val received = recipientDao.getTransfer(transferIdHex)!!
        assertEquals("COMPLETE", received.state)
        assertEquals(listOf(custodianId, secondCustodianId), FileCustodyPdu.holders(received.custodianNodeId))
        val file = recipientReceivedStore.receivedFile(transferIdHex, "photo.png")!!
        assertTrue(file.readBytes().contentEquals(plaintext))

        // Первый хранитель отдал окно от префикса (0..119) и свои полосы
        // хвоста, второй - только свои полосы: ни один кусок не пришёл дважды,
        // вместе они покрыли файл.
        val fromFirst = chunksToRecipientBy(custodianId)
        val fromSecond = chunksToRecipientBy(secondCustodianId)
        assertTrue("first holder sent nothing", fromFirst.isNotEmpty())
        assertTrue("second holder sent nothing", fromSecond.isNotEmpty())
        assertTrue(fromSecond.none { it < 120L })
        assertTrue(fromFirst.intersect(fromSecond.toSet()).isEmpty())
        assertEquals((0L until chunkCount).toSet(), (fromFirst + fromSecond).toSet())
        // Полосы по 8 по номеру куска: 120..127 - второму, 128..135 - первому.
        assertTrue(fromSecond.containsAll((120L..127L).toList()))
        assertTrue(fromFirst.containsAll((128L..135L).toList()))

        // Инвентарь действительно ходил: обоим, с одним номером.
        val wants = reliable.filter { it.first == recipientId }
            .map { it.second to FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(it.third)) }
            .filter { it.second.type == FileTransferPacketCodec.Type.CUSTODY_WANT }
        assertTrue(wants.any { it.first == custodianId } && wants.any { it.first == secondCustodianId })

        // Оба хранителя освободили место (итоговые подтверждения ушли всем).
        assertNull(custodianDao.getTransfer(transferIdHex))
        assertNull(secondDao.getTransfer(transferIdHex))
        assertTrue(custodianStore.storedChunkIndices(transferIdHex).isEmpty())
        assertTrue(secondStore.storedChunkIndices(transferIdHex).isEmpty())
        // Отправителю - итоговое подтверждение, передача доставлена.
        val toOrigin = reliable.filter { it.first == recipientId && it.second == originId }
            .map { FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(it.third)) }
        assertTrue(toOrigin.any { it.type == FileTransferPacketCodec.Type.ACK && it.itemIndex == chunkCount })
    }

    @Test
    fun holderReceivesReleaseFromOriginWhenRecipientAcksOnlyTheOther() = runTest {
        stageOutgoing()
        online += custodianId
        online += secondCustodianId
        offerOrigin()
        offerOrigin()

        // Второй хранитель не успел представиться получателю (его насос не
        // сработал - как у телефона, ушедшего в фон): получатель забрал файл
        // у первого и подтвердил только ему. Копия у второго осталась бы до
        // срока - если бы не отправитель.
        online += recipientId
        forward()
        assertEquals("COMPLETE", recipientDao.getTransfer(transferIdHex)!!.state)
        assertNull(custodianDao.getTransfer(transferIdHex))
        // Второй хранитель тоже отпустил копию: отправитель, получив итоговый
        // ACK получателя, разослал ACK_RELEASE всем хранителям из списка.
        assertNull(secondDao.getTransfer(transferIdHex))
        assertTrue(secondStore.storedChunkIndices(transferIdHex).isEmpty())
        val releases = reliable.filter { it.first == originId }
            .map { it.second to FileTransferPacketCodec.decode(FileTransferWire.decodeToEncodedPacket(it.third)) }
            .filter { it.second.type == FileTransferPacketCodec.Type.CUSTODY_ACK && FileCustodyPdu.ackStatus(it.second.payload) == FileCustodyPdu.ACK_RELEASE }
        assertTrue(releases.any { it.first == secondCustodianId })
    }

    @Test
    fun strangerCannotReleaseHeldCopy() = runTest {
        stageOutgoing()
        online += custodianId
        offerOrigin()
        assertNotNull(custodianDao.getTransfer(transferIdHex))
        val packet = FileTransferPacketCodec.encode(
            FileTransferPacketCodec.Packet(
                FileTransferPacketCodec.Type.CUSTODY_ACK, hexToBytes(transferIdHex), chunkCount, 0, 1, byteArrayOf(FileCustodyPdu.ACK_RELEASE),
            ),
        )
        inFlight.addLast(Triple(secondCustodianId, custodianId, FileTransferWire.encodeEncodedPacket(packet)))
        drain()
        assertNotNull(custodianDao.getTransfer(transferIdHex))
        inFlight.addLast(Triple(originId, custodianId, FileTransferWire.encodeEncodedPacket(packet)))
        drain()
        assertNull(custodianDao.getTransfer(transferIdHex))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
