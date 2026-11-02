package com.vladimir.messenger.data.profile

/**
 * Pure limits and naming rules for the local profile background.
 *
 * The image is a private device customization: it is not a network avatar,
 * not a group wallpaper and not part of profile mirror/backup. Keeping the
 * policy free of Android types makes the storage and memory guards testable
 * on the JVM.
 */
object ProfileBackgroundPolicy {
    /** Source files larger than this are rejected before decoding. */
    const val MAX_SOURCE_BYTES: Long = 24L * 1024 * 1024

    /** The stored JPEG is deliberately small enough not to recreate storage bloat. */
    const val MAX_IMAGE_BYTES: Int = 512 * 1024

    /** Longest side of the stored image. */
    const val MAX_SIDE: Int = 1600

    /** Absurd source dimensions are rejected even if the file itself is small. */
    const val MAX_SOURCE_SIDE: Int = 20_000

    /** Compression ladder: first reduce detail, then quality; never upscale. */
    val SIDES: List<Int> = listOf(1600, 1280, 1024, 800)
    val QUALITIES: List<Int> = listOf(85, 72, 60)

    private val FILE_NAME = Regex("background-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
    private val TEMP_NAME = Regex("background-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg\\.tmp")

    fun validSourceDimensions(width: Int, height: Int): Boolean =
        width in 1..MAX_SOURCE_SIDE && height in 1..MAX_SOURCE_SIDE

    /** Power-of-two sampling understood by BitmapFactory; at least 1. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        require(width > 0 && height > 0 && maxSide > 0)
        var sample = 1
        while (maxOf(width, height) / sample > maxSide) sample *= 2
        return sample
    }

    /** Fit inside [side] without upscaling; both results are at least 1 px. */
    fun scaledSize(width: Int, height: Int, side: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && side > 0)
        val ratio = minOf(1f, side.toFloat() / maxOf(width, height))
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    fun isImageFileName(name: String): Boolean = FILE_NAME.matches(name)

    fun isTempFileName(name: String): Boolean = TEMP_NAME.matches(name)

    /** Only our exact JPEG/tmp names are managed; traversal and foreign files are not. */
    fun isManagedFileName(name: String): Boolean = isImageFileName(name) || isTempFileName(name)
}
