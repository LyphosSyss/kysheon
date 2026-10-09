package epheis.lyphossyss.kysheon.app.platform

import androidx.compose.runtime.Composable
import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.ble.BluetoothAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Creates the platform BLE link. */
interface LinkFactory {
    /** False while this platform has no BLE implementation yet: the UI says so instead of pretending. */
    val supported: Boolean

    fun create(scope: CoroutineScope): Link?
}

/** Bluetooth readiness and the platform flows that fix it (permission prompt, enable request). */
interface BluetoothAccess {
    val availability: StateFlow<BluetoothAvailability>

    /** Asks for the missing runtime permissions, if any. */
    fun requestPermissions()

    /** Asks the user to turn Bluetooth on. */
    fun requestEnable()
}

@Composable
expect fun rememberLinkFactory(): LinkFactory

@Composable
expect fun rememberBluetoothAccess(): BluetoothAccess

/** Device model, shown on the probe screen so that screenshots identify the phone. */
expect fun deviceLabel(): String
