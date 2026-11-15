package app.qqluma

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding, mirroring the preview's shape:
 * a Start page, then a top progress bar with three segments and two milestone dots.
 *   segment 1 — environment + guest stack (Ubuntu, desktop, QQ, SnowLuma) with a live
 *               log under the checklist, so installs and errors are visible as they happen
 *   segment 2 — real QQ QR login: the guest publishes a fresh screenshot of the QQ login
 *               window every couple of seconds and this page polls it (实时刷码)
 *   segment 3 — the user's own API key, then the assistant persona （名字/人设/风格）
 * then a 欢迎使用 page whose 开始 button enters the chat.
 */
private enum class Stage { START, SETUP, SCAN, KEY, PERSONA, DONE }

private val Accent = Color(0xFFD47755)
private val Track = Color(0xFFE7DFCE)
private val DotIdle = Color(0xFFD9CFB8)

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

    // SETUP state
    var status by remember { mutableStateOf("") }
    var setupFraction by remember { mutableStateOf(0f) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var setupRunId by remember { mutableStateOf(0) }
    val stepLabels = remember { mutableStateListOf<String>() }
    val logLines = remember { mutableStateListOf<String>() }

    // KEY state
    var providerCustom by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.deepseek.com") }
    var model by remember { mutableStateOf("deepseek-chat") }

    // PERSONA state
    var personaName by remember { mutableStateOf("小Q") }
    var personaStyle by remember { mutableStateOf("自然口语") }
    var personaPrompt by remember { mutableStateOf("") }

    val overall = when (stage) {
        Stage.START -> 0f
        Stage.SETUP -> setupFraction / 3f
        Stage.SCAN -> 1f / 3f
        Stage.KEY -> 2f / 3f
        Stage.PERSONA -> 5f / 6f
        Stage.DONE -> 1f
    }

    LaunchedEffect(stage, setupRunId) {
        if (stage != Stage.SETUP) return@LaunchedEffect
        setupError = null
        setupFraction = 0f
        stepLabels.clear()
        logLines.clear()
        status = "正在准备环境…"
        // Refresh the app-owned guest scripts (stack installer, host-init) before any
        // exec needs them — on an upgrade this is the first run of the new code.
        withContext(Dispatchers.IO) { sandbox.ensureDataLayout() }
        val envOut = withContext(Dispatchers.IO) {
            sandbox.exec("echo GUEST_OK && uname -a", onProgress = { line ->
                status = line
                Regex("步骤 (\\d+)/(\\d+)").find(line)?.let { m ->
                    val n = m.groupValues[1].toFloatOrNull()
                    val total = m.groupValues[2].toFloatOrNull()
                    if (n != null && total != null && total > 0f) {
                        setupFraction = (n / total) * 0.4f
                        if (!stepLabels.contains(line)) stepLabels.add(line)
                    }
                }
                if (line.contains("已就绪") || line.contains("原地修复")) setupFraction = 0.4f
            })
        }
        if (!envOut.contains("GUEST_OK")) {
            setupError = envOut
            status = "环境配置没能完成"
            return@LaunchedEffect
        }
        setupFraction = 0.4f
        if (sandbox.stackInstalled()) {
            setupFraction = 1f
            status = "环境与 QQ 栈都已就绪"
            stage = Stage.SCAN
            return@LaunchedEffect
        }
        status = "正在安装桌面、QQ 与 SnowLuma（要下约 250MB，慢一点正常）…"
        val stackOut = withContext(Dispatchers.IO) {
            sandbox.exec(
                "sh /qqluma-data/stack/stack-install.sh",
                timeoutSec = 1800,
                onLine = { line ->
                    logLines.add(line)
                    while (logLines.size > 120) logLines.removeAt(0)
                    Regex("\\[stack] (\\d+)/(\\d+)").find(line)?.let { m ->
                        val n = m.groupValues[1].toFloatOrNull()
                        val total = m.groupValues[2].toFloatOrNull()
                        if (n != null && total != null && total > 0f) {
                            setupFraction = 0.4f + (n / total) * 0.6f
                            status = line
                        }
                    }
                }
            )
        }
        if (sandbox.stackInstalled()) {
            setupFraction = 1f
            status = "环境就绪"
            stage = Stage.SCAN
        } else {
            setupError = stackOut.takeLast(2000)
            status = "QQ 栈安装没能完成，看下面日志最后几行"
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
                Stage.PERSONA -> "第三步 · 人设"
                Stage.DONE -> "完成"
            },
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(16.dp))

        when (stage) {
            Stage.START -> {
                Text("在手机里搭一个独立的 Ubuntu 沙箱，住着一个 QQ 小号和它的助手。环境只配一次，之后杀进程、重启、升级都不用重来。")
                Spacer(Modifier.height(24.dp))
                Button(onClick = { stage = Stage.SETUP }) { Text("Start") }
            }

            Stage.SETUP -> {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(status)
                    if (stepLabels.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        stepLabels.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (logLines.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("实时日志", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF201D1A))
                                .padding(10.dp)
                        ) {
                            Text(
                                logLines.joinToString("\n"),
                                color = Color(0xFFEDE6D6),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (setupError != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(setupError!!, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { setupRunId++ }) { Text("重试") }
                    }
                }
            }

            Stage.SCAN -> ScanStage(
                sandbox = sandbox,
                onDone = { stage = Stage.KEY },
                onSkip = { stage = Stage.KEY }
            )

            Stage.KEY -> {
                Text("填你自己的 API Key（助手用它调模型）。Key 只存在这台手机的沙箱数据区里。")
                Spacer(Modifier.height(16.dp))
                Row {
                    FilterChip(
                        selected = !providerCustom,
                        onClick = { providerCustom = false; baseUrl = "https://api.deepseek.com" },
                        label = { Text("DeepSeek 官方") }
                    )
                    Spacer(Modifier.size(8.dp))
                    FilterChip(
                        selected = providerCustom,
                        onClick = { providerCustom = true },
                        label = { Text("自定义接口") }
                    )
                }
                if (providerCustom) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("默认模型") },
                    singleLine = true,
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
                            stage = Stage.PERSONA
                        }
                    },
                    enabled = apiKey.isNotBlank()
                ) { Text("保存") }
                TextButton(onClick = { stage = Stage.PERSONA }) { Text("跳过") }
            }

            Stage.PERSONA -> {
                Text("给助手定个人设：它在 QQ 里说话、回消息都按这个来。之后在会话页右上角的控制台里随时能改。")
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = personaName,
                    onValueChange = { personaName = it },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    listOf("自然口语", "简洁干练", "活泼吐槽", "严谨正式").forEach { s ->
                        FilterChip(
                            selected = personaStyle == s,
                            onClick = { personaStyle = s },
                            label = { Text(s) }
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = personaPrompt,
                    onValueChange = { personaPrompt = it },
                    label = { Text("人设（性格、说话方式、注意事项…）") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AppConfig.savePersona(
                                sandbox,
                                AppConfig.Persona(personaName, personaPrompt, personaStyle)
                            )
                        }
                        stage = Stage.DONE
                    }
                }) { Text("保存人设") }
                TextButton(onClick = { stage = Stage.DONE }) { Text("先用默认的") }
            }

            Stage.DONE -> {
                Text("欢迎使用！", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Text("✓ 环境与 QQ 栈已就绪")
                Text("✓ Key 已保存")
                Text("✓ 人设：$personaName（$personaStyle）")
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    AppConfig.setOnboarded(ctx, true)
                    onFinished()
                }) { Text("开始") }
            }
        }
    }
}

