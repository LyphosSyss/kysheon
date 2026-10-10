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
actual fun rememberHaptics(enabled: Boolean, intensity: Float): Haptics {
    val context = LocalContext.current.applicationContext
    return remember(context, enabled, intensity) {
        if (!enabled) NoHaptics else runCatching { AndroidHaptics(context, intensity.coerceIn(0.1f, 1f)) }.getOrElse { NoHaptics }
    }
}

/**
 * With amplitude control, one-shot pulses scaled by [intensity]; without it (amplitude fixed by the motor), the
 * vendor-tuned predefined effects at full strength and shorter pulses at lower settings.
 * VIBRATE is a normal permission, granted at install.
 */
private class AndroidHaptics(context: Context, private val intensity: Float) : Haptics {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true
    private val tickEffect = effect(predefined = VibrationEffect.EFFECT_TICK, millis = 12, amplitude = 90)
    private val confirmEffect = effect(predefined = VibrationEffect.EFFECT_CLICK, millis = 22, amplitude = 170)
    private val impactEffect = effect(predefined = VibrationEffect.EFFECT_HEAVY_CLICK, millis = 45, amplitude = 255)

    override fun tick() = vibrate(tickEffect)
    override fun confirm() = vibrate(confirmEffect)
    override fun impact() = vibrate(impactEffect)

    private fun effect(predefined: Int, millis: Long, amplitude: Int): VibrationEffect = when {
        amplitudeControl ->
            VibrationEffect.createOneShot(millis, (amplitude * intensity).toInt().coerceIn(1, 255))
        intensity >= 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            VibrationEffect.createPredefined(predefined)
        else ->
            VibrationEffect.createOneShot((millis * intensity).toLong().coerceAtLeast(5), VibrationEffect.DEFAULT_AMPLITUDE)
    }

    private fun vibrate(effect: VibrationEffect) {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        runCatching { target.vibrate(effect) }
    }
}
