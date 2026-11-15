package app.qqluma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point: first run goes through the onboarding wizard (Start → 环境 → 扫码 →
 * 配 Key → 人设 → 欢迎使用）; afterwards the app opens straight onto the chat.
 * Host controls, the persistence bench and persona editing live one level down in
 * the console, reached from the chat header.
 *
 * Visual language mirrors the preview: warm paper background, one orange accent,
 * serif display type.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sandbox = Sandbox(this)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFD47755),
                    background = Color(0xFFFAF9F5),
                    surface = Color(0xFFFFFFFF),
                    onBackground = Color(0xFF1C1A17),
                    onSurface = Color(0xFF1C1A17),
                    surfaceVariant = Color(0xFFF1EBDD),
                    outline = Color(0xFFE3DCCB)
                ),
                typography = AppTypography
            ) {
                Surface(Modifier.fillMaxSize()) {
                    var onboarded by remember { mutableStateOf(AppConfig.isOnboarded(this)) }
                    var showConsole by remember { mutableStateOf(false) }
                    when {
                        !onboarded -> OnboardingWizard(sandbox) { onboarded = true }
                        showConsole -> ConsoleScreen(
                            sandbox,
                            onBack = { showConsole = false },
                            onReconfigure = {
                                AppConfig.setOnboarded(this, false)
                                onboarded = false
                                showConsole = false
                            }
                        )
                        else -> ChatScreen(sandbox, onOpenConsole = { showConsole = true })
                    }
                }
            }
        }
    }
}

/**
 * The LaTeX heading face, both scripts: Latin Modern Roman (the classic LaTeX look)
 * takes Latin glyphs; Chinese falls through to Noto Serif SC — the same GB2312-subset
 * files BotHive bundles — so headings read as one typeset document instead of
 * serif Latin next to sans Chinese. (Latin Modern alone has no CJK glyphs.)
 */
private val LatexFace = FontFamily(
    Font(R.font.latin_modern_regular, FontWeight.Normal),
    Font(R.font.latin_modern_bold, FontWeight.Bold),
    Font(R.font.cjk_serif_regular, FontWeight.Normal),
    Font(R.font.cjk_serif_bold, FontWeight.Medium),
    Font(R.font.cjk_serif_bold, FontWeight.SemiBold),
    Font(R.font.cjk_serif_bold, FontWeight.Bold)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = LatexFace, fontWeight = FontWeight.Bold, fontSize = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = LatexFace, fontWeight = FontWeight.Bold, fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = LatexFace, fontWeight = FontWeight.Bold, fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = LatexFace, fontWeight = FontWeight.Bold, fontSize = 16.sp
    )
)

/** Secondary screen: resident-host controls, persona editing, persistence bench. */
@Composable
private fun ConsoleScreen(sandbox: Sandbox, onBack: () -> Unit, onReconfigure: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var persona by remember { mutableStateOf(AppConfig.loadPersona(sandbox)) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TextButton(onClick = onBack) { Text("← 回到会话") }
        Text("控制台", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("宿主状态: ${QqHostService.status(ctx)}")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { QqHostService.start(ctx) }) { Text("启动常驻宿主") }
        Button(onClick = { QqHostService.stop(ctx) }) { Text("停止常驻宿主") }
        Button(onClick = onReconfigure) { Text("重新走配置向导") }
        Spacer(Modifier.height(20.dp))
        Text("人设", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = persona.name,
            onValueChange = { persona = persona.copy(name = it) },
            label = { Text("名字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = persona.style,
            onValueChange = { persona = persona.copy(style = it) },
            label = { Text("风格") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = persona.prompt,
            onValueChange = { persona = persona.copy(prompt = it) },
            label = { Text("人设（性格、说话方式、注意事项…）") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val p = persona
            scope.launch { withContext(Dispatchers.IO) { AppConfig.savePersona(sandbox, p) } }
        }) { Text("保存人设") }
        Spacer(Modifier.height(20.dp))
        BenchSection(sandbox)
    }
}
