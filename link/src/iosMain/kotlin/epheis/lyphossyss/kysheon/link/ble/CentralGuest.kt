package epheis.lyphossyss.kysheon.link.ble

import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * Guest role: GATT client over `CBCentralManager`.
 *
 * Sequence (each step is driven by the delegate callback of the previous one, all on the main queue):
 * 1. wait for `poweredOn` (creating the manager triggers the permission prompt on first use);
 * 2. `scanForPeripheralsWithServices([SERVICE])`; from the first result, collect for [BleTunables.scanWindow]
 *    and keep the strongest RSSI; nothing within [BleTunables.scanTimeout] → `Lost(PeerNotFound)`;
 * 3. `connectPeripheral` (CoreBluetooth never times out a connection attempt by itself, hence
 *    [BleTunables.connectTimeout], which also covers discovery and subscription);
 * 4. `discoverServices([SERVICE])`, `discoverCharacteristics([RX, TX])`, `setNotifyValue(true)` on TX;
 * 5. `didUpdateNotificationStateForCharacteristic` with `isNotifying` → [LinkState.Connected].
 *
 * Writes go to RX as Write Commands (`CBCharacteristicWriteWithoutResponse`), one at a time, only when
 * `canSendWriteWithoutResponse` is true; otherwise the pump waits for `peripheralIsReadyToSendWriteWithoutResponse:`.
 */
