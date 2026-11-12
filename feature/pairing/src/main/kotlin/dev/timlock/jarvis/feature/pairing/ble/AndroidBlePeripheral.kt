package dev.timlock.jarvis.feature.pairing.ble

import android.content.Context
import dev.timlock.jarvis.core.transport.BlePeripheral

/** M7 CONTRACT STUB — track B implements (BluetoothLeAdvertiser + BluetoothGattServer byte pipe, §6.6). */
class AndroidBlePeripheral(private val context: Context) : BlePeripheral {
    override fun readinessProblem(): String? = "not_implemented"
    override fun open(bleName: String, listener: BlePeripheral.Listener): String? = "not_implemented"
    override fun mtu(): Int = 23
    override fun sendPackets(packets: List<ByteArray>) = Unit
    override fun disconnect(delayMs: Long) = Unit
    override fun close() = Unit
}
