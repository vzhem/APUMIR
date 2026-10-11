package com.vladimir.messenger.ui.screens.groups

import com.vladimir.messenger.data.group.GroupRole
import com.vladimir.messenger.data.group.GroupSummary
import com.vladimir.messenger.data.local.entity.DirectoryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityListTest {
    @Test
    fun combinesGroupsWithOwnedPrivateChannelsAndSubscriptions() = runTest {
        val groups = MutableStateFlow(listOf(community("group")))
        val channels = MutableStateFlow(listOf(
            community("own-channel", isChannel = true, role = GroupRole.OWNER, isPublic = false),
            community("subscription", isChannel = true),
        ))
        val snapshots = mutableListOf<List<GroupSummary>>()
        backgroundScope.launch { CommunityList.observe(groups, channels).collect { snapshots += it } }
        runCurrent()

        assertEquals(listOf("group", "own-channel", "subscription"), snapshots.last().map { it.id })
        assertEquals(listOf("group"), snapshots.last().filter { !it.isChannel }.map { it.id })
        assertEquals(listOf("own-channel", "subscription"), snapshots.last().filter { it.isChannel }.map { it.id })
    }

    @Test
    fun channelChangesUpdateTheListWithoutAnyGroupChange() = runTest {
        val groups = MutableStateFlow<List<GroupSummary>>(emptyList())
        val channels = MutableStateFlow<List<GroupSummary>>(emptyList())
        val snapshots = mutableListOf<List<GroupSummary>>()
        backgroundScope.launch { CommunityList.observe(groups, channels).collect { snapshots += it } }
        runCurrent()
        assertTrue(snapshots.last().isEmpty())

        val channel = community("channel", title = "Первое название", isChannel = true)
        channels.value = listOf(channel)
        runCurrent()
        assertEquals(listOf(channel), snapshots.last())

        channels.value = listOf(channel.copy(title = "Новое название", unreadCount = 3))
        runCurrent()
        assertEquals("Новое название", snapshots.last().single().title)
        assertEquals(3, snapshots.last().single().unreadCount)

        channels.value = emptyList()
        runCurrent()
        assertTrue(snapshots.last().isEmpty())
    }

    @Test
    fun waitsForBothInitialSnapshotsBeforePublishingAList() = runTest {
        val groups = MutableSharedFlow<List<GroupSummary>>(extraBufferCapacity = 1)
        val channels = MutableSharedFlow<List<GroupSummary>>(extraBufferCapacity = 1)
        val snapshots = mutableListOf<List<GroupSummary>>()
        backgroundScope.launch { CommunityList.observe(groups, channels).collect { snapshots += it } }
        runCurrent()

        groups.emit(listOf(community("group")))
        runCurrent()
        assertTrue(snapshots.isEmpty())

        channels.emit(listOf(community("channel", isChannel = true)))
        runCurrent()
        assertEquals(listOf("group", "channel"), snapshots.single().map { it.id })
    }

    @Test
    fun searchIncludesChannelsIgnoresCaseAndTrimsWhitespace() {
        val communities = listOf(
            community("group", title = "Общение"),
            community("ru", title = "Новости APU", isChannel = true),
            community("en", title = "APU News", isChannel = true),
        )
        assertEquals(listOf("ru"), CommunityList.filter(communities, " НОВОСТИ ").map { it.id })
        assertEquals(listOf("en"), CommunityList.filter(communities, "news").map { it.id })
        assertEquals(communities, CommunityList.filter(communities, "  "))
        assertTrue(CommunityList.filter(communities, "missing").isEmpty())
    }

    @Test
    fun directoryExcludesAllJoinedGroupsAndChannelsAndPrivateEntries() {
        val joined = listOf(community("group"), community("channel", isChannel = true))
        val directory = listOf(
            entry("group", isChannel = false),
            entry("channel"),
            entry("closed", needsApproval = true),
            entry("new-channel"),
            entry("new-group", isChannel = false),
        )
        assertEquals(listOf("new-channel", "new-group"),
            CommunityList.directoryMatches(directory, joined, "").map { it.groupId })
    }

    @Test
    fun searchDoesNotOfferSubscriptionAgainEvenIfDirectoryTitleIsStale() {
        val joined = listOf(community("channel", title = "Новое название", isChannel = true))
        val directory = listOf(entry("channel", title = "Old News"), entry("other", title = "Other News"))
        assertTrue(CommunityList.filter(joined, "news").isEmpty())
        assertEquals(listOf("other"), CommunityList.directoryMatches(directory, joined, " NEWS ").map { it.groupId })
    }

    @Test
    fun suggestionsAreLimitedAfterExcludingSubscriptionsAndSortedByRecency() {
        val directory = (0..19).map { entry("channel-$it", updatedAtMs = it.toLong()) }
        val joined = listOf(community("channel-19", isChannel = true), community("channel-18", isChannel = true))
        val found = CommunityList.directoryMatches(directory, joined, "")
        assertEquals(12, found.size)
        assertEquals((17 downTo 6).map { "channel-$it" }, found.map { it.groupId })
    }

    private fun community(
        id: String,
        title: String = id,
        isChannel: Boolean = false,
        role: String = GroupRole.MEMBER,
        isPublic: Boolean = true,
    ) = GroupSummary(
        id = id, title = title, about = "", ownerId = "owner", isPublic = isPublic,
        topicsEnabled = !isChannel, isChannel = isChannel, memberPermissions = 0L,
        memberCount = 2, unreadCount = 0, lastMessagePreview = null, lastMessageAtMs = null,
        myRole = role, pendingRequests = 0,
    )

    private fun entry(
        id: String,
        title: String = id,
        isChannel: Boolean = true,
        needsApproval: Boolean = false,
        updatedAtMs: Long = 0L,
    ) = DirectoryEntity(
        groupId = id, title = title, ownerId = "owner", slug = "invite-$id",
        isChannel = isChannel, needsApproval = needsApproval, updatedAtMs = updatedAtMs,
    )
}