internal class CentralGuest(
    sink: LinkSink,
    scope: CoroutineScope,
    tunables: BleTunables,
) : BleSession(sink, scope, tunables) {

    private enum class Phase { WaitingForAdapter, Scanning, Connecting, Discovering, Connected }

    // Strong references: CoreBluetooth keeps its delegates weak, and a discovered CBPeripheral must be retained by
    // the app or the connection attempt is silently cancelled when it is deallocated.
    private val delegate = GuestDelegate(this)
    private var manager: CBCentralManager? = null
    private var candidate: CBPeripheral? = null
    private var candidateRssi = Int.MIN_VALUE
    private var peripheral: CBPeripheral? = null
    private var rx: CBCharacteristic? = null
    private var tx: CBCharacteristic? = null
    private var windowJob: Job? = null
    private var phase = Phase.WaitingForAdapter

    override val isConnected: Boolean get() = phase == Phase.Connected

    override fun start() {
        sink.setState(LinkState.Scanning)
        sink.updateDiagnostics { it.copy(platformInfo = platformInfo("central (guest) · write without response")) }
        // ShowPowerAlert = false: the app explains a powered-off adapter itself (IosBluetoothAvailability).
        manager = CBCentralManager(
            delegate = delegate,
            queue = dispatch_get_main_queue(),
            options = mapOf<Any?, Any>(CBCentralManagerOptionShowPowerAlertKey to false),
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
            CBManagerStatePoweredOn -> if (phase == Phase.WaitingForAdapter) beginScan()
            // The link with the system Bluetooth service dropped: every connection is gone, without callback.
            CBManagerStateResetting -> if (phase != Phase.WaitingForAdapter) {
                fail(if (phase == Phase.Connected) LostReason.PeerDisconnected else LostReason.ConnectionFailed)
            }
            else -> Unit // unknown: an update follows.
        }
    }

    private fun beginScan() {
        val central = manager ?: return
        phase = Phase.Scanning
        central.scanForPeripheralsWithServices(listOf(GattUuids.service), options = null)
        schedule(tunables.scanTimeout) {
            if (phase == Phase.Scanning && candidate == null) fail(LostReason.PeerNotFound)
        }
    }

    fun onDiscovered(found: CBPeripheral, rssi: NSNumber) {
        if (isReleased || phase != Phase.Scanning) return
        val value = rssi.intValue.let { if (it == RSSI_UNAVAILABLE) Int.MIN_VALUE + 1 else it }
        if (candidate == null || value > candidateRssi) {
            candidate = found
            candidateRssi = value
        }
        if (windowJob == null) windowJob = schedule(tunables.scanWindow) { connectToCandidate() }
    }

    private fun connectToCandidate() {
        val central = manager ?: return
        if (phase != Phase.Scanning) return
        central.stopScan()
        val chosen = candidate ?: return fail(LostReason.PeerNotFound)
        candidate = null
        peripheral = chosen
        chosen.delegate = delegate
        phase = Phase.Connecting
        sink.setState(LinkState.Connecting)
        central.connectPeripheral(chosen, options = null)
        schedule(tunables.connectTimeout) {
            if (phase == Phase.Connecting || phase == Phase.Discovering) fail(LostReason.ConnectionFailed)
        }
    }

    fun onConnected(connected: CBPeripheral) {
        if (isReleased || phase != Phase.Connecting || !connected.isSameAs(peripheral)) return
        phase = Phase.Discovering
        connected.discoverServices(listOf(GattUuids.service))
    }

    fun onConnectFailed(failed: CBPeripheral, error: NSError?) {
        if (isReleased || !failed.isSameAs(peripheral)) return
        fail(LostReason.ConnectionFailed, error)
    }

    fun onDisconnected(lost: CBPeripheral, error: NSError?) {
        if (isReleased || !lost.isSameAs(peripheral)) return
        fail(if (phase == Phase.Connected) LostReason.PeerDisconnected else LostReason.ConnectionFailed, error)
    }

    fun onServicesDiscovered(target: CBPeripheral, error: NSError?) {
        if (isReleased || phase != Phase.Discovering || !target.isSameAs(peripheral)) return
        if (error != null) return fail(LostReason.ConnectionFailed, error)
        val service = target.services.orEmpty().filterIsInstance<CBService>().firstOrNull { it.hasUuid(GattUuids.service) }
            ?: return fail(LostReason.ConnectionFailed)
        target.discoverCharacteristics(listOf(GattUuids.rx, GattUuids.tx), forService = service)
    }

    fun onCharacteristicsDiscovered(target: CBPeripheral, service: CBService, error: NSError?) {
        if (isReleased || phase != Phase.Discovering || !target.isSameAs(peripheral)) return
        if (!service.hasUuid(GattUuids.service)) return
        if (error != null) return fail(LostReason.ConnectionFailed, error)
        val characteristics = service.characteristics.orEmpty().filterIsInstance<CBCharacteristic>()
        val foundRx = characteristics.firstOrNull { it.hasUuid(GattUuids.rx) }
        val foundTx = characteristics.firstOrNull { it.hasUuid(GattUuids.tx) }
        if (foundRx == null || foundTx == null ||
            foundRx.properties and CBCharacteristicPropertyWriteWithoutResponse == 0uL ||
            foundTx.properties and CBCharacteristicPropertyNotify == 0uL
        ) {
            return fail(LostReason.ConnectionFailed)
        }
        rx = foundRx
        tx = foundTx
        target.setNotifyValue(true, forCharacteristic = foundTx)
    }

    fun onNotificationState(target: CBPeripheral, characteristic: CBCharacteristic, error: NSError?) {
        if (isReleased || !target.isSameAs(peripheral) || !characteristic.hasUuid(GattUuids.tx)) return
        if (error != null) return fail(LostReason.ConnectionFailed, error)
        if (phase == Phase.Discovering && characteristic.isNotifying) becomeConnected(target)
    }

    private fun becomeConnected(target: CBPeripheral) {
        phase = Phase.Connected
        publishPayload(target.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt())
        startWritePump()
        sink.setState(LinkState.Connected)
        outcome.complete(true)
    }

    fun onValue(target: CBPeripheral, characteristic: CBCharacteristic, error: NSError?) {
        if (isReleased || phase != Phase.Connected || !target.isSameAs(peripheral)) return
        if (!characteristic.hasUuid(GattUuids.tx)) return
        if (error != null) return noteError(error)
        characteristic.value?.let { sink.deliver(it.toByteArray()) }
    }

    fun onReadyToWrite(target: CBPeripheral) {
        if (isReleased || !target.isSameAs(peripheral)) return
        writeReady.trySend(Unit)
    }

    /** The host removed our service (e.g. an iOS host called `close()`): the only early signal we get. */
    fun onServicesInvalidated(target: CBPeripheral, invalidated: List<*>) {
        if (isReleased || !target.isSameAs(peripheral)) return
        if (invalidated.filterIsInstance<CBService>().any { it.hasUuid(GattUuids.service) }) {
            fail(if (phase == Phase.Connected) LostReason.PeerDisconnected else LostReason.ConnectionFailed)
        }
    }

    override suspend fun writeFrame(frame: ByteArray): Boolean {
        val target = peripheral ?: return false
        val characteristic = rx ?: return false
        while (!target.canSendWriteWithoutResponse) {
            writeReady.receive()
            if (isReleased) return false
        }
        // Read on every write: iOS may finish the MTU exchange after the subscription.
        val payload = target.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt()
        publishPayload(payload)
        if (frame.size > payload) return false
        target.writeValue(frame.toNSData(), forCharacteristic = characteristic, type = CBCharacteristicWriteWithoutResponse)
        return true
    }

    override fun releaseRadio() {
        val central = manager
        val target = peripheral
        // Calls other than reading state are "API misuse" unless poweredOn; when off, the radio is already down.
        if (central != null && central.state == CBManagerStatePoweredOn) {
            if (central.isScanning) central.stopScan()
            if (target != null) central.cancelPeripheralConnection(target)
        }
        target?.delegate = null
        central?.delegate = null
        manager = null
        peripheral = null
        candidate = null
        rx = null
        tx = null
    }
}

