package epheis.lyphossyss.kysheon.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSUserDefaults

@Composable
actual fun rememberKeyValueStore(): KeyValueStore = remember { UserDefaultsStore(NSUserDefaults.standardUserDefaults) }

/** NSUserDefaults; a missing key is told apart from a stored `false` / `0` through `objectForKey`. */
private class UserDefaultsStore(private val defaults: NSUserDefaults) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean) =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    override fun getFloat(key: String, default: Float) =
        if (defaults.objectForKey(key) == null) default else defaults.floatForKey(key)

    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, key)
    override fun putFloat(key: String, value: Float) = defaults.setFloat(value, key)
}
