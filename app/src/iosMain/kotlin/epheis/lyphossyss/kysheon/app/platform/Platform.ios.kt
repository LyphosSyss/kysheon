package epheis.lyphossyss.kysheon.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.ble.BluetoothAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.UIKit.UIDevice

// The iOS BLE link (stream D) is not written yet: the factory reports itself unsupported and the UI says so.

@Composable
actual fun rememberLinkFactory(): LinkFactory = remember {
    object : LinkFactory {
        override val supported: Boolean = false
        override fun create(scope: CoroutineScope): Link? = null
    }
}

@Composable
actual fun rememberBluetoothAccess(): BluetoothAccess = remember {
    object : BluetoothAccess {
        override val availability: StateFlow<BluetoothAvailability> =
            MutableStateFlow(BluetoothAvailability.Unsupported)
        override fun requestPermissions() = Unit
        override fun requestEnable() = Unit
    }
}

actual fun deviceLabel(): String = UIDevice.currentDevice.run { "$model · $systemName $systemVersion" }
