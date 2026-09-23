// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlin.test.Test
import kotlin.test.assertEquals

/// **A Bluetooth grant that outlives the Activity which asked still connects.** The result
/// API redelivers the answer to the NEXT instance after a rotation or a reclaimed process;
/// that instance has no callback, and used to drop the grant — a second Connect tap for a
/// question already answered yes.
class BluetoothPermissionAnswerTests {

    @Test
    fun theInstanceThatAskedGetsTheAnswerEitherWay() {
        for (granted in listOf(true, false)) {
            assertEquals(BluetoothPermissionAnswer.deliver,
                BluetoothPermissionAnswer.of(hasCallback = true, connectRequested = true, allGranted = granted))
        }
    }

    @Test
    fun aRecreatedInstanceResumesTheConnectOnAGrant() {
        assertEquals(BluetoothPermissionAnswer.resumeConnect,
            BluetoothPermissionAnswer.of(hasCallback = false, connectRequested = true, allGranted = true))
    }

    /// A refusal with no screen to report it to, or a grant nobody asked for, does nothing.
    @Test
    fun aRefusalOrAnUnrequestedGrantIsDropped() {
        assertEquals(BluetoothPermissionAnswer.drop,
            BluetoothPermissionAnswer.of(hasCallback = false, connectRequested = true, allGranted = false))
        assertEquals(BluetoothPermissionAnswer.drop,
            BluetoothPermissionAnswer.of(hasCallback = false, connectRequested = false, allGranted = true))
    }
}
