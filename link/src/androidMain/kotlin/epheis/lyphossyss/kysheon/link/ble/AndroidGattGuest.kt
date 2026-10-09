package epheis.lyphossyss.kysheon.link.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.util.Log
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import kotlinx.coroutines.Job
import java.util.UUID

/**
 * Guest role: central that scans for [BleUuids.SERVICE], keeps the strongest RSSI seen during
 * [AndroidBleTimings.scanCollectWindow] (the intended host lies right next to this phone), then connects:
 *
 * connected → requestConnectionPriority(HIGH) → requestMtu(247) → discoverServices →
 * setCharacteristicNotification(TX) + CCCD write → Connected.
 *
 * Every GATT operation waits for its callback before the next one starts (Android runs one at a time per
 * connection); once Connected, characteristic writes go through [SerialSender]. A failed attempt (status 133
 * GATT_ERROR, connection timeout, failed setup step) is retried once after [AndroidBleTimings.retryDelay], then the
 * session ends with `Lost(ConnectionFailed, code)`. Each `BluetoothGatt` is always closed.
 *
 * MissingPermission is suppressed for the whole class on purpose: [start] checks BLUETOOTH_SCAN and
 * BLUETOOTH_CONNECT (ACCESS_FINE_LOCATION below Android 12) through [requireReady] before any call, and every call
 * runs inside [AndroidBleLink.runGuarded] (events) or [quietly] (cleanup), which turn a SecurityException —
 * permission revoked mid-session — into `Lost(PermissionDenied)` or ignore it while releasing.
 */
@SuppressLint("MissingPermission")
internal class GuestSession(link: AndroidBleLink) : BleSession(link, "guest") {

    private enum class Phase { Scanning, Connecting, RequestingMtu, Discovering, Subscribing, Ready }

    private var phase = Phase.Scanning
        set(value) {
            if (field != value) Log.d(AndroidBleLink.TAG, "guest phase $field -> $value")
            field = value
        }
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val seen = HashMap<String, Sighting>()
    private var pickJob: Job? = null
    private var target: BluetoothDevice? = null
    private var attempt = 0
    private var attemptWatchdog: Job? = null
    private var rx: BluetoothGattCharacteristic? = null

    /** Current GATT client; read by onCharacteristicWrite on a binder thread. */
    @Volatile
    private var gatt: BluetoothGatt? = null

    private class Sighting(val device: BluetoothDevice, val rssi: Int)

    override fun peerDevice(): BluetoothDevice? = target

    override fun start() {
        val adapter = requireReady(AndroidBluetooth.guestPermissions())
        val leScanner = adapter.bluetoothLeScanner ?: throw LinkFailure(LostReason.BluetoothOff)
        scanner = leScanner
        link.setState(this, LinkState.Scanning)
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(BleUuids.SERVICE)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        leScanner.startScan(filters, settings, scanCallback)
        scanning = true
        link.after(this, link.timings.scanTimeout) {
            // A host seen just before the deadline still gets its collection window.
            if (phase == Phase.Scanning && seen.isEmpty()) throw LinkFailure(LostReason.PeerNotFound)
        }
    }

