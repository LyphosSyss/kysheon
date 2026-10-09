package epheis.lyphossyss.kysheon.link.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * Bluetooth availability for the UI, from `CBManager.authorization` (class property, iOS 13.1+) and the state of a
 * dedicated `CBCentralManager` created with `CBCentralManagerOptionShowPowerAlertKey = false` (the app explains a
 * powered-off adapter itself instead of the system alert).
 *
 * Creating that manager is what makes iOS show the Bluetooth permission prompt when it was never answered, so call
 * [start] when the user asks for Bluetooth (taps Host or Join), not at launch. Main thread only: the manager reports
 * on the main queue, and [availability] changes there.
 *
 * Before [start] (no manager, so no adapter state), the value only reflects the authorization:
 * - allowed → [BluetoothAvailability.Ready] (optimistic: the power state is unknown until [start]);
 * - denied or restricted → [BluetoothAvailability.Unauthorized];
 * - not determined → [BluetoothAvailability.Unauthorized] as well, since Bluetooth cannot be used yet: check
 *   [isPermissionDetermined] to tell "ask" ([start]) from "refused" ([openAppSettings]).
 *
 * iOS does not let an app turn Bluetooth on. [BluetoothAvailability.Off] is the `poweredOff` state, i.e. Bluetooth
 * switched off in Settings; the Control Center toggle only disconnects accessories and is not expected to produce it
 * (to be confirmed on device: docs/manual-tests/ios-ble.md).
 */
class IosBluetoothAvailability {

    private val mutableAvailability = MutableStateFlow(fromAuthorization())
    val availability: StateFlow<BluetoothAvailability> = mutableAvailability.asStateFlow()

    /** False until the user has answered the permission prompt once (then only Settings can change the answer). */
    val isPermissionDetermined: Boolean
        get() = CBManager.authorization() != CBManagerAuthorizationNotDetermined

    // Strong references: the manager keeps its delegate weak.
    private val delegate = AvailabilityDelegate(this)
    private var manager: CBCentralManager? = null

    /** Idempotent. Shows the permission prompt if it was never answered; then tracks the adapter state. */
    fun start() {
        if (manager != null) return
        manager = CBCentralManager(
            delegate = delegate,
            queue = dispatch_get_main_queue(),
            options = mapOf<Any?, Any>(CBCentralManagerOptionShowPowerAlertKey to false),
        )
    }

    /** Releases the manager; [availability] falls back to the authorization-only value. */
    fun stop() {
        manager?.delegate = null
        manager = null
        mutableAvailability.value = fromAuthorization()
    }

    /**
     * Opens this app's page in Settings (Bluetooth permission switch). Useful when the permission was refused; for a
     * powered-off adapter it is the closest iOS allows: the user goes back to Settings › Bluetooth from there.
     */
    fun openAppSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    internal fun onState(state: Long) {
        mutableAvailability.value = when (state) {
            CBManagerStatePoweredOn -> BluetoothAvailability.Ready
            CBManagerStatePoweredOff -> BluetoothAvailability.Off
            CBManagerStateUnauthorized -> BluetoothAvailability.Unauthorized
            CBManagerStateUnsupported -> BluetoothAvailability.Unsupported
            // unknown (prompt on screen, still starting) or resetting: keep the authorization-based value.
            else -> fromAuthorization()
        }
    }

    private fun fromAuthorization(): BluetoothAvailability =
        if (CBManager.authorization() == CBManagerAuthorizationAllowedAlways) {
            BluetoothAvailability.Ready
        } else {
            BluetoothAvailability.Unauthorized
        }
}

private class AvailabilityDelegate(private val owner: IosBluetoothAvailability) :
    NSObject(), CBCentralManagerDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        // Never let an exception cross into Objective-C: it would abort the app.
        try {
            owner.onState(central.state)
        } catch (_: Throwable) {
            // Keep the previous value.
        }
    }
}