/**
 * Receives both `CBCentralManagerDelegate` and `CBPeripheralDelegate` callbacks on the main queue and forwards them
 * to [guest]. An NSObject subclass is required for a Kotlin class to implement an Objective-C protocol.
 *
 * Why `@ObjCSignatureOverride`: Kotlin sees an Objective-C method as a function named after the first part of its
 * selector, and the other parts become parameter names, which do not count in a Kotlin signature. Hence
 * `peripheral:didUpdateValueForCharacteristic:error:` and `peripheral:didWriteValueForCharacteristic:error:` are both
 * `peripheral(CBPeripheral, CBCharacteristic, NSError?)`, and overriding one would be a conflicting overload. The
 * annotation (Kotlin 2.0+) tells the compiler that such overrides are told apart by their Objective-C selector,
 * which it derives from the parameter names: these must therefore match the protocol exactly.
 */
internal class GuestDelegate(private val guest: CentralGuest) :
    NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) = guest.guarded {
        guest.onAdapterState(central.state)
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber,
    ) = guest.guarded { guest.onDiscovered(didDiscoverPeripheral, RSSI) }

    // Shares its Kotlin signature with centralManager:didUpdateANCSAuthorizationForPeripheral:.
    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) =
        guest.guarded { guest.onConnected(didConnectPeripheral) }

    // The next two share (CBCentralManager, CBPeripheral, NSError?).
    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) =
        guest.guarded { guest.onConnectFailed(didFailToConnectPeripheral, error) }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) =
        guest.guarded { guest.onDisconnected(didDisconnectPeripheral, error) }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) =
        guest.guarded { guest.onServicesDiscovered(peripheral, didDiscoverServices) }

    // Shares (CBPeripheral, CBService, NSError?) with peripheral:didDiscoverIncludedServicesForService:error:.
    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) =
        guest.guarded { guest.onCharacteristicsDiscovered(peripheral, didDiscoverCharacteristicsForService, error) }

    // The next two share (CBPeripheral, CBCharacteristic, NSError?), also used by didWriteValueForCharacteristic
    // and didDiscoverDescriptorsForCharacteristic (not implemented: Write Commands get no callback).
    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateNotificationStateForCharacteristic: CBCharacteristic,
        error: NSError?,
    ) = guest.guarded { guest.onNotificationState(peripheral, didUpdateNotificationStateForCharacteristic, error) }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) =
        guest.guarded { guest.onValue(peripheral, didUpdateValueForCharacteristic, error) }

    override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) =
        guest.guarded { guest.onReadyToWrite(peripheral) }

    override fun peripheral(peripheral: CBPeripheral, didModifyServices: List<*>) =
        guest.guarded { guest.onServicesInvalidated(peripheral, didModifyServices) }
}
