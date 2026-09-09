package com.vladimir.messenger.data.local

import com.vladimir.messenger.data.local.entity.SavedItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фотографии сохранённого поста лежат одной колонкой; проверяем, что список
 * собирается и разбирается без потерь и без пустых строк.
 */
class SavedItemPhotosTest {

    private fun item(photos: String) = SavedItemEntity(
        id = "1",
        kind = "TEXT",
        text = "пост",
        savedAtMs = 0L,
        photos = photos,
    )

    @Test
    fun roundTripKeepsOrder() {
        val images = listOf("AAAA", "BBBB", "CCCC")
        val column = SavedItemEntity.joinPhotos(images)
        assertEquals(images, item(column).photoList())
    }

    @Test
    fun blankEntriesAreDropped() {
        assertEquals("AAAA\nBBBB", SavedItemEntity.joinPhotos(listOf("AAAA", "", "  ", "BBBB")))
        assertTrue(item("").photoList().isEmpty())
        assertTrue(item("\n\n").photoList().isEmpty())
    }

    /** Старые записи (до схемы 18) - без колонки: список пуст, а не падение. */
    @Test
    fun defaultIsEmpty() {
        val legacy = SavedItemEntity(id = "2", kind = "TEXT", text = "заметка", savedAtMs = 0L)
        assertEquals("", legacy.photos)
        assertTrue(legacy.photoList().isEmpty())
    }
}
