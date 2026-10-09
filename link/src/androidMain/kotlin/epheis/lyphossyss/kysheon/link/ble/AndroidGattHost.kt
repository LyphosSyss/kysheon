package epheis.lyphossyss.kysheon.link.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import kotlinx.coroutines.Job
import java.util.UUID

/**
 * Host role: peripheral with a GATT server and a legacy connectable advertisement.
 *
 * Service [BleUuids.SERVICE] holds RX (write without response, guest → host) and TX (notify, host → guest, with its
 * CCCD 0x2902, which a GATT server must declare and answer itself). One peer only: advertising stops at the first
 * connection, and the session is Connected once that central has enabled TX notifications, so frames can flow both
 * ways. A connected central that does not subscribe within [AndroidBleTimings.hostPeerSetupTimeout] (another app's
 * device, a watch reconnecting…) is ignored and advertising resumes.
 *
 * Connection priority cannot be requested from the peripheral side on Android (`requestConnectionPriority` exists on
 * [BluetoothGatt] only): the guest requests CONNECTION_PRIORITY_HIGH.
 *
 * MissingPermission is suppressed for the whole class on purpose: [start] checks BLUETOOTH_CONNECT and
 * BLUETOOTH_ADVERTISE through [requireReady] before any call, and every call runs inside
 * [AndroidBleLink.runGuarded] (events) or [quietly] (cleanup), which turn a SecurityException — permission revoked
 * mid-session — into `Lost(PermissionDenied)` or ignore it while releasing.
 */
@SuppressLint("MissingPermission")
internal class HostSession(link: AndroidBleLink) : BleSession(link, "host") {

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false
    private var tx: BluetoothGattCharacteristic? = null

    /** Connected central that has not enabled notifications yet. */
    private var candidate: BluetoothDevice? = null
    private var candidateWatchdog: Job? = null

    /** The guest, once it has enabled notifications. Read by onNotificationSent on a binder thread. */
    @Volatile
    private var peer: BluetoothDevice? = null

    override fun peerDevice(): BluetoothDevice? = peer ?: candidate