    // ---- Scan ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device ?: return
            val rssi = result.rssi
            link.post(this@GuestSession) { onSighting(device, rssi) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            link.post(this@GuestSession) {
                scanning = false
                val reason = if (errorCode == SCAN_FAILED_FEATURE_UNSUPPORTED) LostReason.Unsupported else LostReason.ConnectionFailed
                throw LinkFailure(reason, errorCode)
            }
        }
    }

    private fun onSighting(device: BluetoothDevice, rssi: Int) {
        if (phase != Phase.Scanning) return
        val key = device.address
        val previous = seen[key]
        if (previous == null || rssi > previous.rssi) seen[key] = Sighting(device, rssi)
        if (pickJob == null) pickJob = link.after(this, link.timings.scanCollectWindow) { pickClosest() }
    }

    private fun pickClosest() {
        if (phase != Phase.Scanning) return
        val best = seen.values.maxByOrNull { it.rssi } ?: throw LinkFailure(LostReason.PeerNotFound)
        stopScan()
        target = best.device
        phase = Phase.Connecting
        link.setState(this, LinkState.Connecting)
        link.describe(this)
        connect()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanner?.stopScan(scanCallback)
    }

    // ---- Connection and setup ----

    private fun connect() {
        val device = target ?: throw LinkFailure(LostReason.PeerNotFound)
        attempt++
        phase = Phase.Connecting
        closeGatt()
        // autoConnect = false: direct connection, fastest for a device that is advertising right now.
        @Suppress("DEPRECATION") // Replaced in API 37 only; this overload covers minSdk 26.
        val client = device.connectGatt(link.appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (client == null) {
            attemptFailed(null)
            return
        }
        gatt = client
        restartWatchdog(link.timings.connectTimeout)
    }

    private fun restartWatchdog(timeout: kotlin.time.Duration) {
        attemptWatchdog?.cancel()
        val watched = gatt
        attemptWatchdog = link.after(this, timeout) {
            if (phase != Phase.Ready && gatt === watched) attemptFailed(null)
        }
    }

    /** First failure: close, wait, retry once. Second failure: Lost(ConnectionFailed, code). */
    private fun attemptFailed(code: Int?) {
        link.recordPlatformCode(code)
        attemptWatchdog?.cancel()
        closeGatt()
        if (attempt >= MAX_ATTEMPTS) throw LinkFailure(LostReason.ConnectionFailed, code)
        phase = Phase.Connecting
        link.after(this, link.timings.retryDelay) { connect() }
    }

    private fun onConnectionChange(client: BluetoothGatt, status: Int, newState: Int) {
        if (client !== gatt) return
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            if (phase != Phase.Connecting) return
            restartWatchdog(link.timings.setupTimeout)
            // Shorter connection interval for the game; a hint, the result is not reported (no public callback).
            client.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            phase = Phase.RequestingMtu
            if (!client.requestMtu(BleUuids.REQUESTED_MTU)) discover(client)
            return
        }
        // Disconnected, or an error status (133 GATT_ERROR, 147 GATT_CONNECTION_TIMEOUT, 8 supervision timeout…).
        if (phase == Phase.Ready) throw LinkFailure(LostReason.PeerDisconnected, status)
        attemptFailed(status)
    }

    private fun handleMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) {
        Log.d(AndroidBleLink.TAG, "guest mtu=$mtu status=$status phase=$phase same=${client === gatt}")
        if (client !== gatt) return
        if (status == BluetoothGatt.GATT_SUCCESS) link.recordMtu(mtu) else link.recordPlatformCode(status)
        // A failed MTU exchange is not fatal: every frame fits the default MTU (KYS-0002).
        if (phase == Phase.RequestingMtu) discover(client)
    }

    private fun discover(client: BluetoothGatt) {
        phase = Phase.Discovering
        if (!client.discoverServices()) attemptFailed(null)
    }

    private fun handleServicesDiscovered(client: BluetoothGatt, status: Int) {
        if (client !== gatt || phase != Phase.Discovering) return
        if (status != BluetoothGatt.GATT_SUCCESS) return attemptFailed(status)
        val service = client.getService(UUID.fromString(BleUuids.SERVICE))
        val rxCharacteristic = service?.getCharacteristic(UUID.fromString(BleUuids.RX))
        val txCharacteristic = service?.getCharacteristic(UUID.fromString(BleUuids.TX))
        val cccd = txCharacteristic?.getDescriptor(UUID.fromString(BleUuids.CCCD))
        if (rxCharacteristic == null || txCharacteristic == null || cccd == null) return attemptFailed(null)
        rx = rxCharacteristic
        phase = Phase.Subscribing
        // Local registration (no radio traffic), then the CCCD write tells the host to send notifications.
        if (!client.setCharacteristicNotification(txCharacteristic, true)) return attemptFailed(null)
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = client.writeDescriptor(cccd, enable)
            if (code != BluetoothStatusCodes.SUCCESS) {
                if (code == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) {
                    throw LinkFailure(LostReason.PermissionDenied, code)
                }
                attemptFailed(code)
            }
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            if (!client.writeDescriptor(cccd)) attemptFailed(null)
        }
    }

    private fun handleDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (client !== gatt || phase != Phase.Subscribing) return
        if (descriptor.uuid != UUID.fromString(BleUuids.CCCD)) return
        if (status != BluetoothGatt.GATT_SUCCESS) return attemptFailed(status)
        phase = Phase.Ready
        attemptWatchdog?.cancel()
        sender = link.newSender(this, ::writeToHost)
        link.setConnected(this)
    }

    /** One write without response; [SerialSender] waits for onCharacteristicWrite before the next one. */
    private fun writeToHost(frame: ByteArray): WriteStart {
        val client = gatt ?: return WriteStart.Rejected()
        val characteristic = rx ?: return WriteStart.Rejected()
        val noResponse = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = client.writeCharacteristic(characteristic, frame, noResponse)
            if (code == BluetoothStatusCodes.SUCCESS) {
                WriteStart.Started
            } else {
                if (code == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) {
                    link.post(this) { throw LinkFailure(LostReason.PermissionDenied, code) }
                }
                WriteStart.Rejected(code)
            }
        } else {
            characteristic.writeType = noResponse
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            val accepted = client.writeCharacteristic(characteristic)
            if (accepted) WriteStart.Started else WriteStart.Rejected()
        }
    }

    private fun onNotification(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (client !== gatt || phase != Phase.Ready) return
        if (characteristic.uuid == UUID.fromString(BleUuids.TX)) link.deliver(this, value)
    }

    // The handlers above are named handle* on purpose: inside this object, a call like `onMtuChanged(gatt, mtu, status)`
    // resolves to the override itself, which re-posted itself forever (found on a CMF Phone 1, see KYS-0024).
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            link.post(this@GuestSession) { onConnectionChange(gatt, status, newState) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            link.post(this@GuestSession) { handleMtuChanged(gatt, mtu, status) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            link.post(this@GuestSession) { handleServicesDiscovered(gatt, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            link.post(this@GuestSession) { handleDescriptorWrite(gatt, descriptor, status) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // Thread-safe completion signal, no need to go through the event loop.
            if (gatt === this@GuestSession.gatt) sender?.confirm(status == BluetoothGatt.GATT_SUCCESS, status)
        }

        /** API 33+: the value is passed in, the characteristic object may already hold a newer one. */
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val frame = value.copyOf()
            link.post(this@GuestSession) { onNotification(gatt, characteristic, frame) }
        }

        /** Below API 33 only (on 33+ the overload above is called instead): read the value right away. */
        @Deprecated("Replaced by the overload with a value on API 33+")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val frame = characteristic.value?.copyOf() ?: return
            link.post(this@GuestSession) { onNotification(gatt, characteristic, frame) }
        }
    }

    private fun closeGatt() {
        val client = gatt ?: return
        gatt = null
        quietly("disconnect") { client.disconnect() }
        quietly("close") { client.close() }
    }

    override fun releaseRadio() {
        quietly("stopScan") { stopScan() }
        closeGatt()
    }

    private companion object {
        /** First attempt plus one retry. */
        const val MAX_ATTEMPTS = 2
    }
}
