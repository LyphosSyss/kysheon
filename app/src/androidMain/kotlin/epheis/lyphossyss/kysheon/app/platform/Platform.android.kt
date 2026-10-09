package epheis.lyphossyss.kysheon.app.platform

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.ble.AndroidBleLink
import epheis.lyphossyss.kysheon.link.ble.AndroidBluetooth
import epheis.lyphossyss.kysheon.link.ble.BluetoothAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Composable
actual fun rememberLinkFactory(): LinkFactory {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        object : LinkFactory {
            override val supported: Boolean = true
            override fun create(scope: CoroutineScope): Link = AndroidBleLink(context, scope)
        }
    }
}

@Composable
actual fun rememberBluetoothAccess(): BluetoothAccess {
    val context = LocalContext.current
    val access = remember(context) { AndroidBluetoothAccess(context.applicationContext) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access.refresh()
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access.refresh()
    }
    access.launchPermissions = { permissions.launch(AndroidBluetooth.requiredPermissions()) }
    access.launchEnable = {
        try {
            enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: SecurityException) {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    // Bluetooth toggled from the quick settings, or permission granted in the system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, access) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = access.refresh()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            // ACTION_STATE_CHANGED is a protected system broadcast: exporting is safe and guarantees delivery (see KYS-0020).
            ContextCompat.RECEIVER_EXPORTED,
        )
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) access.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            context.unregisterReceiver(receiver)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return access
}

private class AndroidBluetoothAccess(private val context: Context) : BluetoothAccess {
    private val state = MutableStateFlow(AndroidBluetooth.availability(context))
    override val availability: StateFlow<BluetoothAvailability> = state.asStateFlow()

    var launchPermissions: () -> Unit = {}
    var launchEnable: () -> Unit = {}

    fun refresh() {
        state.value = AndroidBluetooth.availability(context)
    }

    override fun requestPermissions() = launchPermissions()

    override fun requestEnable() = launchEnable()
}

actual fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
