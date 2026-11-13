package com.vladimir.messenger.data.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedApkAdoptionTest {
    @Test
    fun `simultaneous completion and settings scans do not adopt the same APK twice`() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = launch {
            CompletedApkAdoption.run {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            CompletedApkAdoption.run { secondEntered.complete(Unit) }
        }
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered.isCompleted)
    }
}
