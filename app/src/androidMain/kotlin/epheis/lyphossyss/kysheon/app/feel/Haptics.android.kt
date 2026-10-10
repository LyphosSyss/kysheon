package epheis.lyphossyss.kysheon.app.feel

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberHaptics(enabled: Boolean): Haptics {
    val context = LocalContext.current.applicationContext
    return remember(context, enabled) {
        if (!enabled) NoHaptics else runCatching { AndroidHaptics(context) }.getOrElse { NoHaptics }
    }
}

/**
 * Predefined effects (API 29+) are tuned by each vendor to its motor; below that, short one-shot pulses.
 * VIBRATE is a normal permission, granted at install.
 */
private class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val tickEffect = effect(predefined = VibrationEffect.EFFECT_TICK, millis = 10, amplitude = 60)
    private val confirmEffect = effect(predefined = VibrationEffect.EFFECT_CLICK, millis = 20, amplitude = 140)
    private val impactEffect = effect(predefined = VibrationEffect.EFFECT_HEAVY_CLICK, millis = 45, amplitude = 255)

    override fun tick() = vibrate(tickEffect)
    override fun confirm() = vibrate(confirmEffect)
    override fun impact() = vibrate(impactEffect)

    private fun effect(predefined: Int, millis: Long, amplitude: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(predefined)
        } else {
            VibrationEffect.createOneShot(millis, amplitude)
        }

    private fun vibrate(effect: VibrationEffect) {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        runCatching { target.vibrate(effect) }
    }
}
