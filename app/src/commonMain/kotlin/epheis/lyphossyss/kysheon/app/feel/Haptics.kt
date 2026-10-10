package epheis.lyphossyss.kysheon.app.feel

import androidx.compose.runtime.Composable

/** Short vibrations for game moments. Never throws: a phone without a vibrator simply stays still. */
interface Haptics {
    /** Light tick: the local fighter fires. */
    fun tick()

    /** Medium click: one of our projectiles hit the opponent. */
    fun confirm()

    /** Heavy impact: our fighter was hit. */
    fun impact()
}

/** [intensity] in (0, 1] scales every effect (amplitude where the motor supports it, duration otherwise). */
@Composable
expect fun rememberHaptics(enabled: Boolean, intensity: Float): Haptics

internal object NoHaptics : Haptics {
    override fun tick() = Unit
    override fun confirm() = Unit
    override fun impact() = Unit
}
