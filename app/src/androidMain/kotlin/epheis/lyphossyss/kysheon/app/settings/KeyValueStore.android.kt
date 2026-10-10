package epheis.lyphossyss.kysheon.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberKeyValueStore(): KeyValueStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { PreferencesStore(context.getSharedPreferences("kysheon_settings", Context.MODE_PRIVATE)) }
}

/** SharedPreferences; `apply()` writes asynchronously, which is fine for preferences. */
private class PreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean) = preferences.getBoolean(key, default)
    override fun getFloat(key: String, default: Float) = preferences.getFloat(key, default)
    override fun putBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
    override fun putFloat(key: String, value: Float) = preferences.edit().putFloat(key, value).apply()
}
