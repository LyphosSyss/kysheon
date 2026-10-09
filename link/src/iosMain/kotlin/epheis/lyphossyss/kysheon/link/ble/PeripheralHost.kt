package epheis.lyphossyss.kysheon.link.ble

import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerConnectionLatencyLow
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * Host role: GATT server over `CBPeripheralManager`.
 *
 * Sequence: wait for `poweredOn`, add a primary service with RX (write without response, writeable) and TX (notify,
 * dynamic value, readable), then advertise the service UUID only (`CBAdvertisementDataServiceUUIDsKey`).
 * [LinkState.Advertising] once `peripheralManagerDidStartAdvertising` reports success (`host()` returns then).
 * [LinkState.Connected] when a central subscribes to TX: advertising stops, only that central is served.
 *
 * Limitation, by design of CoreBluetooth: a peripheral manager gets **no disconnection callback**. If the central
 * walks away, crashes, or loses Bluetooth, nothing is reported here (`didUnsubscribe` only fires when the central
 * unsubscribes cleanly, and iOS sometimes reports it on disconnection, without guarantee). The session heartbeat
 * (silence → pause → abort) is the only reliable detector of a vanished guest.
 */
internal class PeripheralHost(
    sink: LinkSink,
    scope: CoroutineScope,
    tunables: BleTunables,
) : BleSession(sink, scope, tunables) {

    private enum class Phase { WaitingForAdapter, AddingService, StartingAdvertising, Advertising, Connected }

    private val delegate = HostDelegate(this)
    private var manager: CBPeripheralManager? = null
    private var txCharacteristic: CBMutableCharacteristic? = null
    private var subscriber: CBCentral? = null
    private var phase = Phase.WaitingForAdapter

    override val isConnected: Boolean get() = phase == Phase.Connected

    override fun start() {
        sink.updateDiagnostics { it.copy(platformInfo = platformInfo("peripheral (host) · notify")) }
        manager = CBPeripheralManager(
            delegate = delegate,
            queue = dispatch_get_main_queue(),
            options = mapOf<Any?, Any>(CBPeripheralManagerOptionShowPowerAlertKey to false),
        )
        armAdapterTimer { phase == Phase.WaitingForAdapter }
    }

    fun onAdapterState(state: Long) {
        if (isReleased) return
        adapterFailure(state)?.let { reason ->
            fail(reason)
            return
        }
        when (state) {
            CBManagerStatePoweredOn -> if (phase == Phase.WaitingForAdapter) addService()
            // Services and subscriptions are wiped by a reset of the system Bluetooth service.
            CBManagerStateResetting -> if (phase != Phase.WaitingForAdapter) {
                fail(if (phase == Phase.Connected) LostReason.PeerDisconnected else LostReason.ConnectionFailed)
            }
            else -> Unit
        }
    }

    private fun addService() {
        val peripheralManager = manager ?: return
        val rx = CBMutableCharacteristic(
            type = GattUuids.rx,
            properties = CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        // A nil value makes the characteristic dynamic: required for notify, its value is pushed by updateValue.
        val tx = CBMutableCharacteristic(
            type = GattUuids.tx,
            properties = CBCharacteristicPropertyNotify,
            value = null,
            permissions = CBAttributePermissionsReadable,
        )
        val service = CBMutableService(type = GattUuids.service, primary = true)
        service.setCharacteristics(listOf(rx, tx))
        txCharacteristic = tx
        phase = Phase.AddingService
        peripheralManager.removeAllServices()
        peripheralManager.addService(service)
        schedule(tunables.adapterTimeout) {
            if (phase == Phase.AddingService || phase == Phase.StartingAdvertising) fail(LostReason.ConnectionFailed)
        }
    }

    fun onServiceAdded(service: CBService, error: NSError?) {
        if (isReleased || phase != Phase.AddingService || !service.hasUuid(GattUuids.service)) return
        if (error != null) return fail(LostReason.ConnectionFailed, error)
        phase = Phase.StartingAdvertising
        // Service UUID only: no local name, so nothing identifies the device beyond the app's own UUID.
        manager?.startAdvertising(mapOf<Any?, Any>(CBAdvertisementDataServiceUUIDsKey to listOf(GattUuids.service)))
    }

    fun onAdvertisingStarted(error: NSError?) {
        if (isReleased || phase != Phase.StartingAdvertising) return
        if (error != null) return fail(LostReason.ConnectionFailed, error)
        phase = Phase.Advertising
        sink.setState(LinkState.Advertising)
        outcome.complete(true)
    }

    fun onSubscribed(central: CBCentral, characteristic: CBCharacteristic) {
        if (isReleased || phase != Phase.Advertising || !characteristic.hasUuid(GattUuids.tx)) return
        val peripheralManager = manager ?: return
        peripheralManager.stopAdvertising()
        subscriber = central
        // A hint only: iOS may ignore it, and the central (Android or iOS) has the final say on the interval.
        peripheralManager.setDesiredConnectionLatency(CBPeripheralManagerConnectionLatencyLow, forCentral = central)
        publishPayload(central.maximumUpdateValueLength.toInt())
        phase = Phase.Connected
        startWritePump()
        sink.setState(LinkState.Connected)
    }

    fun onUnsubscribed(central: CBCentral, characteristic: CBCharacteristic) {
        if (isReleased || !central.isSameAs(subscriber) || !characteristic.hasUuid(GattUuids.tx)) return
        fail(LostReason.PeerDisconnected)
    }

    /**
     * RX only declares "write without response", so every accepted request is an ATT Write Command: the central
     * expects no answer, and `CBATTRequest` exposes no flag saying otherwise. Hence no `respondToRequest` here.
     * Frames arriving before the subscription (not Connected yet) are dropped, as `MemoryLink` does.
     */
    fun onWriteRequests(requests: List<*>) {
        if (isReleased || phase != Phase.Connected) return
        for (request in requests) {
            if (request !is CBATTRequest) continue
            if (!request.central.isSameAs(subscriber) || !request.characteristic.hasUuid(GattUuids.rx)) continue
            if (request.offset != 0uL) continue
            request.value?.let { sink.deliver(it.toByteArray()) }
        }
    }

    fun onReadyToUpdate() {
        if (isReleased) return
        writeReady.trySend(Unit)
    }

    override suspend fun writeFrame(frame: ByteArray): Boolean {
        val peripheralManager = manager ?: return false
        val characteristic = txCharacteristic ?: return false
        val central = subscriber ?: return false
        val payload = central.maximumUpdateValueLength.toInt()
        publishPayload(payload)
        if (frame.size > payload) return false
        val data = frame.toNSData()
        // false = the transmit queue is full: retry the same frame once iOS says there is room again.
        while (!peripheralManager.updateValue(data, forCharacteristic = characteristic, onSubscribedCentrals = listOf(central))) {
            writeReady.receive()
            if (isReleased) return false
        }
        return true
    }

    override fun releaseRadio() {
        val peripheralManager = manager
        if (peripheralManager != null && peripheralManager.state == CBManagerStatePoweredOn) {
            if (peripheralManager.isAdvertising) peripheralManager.stopAdvertising()
            // A peripheral cannot drop a central's connection: removing the service is the strongest signal we can
            // send (an iOS or Android guest sees its service invalidated, see CentralGuest.onServicesInvalidated).
            peripheralManager.removeAllServices()
        }
        peripheralManager?.delegate = null
        manager = null
        txCharacteristic = null
        subscriber = null
    }
}

/** Forwards `CBPeripheralManagerDelegate` callbacks (main queue) to [host]. See GuestDelegate for the annotation. */
internal class HostDelegate(private val host: PeripheralHost) : NSObject(), CBPeripheralManagerDelegateProtocol {

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) = host.guarded {
        host.onAdapterState(peripheral.state)
    }

    override fun peripheralManager(peripheral: CBPeripheralManager, didAddService: CBService, error: NSError?) =
        host.guarded { host.onServiceAdded(didAddService, error) }

    override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) =
        host.guarded { host.onAdvertisingStarted(error) }

    // The next two share (CBPeripheralManager, CBCentral, CBCharacteristic): only the selector tells them apart.
    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic,
    ) = host.guarded { host.onSubscribed(central, didSubscribeToCharacteristic) }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) = host.guarded { host.onUnsubscribed(central, didUnsubscribeFromCharacteristic) }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) =
        host.guarded { host.onWriteRequests(didReceiveWriteRequests) }

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) =
        host.guarded { host.onReadyToUpdate() }
}
