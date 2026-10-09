package epheis.lyphossyss.kysheon.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.ble.BluetoothAvailability
import epheis.lyphossyss.kysheon.link.ble.IosBleLink
import epheis.lyphossyss.kysheon.link.ble.IosBluetoothAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import platform.UIKit.UIDevice

@Composable
actual fun rememberLinkFactory(): LinkFactory = remember {
    object : LinkFactory {
        override val supported: Boolean = true
        override fun create(scope: CoroutineScope): Link = IosBleLink(scope)
    }
}

@Composable
actual fun rememberBluetoothAccess(): BluetoothAccess {
    val access = remember { IosBluetoothAccess(IosBluetoothAvailability()) }
    DisposableEffect(access) {
        // Once the user has answered the permission prompt, watching the adapter shows no prompt: start at once.
        if (access.source.isPermissionDetermined) access.source.start()
        onDispose { access.source.stop() }
    }
    return access
}

private class IosBluetoothAccess(val source: IosBluetoothAvailability) : BluetoothAccess {
    override val availability: StateFlow<BluetoothAvailability> = source.availability

    /** First time: creating the manager shows the system prompt. Afterwards only the Settings app can change it. */
    override fun requestPermissions() {
        if (source.isPermissionDetermined) source.openAppSettings() else source.start()
    }

    /** iOS does not let apps turn Bluetooth on: send the user to Settings. */
    override fun requestEnable() = source.openAppSettings()
}

actual fun deviceLabel(): String = UIDevice.currentDevice.run { "$model · $systemName $systemVersion" }
