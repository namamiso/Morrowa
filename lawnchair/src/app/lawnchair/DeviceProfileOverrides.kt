package app.lawnchair

import android.content.Context
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.ReloadHelper
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.InvariantDeviceProfile.INDEX_DEFAULT
import com.android.launcher3.InvariantDeviceProfile.INDEX_LANDSCAPE
import com.android.launcher3.InvariantDeviceProfile.INDEX_TWO_PANEL_LANDSCAPE
import com.android.launcher3.InvariantDeviceProfile.INDEX_TWO_PANEL_PORTRAIT
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import javax.inject.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@LauncherAppSingleton
class DeviceProfileOverrides @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {
    private val prefs = PreferenceManager.getInstance(context)
    private val preferenceManager2 = PreferenceManager2.getInstance(context)
    private val reloadHelper = ReloadHelper(context)
    private val scope = MainScope()

    // Morrowa fix (bb6f9e07fb regression): that ANR fix replaced the Options constructor's
    // firstBlocking reads with defaultValue, but never added the promised async follow-up — so
    // every IDP-level setting (drawer/folder columns, icon size factors, label settings,
    // taskbar-on-phone) was pinned to its default forever. This cache is the missing half:
    // startup still applies instantly (defaults on the very first initGrid), the real stored
    // values stream in here without ever blocking the main thread, and the first/any emission
    // that changes the effective values triggers one grid reload.
    @Volatile
    private var stored: StoredOverrides? = null

    init {
        val idpValues = combine(
            preferenceManager2.drawerColumns.storedValue(),
            preferenceManager2.folderColumns.storedValue(),
        ) { drawerColumns, folderColumns -> drawerColumns to folderColumns }
        val sizeValues = combine(
            preferenceManager2.homeIconSizeFactor.get(),
            preferenceManager2.drawerIconSizeFactor.get(),
            preferenceManager2.showIconLabelsInDrawer.get(),
            preferenceManager2.drawerIconLabelSizeFactor.get(),
            preferenceManager2.enableTaskbarOnPhone.get(),
        ) { iconSize, drawerIconSize, showDrawerLabels, drawerLabelSize, taskbar ->
            SizeValues(iconSize, drawerIconSize, showDrawerLabels, drawerLabelSize, taskbar)
        }
        val textValues = combine(
            preferenceManager2.showIconLabelsOnHomeScreen.get(),
            preferenceManager2.homeIconLabelSizeFactor.get(),
            preferenceManager2.showIconLabelsOnHomeScreenFolder.get(),
            preferenceManager2.homeIconLabelFolderSizeFactor.get(),
        ) { showHomeLabels, homeLabelSize, showFolderLabels, folderLabelSize ->
            TextValues(showHomeLabels, homeLabelSize, showFolderLabels, folderLabelSize)
        }
        combine(idpValues, sizeValues, textValues) { (drawerColumns, folderColumns), size, text ->
            StoredOverrides(
                drawerColumns = drawerColumns,
                folderColumns = folderColumns,
                sizes = size,
                texts = text,
            )
        }
            .distinctUntilChanged()
            .onEach { values ->
                val previous = stored
                stored = values
                // First emission: reload only if the stored values actually differ from the
                // defaults the initial initGrid already applied. Later emissions: any change
                // reloads. (Settings-screen changes also fire their own onSet reload; the
                // distinctUntilChanged keeps this from ping-ponging.)
                val effectiveChange = if (previous == null) !values.matchesDefaults() else previous != values
                if (effectiveChange) {
                    reloadHelper.reloadGrid()
                }
            }
            .launchIn(scope)
    }

    private fun StoredOverrides.matchesDefaults(): Boolean = drawerColumns == null &&
        folderColumns == null &&
        sizes.iconSizeFactor == preferenceManager2.homeIconSizeFactor.defaultValue &&
        sizes.drawerIconSizeFactor == preferenceManager2.drawerIconSizeFactor.defaultValue &&
        sizes.showIconLabelsInDrawer == preferenceManager2.showIconLabelsInDrawer.defaultValue &&
        sizes.drawerIconLabelSizeFactor == preferenceManager2.drawerIconLabelSizeFactor.defaultValue &&
        sizes.enableTaskbarOnPhone == preferenceManager2.enableTaskbarOnPhone.defaultValue &&
        texts.showIconLabelsOnHomeScreen == preferenceManager2.showIconLabelsOnHomeScreen.defaultValue &&
        texts.homeIconLabelSizeFactor == preferenceManager2.homeIconLabelSizeFactor.defaultValue &&
        texts.showIconLabelsOnHomeScreenFolder == preferenceManager2.showIconLabelsOnHomeScreenFolder.defaultValue &&
        texts.homeIconLabelFolderSizeFactor == preferenceManager2.homeIconLabelFolderSizeFactor.defaultValue