/**
 * Real QR login: the guest's watcher publishes a fresh screenshot of the QQ window
 * (qr.png) plus a status file every ~2s; this page polls both and redraws, which is
 * what makes the code feel live — when QQ refreshes an expired code, the new one
 * shows up here within a couple of seconds.
 */
@Composable
private fun ScanStage(sandbox: Sandbox, onDone: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    var qrBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var qqState by remember { mutableStateOf("starting") }
    var stamp by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { sandbox.ensureDataLayout() }
        QqHostService.start(ctx)
        while (true) {
            val (bmp, state) = withContext(Dispatchers.IO) {
                val f = sandbox.qrImageFile()
                val bmp = if (f.isFile && f.length() > 0) {
                    runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()
                } else null
                val st = sandbox.qrStatusJson()?.let {
                    runCatching { org.json.JSONObject(it).optString("qq") }.getOrNull()
                } ?: "starting"
                bmp to st
            }
            if (bmp != null) qrBitmap = bmp
            qqState = state
            stamp = System.currentTimeMillis()
            delay(1500)
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("用小号扫这张码登录 QQ。码是沙箱里 QQ 窗口的实时截图，过期刷新会自动跟上。")
        Spacer(Modifier.height(16.dp))
        val bmp = qrBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "QQ 登录二维码",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Track, RoundedCornerShape(12.dp))
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF1EBDD)),
                contentAlignment = Alignment.Center
            ) { Text("QQ 正在启动…") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (qqState) {
                "hooked" -> "已和 QQ 接上头，扫码后等它上线。"
                "window" -> "等待扫码…（${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(stamp))} 刷新）"
                "desktop" -> "桌面已经起来了，QQ 窗口还没出现，下面是桌面实时画面。再等一会儿，还不出就展开日志看看。"
                else -> "QQ 正在启动，码马上就来。"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onDone) { Text("已扫码登录") }
        TextButton(onClick = onSkip) { Text("稍后再扫") }

        // Guest logs, for when the code does not show up: host.log plus the
        // per-component logs the resident stack writes under snowluma/logs/.
        var showLogs by remember { mutableStateOf(false) }
        var logsText by remember { mutableStateOf("") }
        TextButton(onClick = { showLogs = !showLogs }) {
            Text(if (showLogs) "收起日志" else "查看日志（码不出来时点这个）")
        }
        if (showLogs) {
            LaunchedEffect(Unit) {
                while (true) {
                    logsText = withContext(Dispatchers.IO) {
                        val base = sandbox.dataDir
                        fun tail(rel: String, n: Int = 45): String {
                            val f = java.io.File(base, rel)
                            if (!f.isFile) return "(无 $rel)"
                            val lines = runCatching { f.readLines() }.getOrElse { return "(读不了 $rel)" }
                            lines.takeLast(n).joinToString("\n")
                        }
                        buildString {
                            append("=== status.json ===\n")
                            append(tail("snowluma/status.json", 3)).append("\n")
                            append("=== host.log ===\n")
                            append(tail("host.log")).append("\n")
                            append("=== qq.log ===\n")
                            append(tail("snowluma/logs/qq.log")).append("\n")
                            append("=== snowluma.log ===\n")
                            append(tail("snowluma/logs/snowluma.log")).append("\n")
                            append("=== xvfb.log ===\n")
                            append(tail("snowluma/logs/xvfb.log", 15))
                        }
                    }
                    delay(3000)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1C1A17))
                    .padding(10.dp)
            ) {
                Text(
                    logsText.ifBlank { "（读取中…）" },
                    color = Color(0xFFF5F0E6),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