    override fun start() {
        val adapter = requireReady(AndroidBluetooth.hostPermissions())
        // Null when the device cannot advertise (no peripheral mode) or when the adapter is off.
        advertiser = adapter.bluetoothLeAdvertiser ?: throw LinkFailure(LostReason.Unsupported)
        val manager = link.appContext.getSystemService(BluetoothManager::class.java)
            ?: throw LinkFailure(LostReason.Unsupported)
        val gattServer = manager.openGattServer(link.appContext, serverCallback)
            ?: throw LinkFailure(LostReason.ConnectionFailed)
        server = gattServer
        // addService is asynchronous: advertising starts in onServiceAdded, once the database is complete.
        if (!gattServer.addService(buildService())) throw LinkFailure(LostReason.ConnectionFailed)
        link.after(this, link.timings.advertiseStartTimeout) {
            if (!started.isCompleted) throw LinkFailure(LostReason.ConnectionFailed)
        }
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(UUID.fromString(BleUuids.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            UUID.fromString(BleUuids.RX),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notify = BluetoothGattCharacteristic(
            UUID.fromString(BleUuids.TX),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString(BleUuids.CCCD),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(notify)
        tx = notify
        return service
    }

    private fun startAdvertising() {
        val target = advertiser ?: return
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Flags (3 bytes) + complete 128-bit service UUID list (18 bytes) fit the 31-byte legacy payload;
        // the device name would not.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid.fromString(BleUuids.SERVICE))
            .build()
        target.startAdvertising(settings, data, advertiseCallback)
        advertising = true
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        advertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            link.post(this@HostSession) {
                if (peer == null && candidate == null) link.setState(this@HostSession, LinkState.Advertising)
                started.complete(Unit)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            link.post(this@HostSession) {
                advertising = false
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> started.complete(Unit)
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> throw LinkFailure(LostReason.Unsupported, errorCode)
                    else -> throw LinkFailure(LostReason.ConnectionFailed, errorCode)
                }
            }
        }
    }

    // ---- GATT server events, on the serial dispatcher ----

    private fun onServiceAdded(status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) throw LinkFailure(LostReason.ConnectionFailed, status)
        startAdvertising()
    }

    private fun onConnectionChange(device: BluetoothDevice, status: Int, newState: Int) {
        link.recordPlatformCode(status.takeIf { it != BluetoothGatt.GATT_SUCCESS })
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (peer != null || candidate != null) return // Single peer: anybody else is ignored.
                candidate = device
                stopAdvertising()
                link.setState(this, LinkState.Connecting)
                link.describe(this)
                candidateWatchdog = link.after(this, link.timings.hostPeerSetupTimeout) {
                    if (peer == null && candidate == device) forgetCandidate()
                }
            }
            BluetoothProfile.STATE_DISCONNECTED -> when (device) {
                peer -> throw LinkFailure(LostReason.PeerDisconnected, status)
                candidate -> forgetCandidate()
            }
        }
    }

    private fun forgetCandidate() {
        candidate = null
        candidateWatchdog?.cancel()
        candidateWatchdog = null
        link.setState(this, LinkState.Advertising)
        link.describe(this)
        startAdvertising()
    }

    private fun onCccdWrite(device: BluetoothDevice, requestId: Int, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
        val gattServer = server ?: return
        fun respond(status: Int) {
            if (responseNeeded) gattServer.sendResponse(device, requestId, status, offset, value)
        }
        when {
            offset != 0 -> return respond(BluetoothGatt.GATT_INVALID_OFFSET)
            value == null || value.size != 2 -> return respond(BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH)
        }
        val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        val currentPeer = peer
        when {
            currentPeer == null && candidate != null && device != candidate ->
                respond(BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
            currentPeer == null -> {
                // Answer first: the guest becomes Connected on this response, and only then may notifications flow.
                respond(BluetoothGatt.GATT_SUCCESS)
                if (enable) becomeConnected(device)
            }
            device == currentPeer -> {
                respond(BluetoothGatt.GATT_SUCCESS)
                if (!enable) throw LinkFailure(LostReason.PeerDisconnected)
            }
            else -> respond(BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
        }
    }

    private fun becomeConnected(device: BluetoothDevice) {
        peer = device
        candidate = null
        candidateWatchdog?.cancel()
        candidateWatchdog = null
        stopAdvertising()
        sender = link.newSender(this, ::notifyPeer)
        link.setConnected(this)
    }

    /** One notification; [SerialSender] waits for onNotificationSent before the next one. */
    private fun notifyPeer(frame: ByteArray): WriteStart {
        val gattServer = server ?: return WriteStart.Rejected()
        val device = peer ?: return WriteStart.Rejected()
        val characteristic = tx ?: return WriteStart.Rejected()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = gattServer.notifyCharacteristicChanged(device, characteristic, false, frame)
            if (code == BluetoothStatusCodes.SUCCESS) {
                WriteStart.Started
            } else {
                if (code == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION) {
                    link.post(this) { throw LinkFailure(LostReason.PermissionDenied, code) }
                }
                WriteStart.Rejected(code)
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            val accepted = gattServer.notifyCharacteristicChanged(device, characteristic, false)
            if (accepted) WriteStart.Started else WriteStart.Rejected()
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            link.post(this@HostSession) { onServiceAdded(status) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            link.post(this@HostSession) { onConnectionChange(device, status, newState) }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            device ?: return
            link.post(this@HostSession) { if (device == peer || device == candidate) link.recordMtu(mtu) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            device ?: return
            val frame = value?.copyOf()
            val isRx = characteristic?.uuid == UUID.fromString(BleUuids.RX)
            link.post(this@HostSession) {
                val accepted = isRx && !preparedWrite && offset == 0 && frame != null && device == peer
                if (responseNeeded) {
                    val status = if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    server?.sendResponse(device, requestId, status, offset, null)
                }
                if (accepted) link.deliver(this@HostSession, frame)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            device ?: return
            link.post(this@HostSession) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            device ?: return
            val bytes = value?.copyOf()
            val isOurCccd = descriptor?.uuid == UUID.fromString(BleUuids.CCCD) &&
                descriptor.characteristic?.uuid == UUID.fromString(BleUuids.TX)
            link.post(this@HostSession) {
                when {
                    !isOurCccd || preparedWrite -> if (responseNeeded) {
                        server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                    }
                    else -> onCccdWrite(device, requestId, responseNeeded, offset, bytes)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?,
        ) {
            device ?: return
            link.post(this@HostSession) {
                val value = if (device == peer) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                if (offset > value.size) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                } else {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
                }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
            device ?: return
            // Prepared (long) writes are never used by the guest.
            link.post(this@HostSession) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            // Thread-safe completion signal, no need to go through the event loop.
            if (device != null && device == peer) sender?.confirm(status == BluetoothGatt.GATT_SUCCESS, status)
        }
    }

    override fun releaseRadio() {
        quietly("stopAdvertising") { stopAdvertising() }
        val gattServer = server ?: return
        server = null
        peer?.let { device -> quietly("cancelConnection") { gattServer.cancelConnection(device) } }
        quietly("clearServices") { gattServer.clearServices() }
        quietly("close") { gattServer.close() }
    }
}