    private val predefinedGrids = InvariantDeviceProfile.parseAllGridOptions(context)
        .map { option ->
            val gridInfo = DBGridInfo(
                numHotseatColumns = option.numHotseatIcons,
                numRows = option.numRows,
                numColumns = option.numColumns,
            )
            gridInfo to option.name
        }

    fun getGridInfo() = DBGridInfo(prefs)

    fun getGridInfo(gridName: String) = predefinedGrids
        .first { it.second == gridName }
        .first

    fun getGridName(gridInfo: DBGridInfo): String {
        val match = predefinedGrids
            .firstOrNull { it.first.numRows >= gridInfo.numRows && it.first.numColumns >= gridInfo.numColumns }
            ?: predefinedGrids.last()
        return match.second
    }

    fun getCurrentGridName() = getGridName(getGridInfo())

    fun setCurrentGrid(gridName: String) {
        val gridInfo = getGridInfo(gridName)
        prefs.workspaceRows.set(gridInfo.numRows)
        prefs.workspaceColumns.set(gridInfo.numColumns)
        prefs.hotseatColumns.set(gridInfo.numHotseatColumns)
    }

    fun getOverrides(defaultGrid: InvariantDeviceProfile.GridOption) = Options(
        prefs = prefs,
        prefs2 = preferenceManager2,
        defaultGrid = defaultGrid,
        stored = stored,
    )

    fun getTextFactors() = TextFactors(preferenceManager2, stored)

    override fun close() {
        scope.cancel()
    }

    /** The user's stored setting values (null members = user kept the grid default). */
    data class StoredOverrides(
        val drawerColumns: Int?,
        val folderColumns: Int?,
        val sizes: SizeValues,
        val texts: TextValues,
    )

    data class SizeValues(
        val iconSizeFactor: Float,
        val drawerIconSizeFactor: Float,
        val showIconLabelsInDrawer: Boolean,
        val drawerIconLabelSizeFactor: Float,
        val enableTaskbarOnPhone: Boolean,
    )

    data class TextValues(
        val showIconLabelsOnHomeScreen: Boolean,
        val homeIconLabelSizeFactor: Float,
        val showIconLabelsOnHomeScreenFolder: Boolean,
        val homeIconLabelFolderSizeFactor: Float,
    )

    data class DBGridInfo(
        val numHotseatColumns: Int,
        val numRows: Int,
        val numColumns: Int,
    ) {
        val dbFile get() = "launcher_${numRows}_${numColumns}_$numHotseatColumns.db"

        constructor(prefs: PreferenceManager) : this(
            numHotseatColumns = prefs.hotseatColumns.get(),
            numRows = prefs.workspaceRows.get(),
            numColumns = prefs.workspaceColumns.get(),
        )
    }

