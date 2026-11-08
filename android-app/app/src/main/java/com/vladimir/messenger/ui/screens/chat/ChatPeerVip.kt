package com.vladimir.messenger.ui.screens.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The chat peer and the VIP directory arrive independently; either must refresh the header. */
internal fun observeChatPeerVip(peerIds: Flow<String>, vipNodeIds: Flow<Set<String>>): Flow<Pair<String, Set<String>>> =
    combine(peerIds, vipNodeIds) { peer, vipIds -> peer.trim().lowercase() to vipIds }

/** Database errors must not terminate the screen observer; cancellation must propagate. */
internal suspend fun readChatPeerRank(store: com.vladimir.messenger.data.rank.PeerRankStore, peer: String): Int? =
    try { store.qualifiedFor(peer) }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) { android.util.Log.w("ChatDetailVM", "Peer rank unavailable", error); null }
