package com.vladimir.messenger.data.featurepacks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каталог докачиваемых пакетов: цифры замера CI (прогон 41695927881),
 * арифметика догрузки для одного телефона и состав файлов релиза.
 */
class FeaturePackCatalogTest {

    @Test
    fun catalogHasThreePacksForEveryReleaseAbi() {
        assertEquals(
            listOf("translate", "voice-text", "face-effects"),
            FeaturePackCatalog.packs.map { it.id },
        )
        FeaturePackCatalog.packs.forEach { pack ->
            assertEquals(
                FeaturePackCatalog.releaseAbis.toSet(),
                pack.sizeBytesByAbi.keys,
            )
            assertTrue(pack.nativeLibs.isNotEmpty())
            assertTrue(pack.sizeBytesByAbi.values.all { it > 0 })
        }
    }

    @Test
    fun phoneDownloadsOnlyItsOwnAbi() {
        val arm64 = FeaturePackCatalog.totalBytesAll(FeaturePackCatalog.ABI_ARM64)
        // 37.1 (перевод) + 8.5 (голос) + 8.1 (лицо) — замеренные значения arm64.
        assertEquals(53.7, arm64 / 1024.0 / 1024.0, 0.15)
        val all = FeaturePackCatalog.packs.map { it.id }
        assertEquals(arm64, FeaturePackCatalog.totalBytes(all, FeaturePackCatalog.ABI_ARM64))
        assertEquals(0L, FeaturePackCatalog.totalBytes(emptyList(), FeaturePackCatalog.ABI_ARM64))
    }

    @Test
    fun translationIsTheHeavyPackAndCoreStaysInBase() {
        val translate = FeaturePackCatalog.packFor(FeaturePackFeature.MESSAGE_TRANSLATION)
        assertTrue(translate.nativeLibs.contains("libxeno_native.so"))
        // Ядро сети не пакет: без libp2p_core нет ни базы, ни догрузки.
        assertTrue(FeaturePackCatalog.packs.none { pack ->
            pack.nativeLibs.any { it.contains("p2p_core") }
        })
        FeaturePackFeature.entries.forEach { feature ->
            assertEquals(feature, FeaturePackCatalog.packFor(feature).feature)
        }
    }

    @Test
    fun releasePublishesOneFilePerPackAndAbi() {
        val names = FeaturePackCatalog.releaseFileNames()
        assertEquals(3 * 3, names.size)
        assertTrue("feature-pack-translate-arm64-v8a.zip" in names)
        assertTrue("feature-pack-voice-text-x86_64.zip" in names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun unknownAbiShowsTheLargestHonestSize() {
        val pack = FeaturePackCatalog.pack("translate")!!
        assertEquals(
            pack.sizeBytesByAbi.values.max(),
            pack.sizeBytesFor("unknown-abi"),
        )
    }
}
