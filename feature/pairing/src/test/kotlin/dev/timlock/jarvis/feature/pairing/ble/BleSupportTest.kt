package dev.timlock.jarvis.feature.pairing.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleSupportTest {
    @Test fun advertiseErrorsMapToCodes() {
        assertEquals("advertise_data_too_large", BleSupport.advertiseError(1))
        assertEquals("advertise_too_many_advertisers", BleSupport.advertiseError(2))
        assertEquals("advertise_already_started", BleSupport.advertiseError(3))
        assertEquals("advertise_internal_error", BleSupport.advertiseError(4))
        assertEquals("advertise_unsupported", BleSupport.advertiseError(5))
        assertEquals("advertise_failed_99", BleSupport.advertiseError(99))
    }

    @Test fun cccParsing() {
        assertEquals(true, BleSupport.parseCcc(byteArrayOf(1, 0)))
        assertEquals(false, BleSupport.parseCcc(byteArrayOf(0, 0)))
        assertNull(BleSupport.parseCcc(byteArrayOf(2, 0)))   // indication: not offered
        assertNull(BleSupport.parseCcc(byteArrayOf(1)))
        assertNull(BleSupport.parseCcc(null))
    }

    @Test fun ssidNormalization() {
        assertEquals("HomeNet", BleSupport.normalizeSsid("\"HomeNet\""))
        assertEquals("Plain", BleSupport.normalizeSsid("Plain"))
        assertNull(BleSupport.normalizeSsid("<unknown ssid>"))
        assertNull(BleSupport.normalizeSsid("\"\""))
        assertNull(BleSupport.normalizeSsid(null))
    }

    @Test fun preparedWritesAssembleByOffset() {
        val b = PreparedWriteBuffer()
        assertTrue(b.add(0, byteArrayOf(1, 2, 3)))
        assertTrue(b.add(3, byteArrayOf(4, 5)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), b.take())
        assertNull(b.take())   // reset after take
    }

    @Test fun preparedWriteLimitDropsBuffer() {
        val b = PreparedWriteBuffer(maxBytes = 4)
        assertTrue(b.add(0, byteArrayOf(1, 2)))
        assertFalse(b.add(2, byteArrayOf(3, 4, 5)))
        assertNull(b.take())
    }

    @Test fun cancelledExecuteDiscards() {
        val b = PreparedWriteBuffer()
        b.add(0, byteArrayOf(9))
        b.clear()
        assertNull(b.take())
    }
}
