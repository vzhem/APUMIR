package com.vladimir.messenger.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Возврат в меню настроек на том же месте. Владелец 2026-10-29: «в каком
 * положении переходил — в такое же и возвращалось».
 */
class SettingsScrollMemoryTest {

    @Test
    fun `an unknown menu starts at the top`() {
        val memory = SettingsScrollMemory()
        assertTrue(memory.anchorFor(SettingsScrollMemory.ROOT).isTop)
    }

    @Test
    fun `returning to a menu restores where it was left`() {
        val memory = SettingsScrollMemory()
        memory.remember(SettingsScrollMemory.ROOT, SettingsScrollMemory.Anchor(7, 120))
        val anchor = memory.anchorFor(SettingsScrollMemory.ROOT)
        assertEquals(7, anchor.index)
        assertEquals(120, anchor.offset)
    }

    @Test
    fun `each menu keeps its own position`() {
        val memory = SettingsScrollMemory()
        memory.remember(SettingsScrollMemory.ROOT, SettingsScrollMemory.Anchor(4, 0))
        memory.remember("Notifications", SettingsScrollMemory.Anchor(2, 40))
        assertEquals(4, memory.anchorFor(SettingsScrollMemory.ROOT).index)
        assertEquals(2, memory.anchorFor("Notifications").index)
        assertEquals(0, memory.anchorFor("Security").index)
    }

    @Test
    fun `scrolling to the top is not stored so the map does not grow`() {
        val memory = SettingsScrollMemory()
        memory.remember(SettingsScrollMemory.ROOT, SettingsScrollMemory.Anchor(9, 10))
        memory.remember(SettingsScrollMemory.ROOT, SettingsScrollMemory.Anchor.TOP)
        assertTrue(memory.anchorFor(SettingsScrollMemory.ROOT).isTop)
        assertEquals(0, memory.rememberedMenus)
    }

    @Test
    fun `positions survive a rotate through the saveable form`() {
        val memory = SettingsScrollMemory()
        memory.remember(SettingsScrollMemory.ROOT, SettingsScrollMemory.Anchor(11, 6))
        memory.remember("Network", SettingsScrollMemory.Anchor(3, 90))
        val restored = SettingsScrollMemory.fromFlatten(memory.flatten())
        assertEquals(11, restored.anchorFor(SettingsScrollMemory.ROOT).index)
        assertEquals(6, restored.anchorFor(SettingsScrollMemory.ROOT).offset)
        assertEquals(3, restored.anchorFor("Network").index)
    }

    @Test
    fun `unknown menu codes are skipped so they cannot corrupt the root list`() {
        val memory = SettingsScrollMemory.fromFlatten(listOf(999, 5, 5))
        assertTrue(memory.anchorFor(SettingsScrollMemory.ROOT).isTop)
        assertEquals(0, memory.rememberedMenus)
        assertEquals(SettingsScrollMemory.ROOT, SettingsScrollMemory.menuName(999))
    }

    @Test
    fun `flatten is a flat list of primitives for rememberSaveable`() {
        val memory = SettingsScrollMemory()
        memory.remember("Files", SettingsScrollMemory.Anchor(2, 8))
        val flat = memory.flatten()
        assertEquals(3, flat.size)
        assertEquals(SettingsScrollMemory.menuKey("Files"), flat[0])
        assertEquals(2, flat[1])
        assertEquals(8, flat[2])
    }
}
