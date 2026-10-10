package epheis.lyphossyss.kysheon.app.feel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

@Composable
actual fun rememberHaptics(enabled: Boolean, intensity: Float): Haptics = remember(enabled, intensity) {
    if (enabled) IosHaptics(intensity.coerceIn(0.1f, 1f).toDouble()) else NoHaptics
}

/** Taptic Engine through UIKit feedback generators; prepared up front to cut the first-use latency. Main thread only. */
private class IosHaptics(private val intensity: Double) : Haptics {
    private val light = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).also { it.prepare() }
    private val medium = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).also { it.prepare() }
    private val heavy = UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy).also { it.prepare() }

    override fun tick() = fire(light)
    override fun confirm() = fire(medium)
    override fun impact() = fire(heavy)

    private fun fire(generator: UIImpactFeedbackGenerator) {
        generator.impactOccurredWithIntensity(intensity)
        generator.prepare()
    }
}
