package app.lawnchair.preferences2

import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.launcher3.InvariantDeviceProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class IdpPreference(
    val defaultSelector: InvariantDeviceProfile.GridOption.() -> Int,
    val key: Preferences.Key<Int>,
    private val preferencesDataStore: DataStore<Preferences>,
    val onSet: (Int) -> Unit = {},
) {

    fun get(gridOption: InvariantDeviceProfile.GridOption) = preferencesDataStore.data.map { preferences ->
        val value = preferences[key]
        if (value == null || value == -1) {
            defaultSelector(gridOption)
        } else {
            value
        }
    }

    fun defaultValue(gridOption: InvariantDeviceProfile.GridOption) = defaultSelector(gridOption)

    /**
     * Morrowa (drawer-columns fix): the raw stored value, or null when the user never changed it
     * (or explicitly reset to default, stored as -1). Unlike [get], this needs no [gridOption], so
     * callers can cache it and resolve the grid-dependent default at apply time — see
     * [app.lawnchair.DeviceProfileOverrides].
     */
    fun storedValue() = preferencesDataStore.data.map { preferences ->
        preferences[key]?.takeIf { it != -1 }
    }

    suspend fun set(value: Int, gridOption: InvariantDeviceProfile.GridOption) {
        preferencesDataStore.edit { mutablePreferences ->
            val defaultValue = defaultSelector(gridOption)
            if (value == defaultValue) {
                mutablePreferences.remove(key)
            } else {
                mutablePreferences[key] = value
            }
        }
        onSet(value)
    }
}

fun IdpPreference.firstBlocking(gridOption: InvariantDeviceProfile.GridOption) = runBlocking { get(gridOption = gridOption).first() }

@Composable
fun IdpPreference.state(
    gridOption: InvariantDeviceProfile.GridOption,
    initial: Int? = null,
) = get(gridOption = gridOption).collectAsStateWithLifecycle(initialValue = initial)
