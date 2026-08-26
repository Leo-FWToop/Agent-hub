package com.tailnet.agenthub

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tailnet.agenthub.ui.App
import java.io.File

class MainActivity : ComponentActivity() {

    /** 上次崩溃的堆栈（存在则先展示，便于用户反馈） */
    private val lastCrash = mutableStateOf<String?>(null)
    private var crashFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        enableEdgeToEdge()
        val crash = readLastCrash()
        lastCrash.value = crash
        setContent {
            MaterialTheme {
                val crashText = lastCrash.value
                if (crashText == null) {
                    App()
                } else {
                    // 崩溃诊断页：展示上次崩溃堆栈，用户可复制反馈
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "上次运行崩溃了\n以下是崩溃信息（点击按钮复制后反馈）：",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                crashText.take(4000),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp)
                            )
                            Button(onClick = {
                                val cm = getSystemService(CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                cm?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("crash", crashText)
                                )
                                Toast.makeText(this@MainActivity, "已复制", Toast.LENGTH_SHORT).show()
                            }) { Text("复制崩溃信息") }
                            Button(onClick = {
                                crashFile?.delete()
                                lastCrash.value = null
                            }) { Text("清除并继续使用") }
                        }
                    }
                }
            }
        }
    }

    /** 捕获未处理异常写入文件，避免"一打开就闪退"无从排查 */
    private fun installCrashHandler() {
        val dir = File(filesDir, "openclaw").apply { mkdirs() }
        crashFile = File(dir, "crash.log")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile?.writeText(
                    buildString {
                        appendLine("thread: ${thread.name}")
                        appendLine(
                            android.util.Log.getStackTraceString(throwable)
                        )
                    }
                )
            } catch (_: Throwable) {
            }
            // 交回系统默认处理（结束进程）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun readLastCrash(): String? = try {
        crashFile?.takeIf { it.exists() }?.readText()?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private var defaultHandler: Thread.UncaughtExceptionHandler? = null

        init {
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        }
    }
}
