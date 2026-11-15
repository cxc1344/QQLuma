package app.qqluma

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding, shape:
 * a Start page, then a top progress bar with three segments and two milestone dots,
 * with a live status line under it saying what is happening right now.
 *   segment 1 — environment setup (real installer steps drive the bar) → dot 1
 *   segment 2 — QQ QR login (placeholder until SnowLuma lands in milestone 2) → dot 2
 *   segment 3 — the user's own API key
 * then a 欢迎使用 page whose 开始 button enters the app.
 */
private enum class Stage { START, SETUP, SCAN, KEY, DONE }

private val Accent = Color(0xFFD47755)
private val Track = Color(0xFF2E2E2C)
private val DotIdle = Color(0xFF3A3A38)

@Composable
private fun StageProgressBar(progress: Float) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(3.dp))
                .background(Track)
        )
        if (progress > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Accent)
            )
        }
        listOf(1f / 3f, 2f / 3f).forEach { f ->
            val reached = progress >= f - 0.001f
            Box(
                Modifier
                    .size(14.dp)
                    .offset(x = maxWidth * f - 7.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(if (reached) Accent else DotIdle)
                    .border(2.dp, if (reached) Accent else Track, CircleShape)
            )
        }
    }
}

@Composable
fun OnboardingWizard(sandbox: Sandbox, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.START) }
    var status by remember { mutableStateOf("") }
    var setupFraction by remember { mutableStateOf(0f) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var setupRunId by remember { mutableStateOf(0) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.deepseek.com") }

    val overall = when (stage) {
        Stage.START -> 0f
        Stage.SETUP -> setupFraction / 3f
        Stage.SCAN -> 1f / 3f
        Stage.KEY -> 2f / 3f
        Stage.DONE -> 1f
    }

    LaunchedEffect(stage, setupRunId) {
        if (stage != Stage.SETUP) return@LaunchedEffect
        setupError = null
        setupFraction = 0f
        status = "正在准备环境…"
        val out = withContext(Dispatchers.IO) {
            sandbox.exec("echo GUEST_OK && uname -a", onProgress = { line ->
                status = line
                Regex("步骤 (\\d+)/(\\d+)").find(line)?.let { m ->
                    val n = m.groupValues[1].toFloatOrNull()
                    val total = m.groupValues[2].toFloatOrNull()
                    if (n != null && total != null && total > 0f) setupFraction = n / total
                }
                if (line.contains("已就绪") || line.contains("原地修复")) setupFraction = 1f
            })
        }
        if (out.contains("GUEST_OK")) {
            setupFraction = 1f
            status = "环境就绪"
            stage = Stage.SCAN
        } else {
            setupError = out
            status = "环境配置没能完成"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        StageProgressBar(overall)
        Spacer(Modifier.height(10.dp))
        Text(
            when (stage) {
                Stage.START -> "QQLuma"
                Stage.SETUP -> "第一步 · 配置环境"
                Stage.SCAN -> "第二步 · 扫码登录"
                Stage.KEY -> "第三步 · 配置 Key"
                Stage.DONE -> "完成"
            },
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(16.dp))

        when (stage) {
            Stage.START -> {
                Text("在手机里搭一个独立的 Ubuntu 沙箱，住着 DSH 和一个 QQ 小号。环境只配一次，之后杀进程、重启、升级都不用重来。")
                Spacer(Modifier.height(24.dp))
                Button(onClick = { stage = Stage.SETUP }) { Text("Start") }
            }

            Stage.SETUP -> {
                Text(status)
                if (setupError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(setupError!!, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { setupRunId++ }) { Text("重试") }
                }
            }

            Stage.SCAN -> {
                Text("用小号扫码登录 QQ。")
                Spacer(Modifier.height(8.dp))
                Text(
                    "本版说明：二维码要等 SnowLuma 入住沙箱（里程碑 2）后才会真的出现在这里；现在先走通向导流程。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { stage = Stage.KEY }) { Text("下一步") }
            }

            Stage.KEY -> {
                Text("填你自己的 API Key（DSH 用它调模型，缓存命中好、省钱）。Key 只存在这台手机的沙箱数据区里。")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                sandbox.ensureDataLayout()
                                AppConfig.saveDshConfig(sandbox, baseUrl, apiKey)
                            }
                            stage = Stage.DONE
                        }
                    },
                    enabled = apiKey.isNotBlank()
                ) { Text("保存") }
            }

            Stage.DONE -> {
                Text("欢迎使用！", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    AppConfig.setOnboarded(ctx, true)
                    onFinished()
                }) { Text("开始") }
            }
        }
    }
}
