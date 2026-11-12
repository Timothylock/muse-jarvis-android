package dev.timlock.jarvis.feature.pairing.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import dev.timlock.jarvis.core.transport.BlePeripheral
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Kotlin BLE GATT peripheral (§6.6): a byte pipe with the SDK's service/characteristic UUIDs (ble_server.py:52-54;
 * flags ble_server.py:253-255). RX = write + write-without-response, TX = read + notify (+ CCC 0x2902). All framing
 * and crypto live in the SDK's Python. Logs (tag JARVIS_BLE) carry events and sizes, never packet contents.
 *
 * Threading: GATT callbacks arrive on binder threads and must stay short ([BlePeripheral.Listener.onWrite] only
 * feeds the SDK's assembler). [sendPackets] blocks and must be called from a worker thread.
 */
@SuppressLint("MissingPermission") // every call is guarded by hasConnect()/hasAdvertise() or wrapped in SecurityException handlers
class AndroidBlePeripheral(context: Context) : BlePeripheral {
    private val app = context.applicationContext
    private val manager: BluetoothManager? = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val lock = Any()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var listener: BlePeripheral.Listener? = null
    private var opened = false
    private var originalName: String? = null
    private var renamed = false
    private var central: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var notifyLatch: CountDownLatch? = null
    @Volatile private var notifyStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile private var serviceLatch: CountDownLatch? = null
    @Volatile private var advertiseLatch: CountDownLatch? = null
    @Volatile private var advertiseError: String? = null
    private val prepared = PreparedWriteBuffer()
    private val sendLock = Any()
    private var scheduler: ScheduledExecutorService? = null

    /** GATT status of the last onServiceAdded (null = never reported). For the ble_smoke diagnostic. */
    @Volatile var lastServiceAddedStatus: Int? = null
        private set

    // ---- readiness ----
    override fun readinessProblem(): String? {
        val a = adapter
        if (a == null || !app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "bluetooth_unsupported"
        if (!hasConnect() || !hasAdvertise()) return "no_permission"
        if (!a.isEnabled) return "bluetooth_off"
        if (!a.isMultipleAdvertisementSupported || a.bluetoothLeAdvertiser == null) return "advertise_unsupported"
        return null
    }

    /** BluetoothAdapter.isMultipleAdvertisementSupported (diagnostic; false on devices that cannot advertise). */
    fun multipleAdvertisementSupported(): Boolean = adapter?.isMultipleAdvertisementSupported == true

    private fun hasPermission(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun hasConnect() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun hasAdvertise() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)

    // ---- open / close ----
    override fun open(bleName: String, listener: BlePeripheral.Listener): String? {
        readinessProblem()?.let { return it }
        synchronized(lock) {
            if (opened) return "already_open"
            val err = try { openLocked(bleName, listener) } catch (e: SecurityException) { "no_permission" }
            if (err != null) { Log.w(TAG, "open failed: $err"); closeLocked() }
            return err
        }
    }

    private fun openLocked(bleName: String, l: BlePeripheral.Listener): String? {
        val a = adapter ?: return "bluetooth_unsupported"
        val mgr = manager ?: return "bluetooth_unsupported"
        listener = l
        opened = true
        mtu = DEFAULT_MTU
        subscribed = false
        central = null
        prepared.clear()
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ble-peripheral").apply { isDaemon = true } }

        // Android advertises the ADAPTER name in the scan response: rename for the window, restore in close().
        // The pre-window name is persisted so a process death mid-window cannot leave "MuseGadgetXXXXXX" behind.
        val current = a.name
        originalName = prefs.getString(KEY_ORIGINAL_NAME, null) ?: current
        if (current != bleName) {
            prefs.edit().putString(KEY_ORIGINAL_NAME, originalName).commit()
            renamed = true
            if (!a.setName(bleName)) return "name_set_failed"
            if (!waitFor { a.name == bleName }) return "name_set_failed"
        }

        val srv = mgr.openGattServer(app, gattCallback) ?: return "gatt_open_failed"
        server = srv
        val service = BluetoothGattService(UUID.fromString(BlePeripheral.SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            UUID.fromString(BlePeripheral.RX_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val txChar = BluetoothGattCharacteristic(
            UUID.fromString(BlePeripheral.TX_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        txChar.addDescriptor(BluetoothGattDescriptor(
            CCC_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx)
        service.addCharacteristic(txChar)
        tx = txChar
        val added = CountDownLatch(1)
        serviceLatch = added
        lastServiceAddedStatus = null
        if (!srv.addService(service)) return "gatt_add_service_failed"
        if (!added.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "gatt_add_service_timeout"
        val status = lastServiceAddedStatus
        if (status != BluetoothGatt.GATT_SUCCESS) return "gatt_add_service_failed_$status"

        return startAdvertising(a)
    }

    private fun startAdvertising(a: BluetoothAdapter): String? {
        val adv = a.bluetoothLeAdvertiser ?: return "advertise_unsupported"
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Legacy advertising is 31 bytes: flags(3) + 128-bit service UUID(18) leaves no room for the 18-byte name,
        // so the name goes in the scan response (adapter name, set above).
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid.fromString(BlePeripheral.SERVICE_UUID)).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val started = CountDownLatch(1)
        advertiseLatch = started
        advertiseError = null
        advertiseCallback = advertiseCb
        adv.startAdvertising(settings, data, scan, advertiseCb)
        if (!started.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return "advertise_timeout"
        return advertiseError
    }

    private val advertiseCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising started")
            advertiseLatch?.countDown()
        }

        override fun onStartFailure(errorCode: Int) {
            advertiseError = BleSupport.advertiseError(errorCode)
            Log.w(TAG, "advertising failed code=$errorCode")
            advertiseLatch?.countDown()
        }
    }

    override fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        val wasOpen = opened || server != null || renamed
        opened = false
        listener = null
        try { advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (e: Exception) { Log.w(TAG, "stopAdvertising: ${e.javaClass.simpleName}") }
        advertiseCallback = null
        try { server?.close() } catch (e: Exception) { Log.w(TAG, "server close: ${e.javaClass.simpleName}") }
        server = null
        tx = null
        central = null
        subscribed = false
        notifyLatch?.countDown()
        scheduler?.shutdownNow()
        scheduler = null
        if (renamed) {
            val orig = originalName
            try {
                if (orig != null && adapter?.setName(orig) == true) prefs.edit().remove(KEY_ORIGINAL_NAME).apply()
                else Log.w(TAG, "adapter name restore not confirmed")
            } catch (e: SecurityException) {
                Log.w(TAG, "adapter name restore: no permission")
            }
            renamed = false
        }
        if (wasOpen) Log.i(TAG, "closed")
    }

    // ---- data path ----
    override fun mtu(): Int = mtu

    override fun sendPackets(packets: List<ByteArray>) {
        if (Looper.myLooper() == Looper.getMainLooper()) { Log.e(TAG, "sendPackets on main thread refused"); return }
        synchronized(sendLock) {
            for ((i, packet) in packets.withIndex()) {
                val dev = central
                val txChar = tx
                val srv = server
                if (dev == null || txChar == null || srv == null || !subscribed) {
                    Log.w(TAG, "tx dropped: no subscribed central (${packets.size - i} packets)")
                    return
                }
                val latch = CountDownLatch(1)
                notifyLatch = latch
                notifyStatus = BluetoothGatt.GATT_SUCCESS
                val ok = try { notify(srv, dev, txChar, packet) } catch (e: SecurityException) { false }
                if (!ok) { Log.w(TAG, "notify rejected at packet $i/${packets.size} (${packet.size} B)"); notifyLatch = null; return }
                if (!latch.await(NOTIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "onNotificationSent timeout at packet $i")
                else if (notifyStatus != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "notify status $notifyStatus"); return }
                notifyLatch = null
                try { Thread.sleep(BlePeripheral.CHUNK_STAGGER_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(srv: BluetoothGattServer, dev: BluetoothDevice, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            srv.notifyCharacteristicChanged(dev, c, false, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.value = value
            srv.notifyCharacteristicChanged(dev, c, false)
        }

    override fun disconnect(delayMs: Long) {
        val dev = central ?: return
        val task = Runnable {
            try { if (central == dev) server?.cancelConnection(dev) } catch (e: SecurityException) { Log.w(TAG, "disconnect: no permission") }
        }
        val s = synchronized(lock) { scheduler }
        if (s == null || delayMs <= 0) task.run()
        else try { s.schedule(task, delayMs, TimeUnit.MILLISECONDS) } catch (e: Exception) { task.run() }
    }

    // ---- GATT callbacks (binder threads) ----
    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.i(TAG, "service added status=$status")
            lastServiceAddedStatus = status
            serviceLatch?.countDown()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val l: BlePeripheral.Listener?
                synchronized(lock) {
                    if (!opened) return
                    if (central != null && central != device) {
                        // Single central at a time.
                        try { server?.cancelConnection(device) } catch (e: SecurityException) { }
                        return
                    }
                    central = device
                    mtu = DEFAULT_MTU
                    subscribed = false
                    prepared.clear()
                    l = listener
                }
                l?.onConnected()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val l: BlePeripheral.Listener?
                synchronized(lock) {
                    if (central != device) return
                    central = null
                    mtu = DEFAULT_MTU
                    subscribed = false
                    prepared.clear()
                    l = listener
                }
                notifyLatch?.countDown()
                l?.onDisconnected()
                restartAdvertising()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.i(TAG, "mtu=$mtu")
            if (device == central) this@AndroidBlePeripheral.mtu = mtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            // TX is notify-driven; a read returns an empty value.
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val data = value ?: ByteArray(0)
            val allowed = characteristic.uuid == UUID.fromString(BlePeripheral.RX_UUID) && device == central
            if (!allowed) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            if (preparedWrite) {
                val ok = prepared.add(offset, data)
                Log.d(TAG, "prepared write ${data.size} B offset=$offset ok=$ok")
                respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, offset, data)
                return
            }
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
            Log.d(TAG, "rx ${data.size} B")
            listener?.onWrite(data)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val whole = if (execute && device == central) prepared.take() else { prepared.clear(); null }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (whole != null) {
                Log.d(TAG, "rx ${whole.size} B (long write)")
                listener?.onWrite(whole)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val v = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val on = if (descriptor.uuid == CCC_UUID && device == central && !preparedWrite) BleSupport.parseCcc(value) else null
            if (on == null) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            subscribed = on
            Log.i(TAG, "tx notifications ${if (on) "enabled" else "disabled"}")
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyStatus = status
            notifyLatch?.countDown()
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try { server?.sendResponse(device, requestId, status, offset, value) }
        catch (e: SecurityException) { Log.w(TAG, "sendResponse: no permission") }
    }

    /** Connectable advertising may stop once a central connects; resume so the phone can retry after a disconnect. */
    private fun restartAdvertising() {
        val s = synchronized(lock) { if (!opened) return; scheduler } ?: return
        try {
            s.execute {
                try {
                    val a = adapter ?: return@execute
                    synchronized(lock) { if (!opened) return@execute }
                    advertiseCallback?.let { a.bluetoothLeAdvertiser?.stopAdvertising(it) }
                    val err = startAdvertising(a)
                    Log.i(TAG, "re-advertise: ${err ?: "ok"}")
                } catch (e: Exception) { Log.w(TAG, "re-advertise: ${e.javaClass.simpleName}") }
            }
        } catch (e: Exception) { /* closed meanwhile */ }
    }

    private inline fun waitFor(check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + NAME_TIMEOUT_MS
        while (true) {
            if (check()) return true
            if (System.currentTimeMillis() > end) return false
            try { Thread.sleep(50) } catch (e: InterruptedException) { return false }
        }
    }

    companion object {
        const val TAG = "JARVIS_BLE"
        private const val PREFS = "jarvis_ble"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
        private const val DEFAULT_MTU = 23
        private const val OPEN_TIMEOUT_MS = 3000L
        private const val NOTIFY_TIMEOUT_MS = 500L
        private const val NAME_TIMEOUT_MS = 2000L
        private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
