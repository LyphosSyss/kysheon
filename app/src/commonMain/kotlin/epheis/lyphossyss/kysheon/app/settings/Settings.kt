package epheis.lyphossyss.kysheon.app.settings

import androidx.compose.runtime.Composable

/** User preferences, persisted across launches. Game data (creatures, progress) is a V2 matter, not stored here. */
data class Settings(
    val soundEnabled: Boolean = true,
    /** Master volume of game sounds, in [0, 1]. */
    val volume: Float = 0.8f,
    val hapticsEnabled: Boolean = true,
    /** Strength of the vibrations, in (0, 1]: see [HapticsLevel]. */
    val hapticsIntensity: Float = HapticsLevel.Medium.intensity,
    /** Show the Debug button during a game. */
    val showDebugButton: Boolean = true,
    /** Open the debug overlay automatically when a game starts. */
    val debugOverlayOnStart: Boolean = false,
)

/** The three vibration strengths offered in the settings. */
enum class HapticsLevel(val intensity: Float) {
    Light(0.4f),
    Medium(0.7f),
    Strong(1f);

    companion object {
        fun closestTo(intensity: Float): HapticsLevel = entries.minBy { kotlin.math.abs(it.intensity - intensity) }
    }
}

/** Minimal key-value storage: SharedPreferences on Android, NSUserDefaults on iOS. */
interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun putBoolean(key: String, value: Boolean)
    fun putFloat(key: String, value: Float)
}

@Composable
expect fun rememberKeyValueStore(): KeyValueStore

/** Reads and writes [Settings]; keys are versioned so that a future format can coexist. */
class SettingsRepository(private val store: KeyValueStore) {

    fun load(): Settings {
        val defaults = Settings()
        return Settings(
            soundEnabled = store.getBoolean(SOUND, defaults.soundEnabled),
            volume = store.getFloat(VOLUME, defaults.volume).coerceIn(0f, 1f),
            hapticsEnabled = store.getBoolean(HAPTICS, defaults.hapticsEnabled),
            hapticsIntensity = store.getFloat(HAPTICS_INTENSITY, defaults.hapticsIntensity).coerceIn(0.1f, 1f),
            showDebugButton = store.getBoolean(DEBUG_BUTTON, defaults.showDebugButton),
            debugOverlayOnStart = store.getBoolean(DEBUG_ON_START, defaults.debugOverlayOnStart),
        )
    }

    fun save(settings: Settings) {
        store.putBoolean(SOUND, settings.soundEnabled)
        store.putFloat(VOLUME, settings.volume.coerceIn(0f, 1f))
        store.putBoolean(HAPTICS, settings.hapticsEnabled)
        store.putFloat(HAPTICS_INTENSITY, settings.hapticsIntensity.coerceIn(0.1f, 1f))
        store.putBoolean(DEBUG_BUTTON, settings.showDebugButton)
        store.putBoolean(DEBUG_ON_START, settings.debugOverlayOnStart)
    }

    private companion object {
        const val SOUND = "v1.sound"
        const val VOLUME = "v1.volume"
        const val HAPTICS = "v1.haptics"
        const val HAPTICS_INTENSITY = "v1.haptics_intensity"
        const val DEBUG_BUTTON = "v1.debug_button"
        const val DEBUG_ON_START = "v1.debug_on_start"
    }
}