    data class Options(
        val numAllAppsColumns: Int,
        val numFolderRows: Int,
        val numFolderColumns: Int,

        val iconSizeFactor: Float,
        val allAppsIconSizeFactor: Float,
        val allAppsIconTextSizeFactor: Float,

        val enableTaskbarOnPhone: Boolean,
    ) {
        // Morrowa fix (bb6f9e07fb regression): prefer the async-cached stored values; fall back to
        // the defaults only until the cache's first emission (a fraction of a second at startup —
        // DeviceProfileOverrides reloads the grid once if they turn out to differ).
        constructor(
            prefs: PreferenceManager,
            prefs2: PreferenceManager2,
            defaultGrid: InvariantDeviceProfile.GridOption,
            stored: StoredOverrides?,
        ) : this(
            numAllAppsColumns = stored?.drawerColumns ?: prefs2.drawerColumns.defaultValue(defaultGrid),
            numFolderRows = prefs.folderRows.get(defaultGrid),
            numFolderColumns = stored?.folderColumns ?: prefs2.folderColumns.defaultValue(defaultGrid),

            iconSizeFactor = stored?.sizes?.iconSizeFactor
                ?: prefs2.homeIconSizeFactor.defaultValue,
            allAppsIconSizeFactor = stored?.sizes?.drawerIconSizeFactor
                ?: prefs2.drawerIconSizeFactor.defaultValue,
            allAppsIconTextSizeFactor =
            if (stored?.sizes?.showIconLabelsInDrawer ?: prefs2.showIconLabelsInDrawer.defaultValue) {
                stored?.sizes?.drawerIconLabelSizeFactor ?: prefs2.drawerIconLabelSizeFactor.defaultValue
            } else {
                0f
            },

            enableTaskbarOnPhone = stored?.sizes?.enableTaskbarOnPhone
                ?: prefs2.enableTaskbarOnPhone.defaultValue,
        )

        fun applyUi(idp: InvariantDeviceProfile) {
            // apply grid size
            idp.numAllAppsColumns = numAllAppsColumns
            idp.numDatabaseAllAppsColumns = numAllAppsColumns
            idp.numFolderRows[INDEX_DEFAULT] = numFolderRows
            idp.numFolderColumns[INDEX_DEFAULT] = numFolderColumns

            // apply icon and text size
            idp.iconSize[INDEX_DEFAULT] *= iconSizeFactor
            idp.iconSize[INDEX_LANDSCAPE] *= iconSizeFactor
            idp.iconSize[INDEX_TWO_PANEL_PORTRAIT] *= iconSizeFactor
            idp.iconSize[INDEX_TWO_PANEL_LANDSCAPE] *= iconSizeFactor

            idp.allAppsIconSize[INDEX_DEFAULT] *= allAppsIconSizeFactor
            idp.allAppsIconSize[INDEX_LANDSCAPE] *= allAppsIconSizeFactor
            idp.allAppsIconSize[INDEX_TWO_PANEL_PORTRAIT] *= allAppsIconSizeFactor
            idp.allAppsIconSize[INDEX_TWO_PANEL_LANDSCAPE] *= allAppsIconSizeFactor

            idp.allAppsIconTextSize[INDEX_DEFAULT] *= allAppsIconTextSizeFactor
            idp.allAppsIconTextSize[INDEX_LANDSCAPE] *= allAppsIconTextSizeFactor
            idp.allAppsIconTextSize[INDEX_TWO_PANEL_PORTRAIT] *= allAppsIconTextSizeFactor
            idp.allAppsIconTextSize[INDEX_TWO_PANEL_LANDSCAPE] *= allAppsIconTextSizeFactor
        }
    }

    data class TextFactors(
        val iconTextSizeFactor: Float,
        val allAppsIconTextSizeFactor: Float,
        val iconFolderTextSizeFactor: Float,
    ) {
        // Morrowa fix (bb6f9e07fb regression): same cached-values treatment as Options.
        constructor(
            prefs2: PreferenceManager2,
            stored: StoredOverrides?,
        ) : this(
            enableIconText = stored?.texts?.showIconLabelsOnHomeScreen
                ?: prefs2.showIconLabelsOnHomeScreen.defaultValue,
            iconTextSizeFactor = stored?.texts?.homeIconLabelSizeFactor
                ?: prefs2.homeIconLabelSizeFactor.defaultValue,
            enableIconTextFolder = stored?.texts?.showIconLabelsOnHomeScreenFolder
                ?: prefs2.showIconLabelsOnHomeScreenFolder.defaultValue,
            iconFolderTextSizeFactor = stored?.texts?.homeIconLabelFolderSizeFactor
                ?: prefs2.homeIconLabelFolderSizeFactor.defaultValue,
            enableAllAppsIconText = stored?.sizes?.showIconLabelsInDrawer
                ?: prefs2.showIconLabelsInDrawer.defaultValue,
            allAppsIconTextSizeFactor = stored?.sizes?.drawerIconLabelSizeFactor
                ?: prefs2.drawerIconLabelSizeFactor.defaultValue,
        )

        constructor(
            enableIconText: Boolean,
            iconTextSizeFactor: Float,
            enableIconTextFolder: Boolean,
            iconFolderTextSizeFactor: Float,
            enableAllAppsIconText: Boolean,
            allAppsIconTextSizeFactor: Float,
        ) : this(
            iconTextSizeFactor = if (enableIconText) iconTextSizeFactor else 0f,
            allAppsIconTextSizeFactor = if (enableAllAppsIconText) allAppsIconTextSizeFactor else 0f,
            iconFolderTextSizeFactor = if (enableIconTextFolder) iconFolderTextSizeFactor else 0f,
        )
    }

    companion object {
        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getDPO)
    }
}
