package com.vladimir.messenger.data.file

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.fileTransferCryptoSelfTest

@RunWith(AndroidJUnit4::class)
class FileTransferCryptoInstrumentedTest {
    @Test
    fun productionManifestAndChunkAeadPassOnAndroid() {
        assertTrue(fileTransferCryptoSelfTest())
    }
}
