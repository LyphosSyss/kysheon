package epheis.lyphossyss.kysheon.app.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsRepositoryTest {

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putFloat(key: String, value: Float) { values[key] = value }
    }

    @Test
    fun defaultsWhenNothingIsStored() {
        assertEquals(Settings(), SettingsRepository(MemoryStore()).load())
    }

    @Test
    fun roundTrip() {
        val store = MemoryStore()
        val settings = Settings(
            soundEnabled = false, volume = 0.35f, hapticsEnabled = false, hapticsIntensity = 0.4f,
            showDebugButton = false, debugOverlayOnStart = true,
        )
        SettingsRepository(store).save(settings)
        assertEquals(settings, SettingsRepository(store).load())
    }

    @Test
    fun hapticsLevelsMapToTheClosestPreset() {
        assertEquals(HapticsLevel.Light, HapticsLevel.closestTo(0.35f))
        assertEquals(HapticsLevel.Medium, HapticsLevel.closestTo(0.7f))
        assertEquals(HapticsLevel.Strong, HapticsLevel.closestTo(0.95f))
    }

    @Test
    fun volumeIsClamped() {
        val store = MemoryStore()
        store.putFloat("v1.volume", 7f)
        assertEquals(1f, SettingsRepository(store).load().volume)
        SettingsRepository(store).save(Settings(volume = -2f))
        assertEquals(0f, SettingsRepository(store).load().volume)
    }
}
