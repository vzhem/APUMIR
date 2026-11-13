package com.vladimir.messenger.data.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.p2p_core.CoreException
import uniffi.p2p_core.derivePushWakeMaterial
import uniffi.p2p_core.signPushWakeRegistration

/** Offline configuration only: never requests a token or contacts FCM. */
@RunWith(AndroidJUnit4::class)
class FirebaseClientConfigurationInstrumentedTest {
    @Test fun configuredSdkHasAutoInitDisabledWithoutTokenRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(FirebaseClientConfiguration.Status.CONFIGURED, FirebaseClientConfiguration.inspect(context))
        assertFalse(FirebaseMessaging.getInstance().isAutoInitEnabled)
    }

    @Test fun wakeCryptoFfiRejectsInvalidInputWithoutStartingPush() {
        assertThrows(CoreException.CryptoException::class.java) {
            derivePushWakeMaterial(byteArrayOf(), byteArrayOf(2), ByteArray(32), ByteArray(16), true)
        }
        assertThrows(CoreException.CryptoException::class.java) {
            signPushWakeRegistration(byteArrayOf(), ByteArray(32), ByteArray(16), 1uL, 1uL, byteArrayOf(), true)
        }
    }
}
