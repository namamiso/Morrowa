package app.morrowa.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morrowa.data.BackupRepository
import app.morrowa.data.MorrowaBackup
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupScreen(
    backgroundColor: Color,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        isWorking = true
        statusMessage = null
        scope.launch {
            runCatching {
                val repo = BackupRepository(context)
                val data = withContext(Dispatchers.IO) { repo.exportAll() }
                val json = MorrowaBackup.serialize(data)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    } ?: error("ファイルを開けませんでした")
                }
            }.onSuccess {
                statusMessage = "エクスポートしました"
            }.onFailure {
                statusMessage = "エラー: ${it.message}"
            }
            isWorking = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { importUri = it }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "戻る",
                        tint = Color.White,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "バックアップ",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "エクスポート",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Habit / ToDo / アラーム / ゴミ箱データを JSON ファイルに保存します。",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val dateStr = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString()
                    exportLauncher.launch("morrowa_backup_$dateStr.json")
                },
                enabled = !isWorking,
            ) {
                Text("JSON をエクスポート")
            }

            Spacer(modifier = Modifier.height(32.dp))
            Divider(color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "インポート",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "JSON ファイルを選んでインポートします。現在のデータはすべて置き換わります。",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
                enabled = !isWorking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                ),
            ) {
                Text("JSON をインポート", color = Color.White)
            }

            statusMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                )
            }
            if (isWorking) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(color = Color.White)
            }
        }
    }

    importUri?.let { uri ->
        ConfirmDialog(
            title = "インポートの確認",
            message = "現在の Habit / ToDo データはすべて置き換わります。続けますか？",
            onConfirm = {
                isWorking = true
                statusMessage = null
                importUri = null
                scope.launch {
                    runCatching {
                        val json = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                                ?: error("ファイルを読み込めませんでした")
                        }
                        val data = MorrowaBackup.deserialize(json)
                        val repo = BackupRepository(context)
                        withContext(Dispatchers.IO) { repo.importAll(data) }
                    }.onSuccess {
                        statusMessage = "インポートしました"
                    }.onFailure {
                        statusMessage = "エラー: ${it.message}"
                    }
                    isWorking = false
                }
            },
            onDismiss = { importUri = null },
        )
    }
}
