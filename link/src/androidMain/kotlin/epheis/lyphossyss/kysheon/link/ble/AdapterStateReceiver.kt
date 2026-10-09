package epheis.lyphossyss.kysheon.link.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Watches `BluetoothAdapter.ACTION_STATE_CHANGED` for one link session and calls [onTurningOff] as soon as the
 * adapter starts switching off (STATE_TURNING_OFF, or STATE_OFF if the first one was missed).
 *
 * The broadcast is sent by the Bluetooth stack, a privileged app that does not run under the system UID, so on
 * Android 13+ the receiver must be registered with RECEIVER_EXPORTED to get it (a RECEIVER_NOT_EXPORTED receiver
 * only gets broadcasts from the system UID and from this app). The action is a protected broadcast: no third-party
 * app can forge it.
 */
internal class AdapterStateReceiver(
    private val context: Context,
    private val onTurningOff: () -> Unit,
) {
    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onTurningOff()
            }
        }
    }

    /** Main thread or any thread; [onTurningOff] is called on the main thread. */
    fun register() {
        if (registered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    /** Idempotent, never throws. */
    fun unregister() {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }
}
