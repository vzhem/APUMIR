package com.vladimir.messenger.ui.screens.call

import com.vladimir.messenger.R
import com.vladimir.messenger.data.call.CallStateMachine.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCallHangupTest {
    @Test fun `label follows call phase`() {
        assertEquals(R.string.call_decline, videoHangupLabel(Phase.INCOMING))
        assertEquals(R.string.call_cancel, videoHangupLabel(Phase.OFFERING))
        assertEquals(R.string.call_cancel, videoHangupLabel(Phase.RINGING))
        assertEquals(R.string.call_end, videoHangupLabel(Phase.CONNECTING))
        assertEquals(R.string.call_end, videoHangupLabel(Phase.ACTIVE))
        assertNull(videoHangupLabel(Phase.IDLE))
        assertNull(videoHangupLabel(Phase.ENDED))
    }
}
