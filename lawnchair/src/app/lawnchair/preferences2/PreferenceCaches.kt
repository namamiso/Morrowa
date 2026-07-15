package app.lawnchair.preferences2

import android.content.Context
import app.lawnchair.util.MainThreadInitializedObject
import com.patrykmichalik.opto.domain.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Morrowa 残項目 B-1 (docs/Morrowa_残項目一覧.md): non-blocking cached snapshots of preferences
 * that are read from synchronous, possibly hot paths (folder rendering, drawer drawing, QSB
 * setup, search-algorithm selection, window transitions).
 *
 * Background: the ANR fix `bb6f9e07fb` replaced `firstBlocking()` at these call sites with
 * `.defaultValue`, which silently pinned the affected settings to their defaults forever (the
 * same regression class as the drawer-columns bug fixed in `c5460eb956`). This class is the
 * missing middle ground: each value starts at the default, is corrected asynchronously as soon
 * as the DataStore emits (warmed from [app.lawnchair.LawnchairApp.onCreate], typically well
 * before the first UI inflate), and tracks later changes — with zero main-thread blocking.
 */
class PreferenceCaches private constructor(context: Context) {

    private val prefs2 = PreferenceManager2.getInstance(context)
    private val scope = MainScope()

    class Cached<C> internal constructor(preference: Preference<C, *, *>, scope: CoroutineScope) {
        @Volatile
        var value: C = preference.defaultValue
            private set

        init {
            preference.get().onEach { value = it }.launchIn(scope)
        }
    }

    private fun <C> cache(preference: Preference<C, *, *>) = Cached(preference, scope)

    val iconShape = cache(prefs2.iconShape)
    val folderShape = cache(prefs2.folderShape)
    val folderPreviewBackgroundOpacity = cache(prefs2.folderPreviewBackgroundOpacity)
    val folderBackgroundOpacity = cache(prefs2.folderBackgroundOpacity)
    val appDrawerBackgroundColor = cache(prefs2.appDrawerBackgroundColor)
    val workProfileTabBackgroundColor = cache(prefs2.workProfileTabBackgroundColor)
    val matchHotseatQsbStyle = cache(prefs2.matchHotseatQsbStyle)
    val hideAppDrawerSearchBar = cache(prefs2.hideAppDrawerSearchBar)
    val hotseatQsbProvider = cache(prefs2.hotseatQsbProvider)
    val searchAlgorithm = cache(prefs2.searchAlgorithm)

    companion object {
        @JvmField
        val INSTANCE = MainThreadInitializedObject { context -> PreferenceCaches(context) }
    }
}
