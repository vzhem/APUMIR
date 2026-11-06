package com.vladimir.messenger.data.avatavideo

import android.content.Context
import android.util.Log
import com.vladimir.messenger.data.local.entity.FileTransferEntity
import com.vladimir.messenger.data.story.StoryStore
import com.vladimir.messenger.data.story.StoryVideoStorage
import com.vladimir.messenger.data.story.StoryVideoWire
import java.io.File

/**
 * Куда складывать завершённые передачи со служебными именами файлов.
 *
 * Файлы видеоисторий и видеоаватаров приходят обычными файловыми передачами с
 * зарезервированными именами (`apu-story-video-…`, `apu-avatar-video-…`): они не
 * должны становиться вложениями в чате. Раньше ветка историй жила прямо в
 * роутере передач; перенос сюда держит god-файлы на потолке и даёт видеоаватару
 * тот же путь приёма: найти строку, взять принятый файл, проверить и поставить.
 */
object SpecialMediaInbox {
    private const val TAG = "SpecialMediaInbox"

    /** Завершённая служебная передача: история - к [StoryStore], видеоаватар - к [PeerVideoAvatarStore]. */
    suspend fun accept(
        context: Context,
        row: FileTransferEntity,
        receivedFile: (transferId: String, displayName: String) -> File?,
    ) {
        if (StoryVideoWire.reserved(row.displayName)) {
            acceptStoryVideo(context, row, receivedFile)
        } else if (AvatarVideoWire.reserved(row.displayName)) {
            acceptPeerAvatarVideo(context, row, receivedFile)
        }
    }

    private suspend fun acceptStoryVideo(
        context: Context,
        row: FileTransferEntity,
        receivedFile: (String, String) -> File?,
    ) {
        val story = StoryStore.active().firstOrNull {
            StoryVideoWire.matches(it, row.peerNodeId, row.displayName,
                row.fileSha256, row.totalBytes, System.currentTimeMillis())
        } ?: return
        if (StoryVideoStorage.file(context, story) != null ||
            StoryVideoStorage.failure(story) != null) return
        val file = receivedFile(row.transferId, row.displayName) ?: return
        try {
            StoryVideoStorage.install(context,
                story.authorId, story.id, file, checkNotNull(story.video))
            // A revoke/expiry may have raced the hashing/copy. Never resurrect it.
            if (StoryStore.active().none { it.id == story.id && it.authorId == story.authorId }) {
                StoryVideoStorage.remove(context, story.authorId, story.id)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            StoryVideoStorage.noteFailure(story)
            Log.w(TAG, "Story video validation failed", failure)
        }
    }

    /**
     * Ролик видеоаватара собеседника. Обязательна запись из объявления (кому и
     * какой sha ждём): без неё файл отклоняется — прислать ролик может только
     * владелец и только тот, что объявил.
     */
    private suspend fun acceptPeerAvatarVideo(
        context: Context,
        row: FileTransferEntity,
        receivedFile: (String, String) -> File?,
    ) {
        val expected = PeerVideoAvatarStore.metaFor(context, row.peerNodeId)
        if (expected == null) {
            PeerVideoAvatarStore.bump(context, "file-reject")
            return
        }
        if (expected.sha256 != row.fileSha256 || expected.bytes != row.totalBytes) {
            Log.w(TAG, "peer video avatar transfer does not match the announce, dropped")
            PeerVideoAvatarStore.bump(context, "file-reject")
            return
        }
        if (PeerVideoAvatarStore.fileFor(context, row.peerNodeId)?.length() == expected.bytes) return
        val file = receivedFile(row.transferId, row.displayName) ?: return
        if (!PeerVideoAvatarStore.install(context, row.peerNodeId, file, expected)) {
            Log.w(TAG, "peer video avatar was not installed for ${row.peerNodeId}")
            PeerVideoAvatarStore.bump(context, "file-reject")
        } else {
            PeerVideoAvatarStore.bump(context, "file-in")
        }
    }
}
