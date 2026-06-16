package app.morrowa

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MorrowaPageController(context: Context) {
    private val appContext = context.applicationContext
    private val _currentPage = MutableStateFlow(MorrowaPage.HOME)

    val currentPage: StateFlow<MorrowaPage> = _currentPage.asStateFlow()

    fun moveNext() {
        setPage(_currentPage.value.next())
    }

    fun movePrevious() {
        setPage(_currentPage.value.previous())
    }

    fun setPage(page: MorrowaPage) {
        _currentPage.value = page
    }
}
