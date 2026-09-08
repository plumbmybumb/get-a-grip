// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BroadcastScanFilterTests {
    @Test fun filtersByManufacturerWithoutRequiringANameServiceOrPayloadPattern() {
        val filter = whc06ScanFilters().single()
        assertEquals(0x0100, filter.manufacturerId)
        assertTrue(assertNotNull(filter.manufacturerData).isEmpty())
        assertNull(filter.manufacturerDataMask)
        assertNull(filter.deviceName)
        assertNull(filter.deviceAddress)
        assertNull(filter.serviceUuid)
    }

    @Suppress("DEPRECATION")
    @Test fun realFilterMatchesUnnamedManufacturerFramesAndRejectsOtherCompanyIds() {
        val manager = RuntimeEnvironment.getApplication()
            .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice("00:11:22:33:44:55")
        fun result(companyId: Int): ScanResult {
            // A synthetic advertisement with manufacturer data only: no name or service.
            val payload = ByteArray(12)
            payload[10] = 0x03
            payload[11] = 0x20 // 8.00 kg, decoded separately by the client
            val bytes = byteArrayOf(
                (payload.size + 3).toByte(), 0xff.toByte(),
                (companyId and 0xff).toByte(), (companyId shr 8).toByte(),
            ) + payload + byteArrayOf(0)
            // parseFromBytes is a framework implementation method, hidden from android.jar
            // but present in Robolectric's actual Android runtime. Exercise that parser and
            // ScanFilter.matches instead of substituting a mock ScanRecord.
            val record = ReflectionHelpers.callStaticMethod<ScanRecord>(
                ScanRecord::class.java, "parseFromBytes", ClassParameter.from(ByteArray::class.java, bytes),
            )
            assertNull(record.deviceName)
            assertTrue(record.serviceUuids.isNullOrEmpty())
            return ScanResult(device, record, -40, 1_000_000L)
        }
        val filter = whc06ScanFilters().single()
        assertTrue(filter.matches(result(0x0100)))
        assertFalse(filter.matches(result(0x0101)))
        assertFalse(filter.matches(ScanResult(device, null, -40, 1_000_000L)))
    }
}
