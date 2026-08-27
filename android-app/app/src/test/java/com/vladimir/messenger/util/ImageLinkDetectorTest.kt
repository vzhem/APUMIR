package com.vladimir.messenger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageLinkDetectorTest {

    @Test
    fun directGifLinksAreDetected() {
        assertEquals(
            "https://media.tenor.com/abc123/xyz.gif",
            ImageLinkDetector.directImageUrl("https://media.tenor.com/abc123/xyz.gif"),
        )
        assertEquals(
            "https://media.giphy.com/media/abc/giphy.gif",
            ImageLinkDetector.directImageUrl("https://media.giphy.com/media/abc/giphy.gif"),
        )
    }

    @Test
    fun queryAndFragmentDoNotHideTheExtension() {
        assertEquals(
            "https://example.com/cat.gif?size=large",
            ImageLinkDetector.directImageUrl("https://example.com/cat.gif?size=large"),
        )
        assertEquals(
            "https://example.com/cat.png#preview",
            ImageLinkDetector.directImageUrl("https://example.com/cat.png#preview"),
        )
    }

    @Test
    fun extensionCaseDoesNotMatter() {
        assertEquals(
            "https://example.com/PHOTO.JPG",
            ImageLinkDetector.directImageUrl("https://example.com/PHOTO.JPG"),
        )
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(
            "https://example.com/a.webp",
            ImageLinkDetector.directImageUrl("  https://example.com/a.webp\n"),
        )
    }

    @Test
    fun ordinaryLinksStayText() {
        assertNull(ImageLinkDetector.directImageUrl("https://apumir.app/i?r=abc"))
        assertNull(ImageLinkDetector.directImageUrl("https://github.com/vzhem/APUMIR"))
        // Расширение есть, но не картинка.
        assertNull(ImageLinkDetector.directImageUrl("https://example.com/archive.zip"))
    }

    @Test
    fun messageWithWordsIsNotAPreview() {
        assertNull(ImageLinkDetector.directImageUrl("Смотри https://example.com/cat.gif"))
        assertNull(ImageLinkDetector.directImageUrl("https://example.com/cat.gif спасибо"))
    }

    @Test
    fun severalLinksAreNotAPreview() {
        assertNull(
            ImageLinkDetector.directImageUrl(
                "https://example.com/a.gif\nhttps://example.com/b.gif",
            ),
        )
    }

    @Test
    fun emptyAndOtherSchemesAreIgnored() {
        assertNull(ImageLinkDetector.directImageUrl(null))
        assertNull(ImageLinkDetector.directImageUrl(""))
        assertNull(ImageLinkDetector.directImageUrl("   "))
        assertNull(ImageLinkDetector.directImageUrl("ftp://example.com/cat.gif"))
        assertNull(ImageLinkDetector.directImageUrl("p2pmessenger://add?node_id=pk_abc"))
    }

    @Test
    fun helperFlagMatchesTheDetector() {
        assertEquals(true, ImageLinkDetector.isDirectImage("https://example.com/a.gif"))
        assertEquals(false, ImageLinkDetector.isDirectImage("привет"))
    }
}
