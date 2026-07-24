package app.morrowa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import app.morrowa.ui.BackupScreen

class MorrowaBackupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BackupScreen(
                backgroundColor = Color(0xFF101010),
                onBack = { finish() },
            )
        }
    }
}
