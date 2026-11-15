package app.qqluma

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Entry point: first run goes through the onboarding wizard (Start → 环境 → 扫码 →
 * 配 Key → 欢迎使用）, afterwards the home screen. The Claude-style chat UI takes over
 * the home screen once DSH moves into the sandbox (milestone 3).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sandbox = Sandbox(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD47755),
                    background = Color(0xFF151515),
                    surface = Color(0xFF151515)
                ),
                typography = AppTypography
            ) {
                Surface(Modifier.fillMaxSize()) {
                    var onboarded by remember { mutableStateOf(AppConfig.isOnboarded(this)) }
                    if (!onboarded) {
                        OnboardingWizard(sandbox) { onboarded = true }
                    } else {
                        HomeScreen(
                            sandbox,
                            onReconfigure = {
                                AppConfig.setOnboarded(this, false)
                                onboarded = false
                            }
                        )
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
 * serif Latin next to sans Chinese. (BotHive's Fonts.kt documents this exact pitfall;
 * Latin Modern alone has no CJK glyphs.)
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

@Composable
private fun HomeScreen(sandbox: Sandbox, onReconfigure: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("QQLuma", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("宿主状态: ${QqHostService.status(ctx)}")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { QqHostService.start(ctx) }) { Text("启动常驻宿主") }
        Button(onClick = { QqHostService.stop(ctx) }) { Text("停止常驻宿主") }
        Button(onClick = onReconfigure) { Text("重新走配置向导") }
        Spacer(Modifier.height(16.dp))
        Text("概括模式", style = MaterialTheme.typography.titleMedium)
        val digestOn = remember { DigestStore.list(sandbox).filter { it.enabled } }
        if (digestOn.isEmpty()) {
            Text(
                "暂无群开启。在聊天里对助手说一句「给某群开概括模式」，它调用工具后这里就会出现。",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            digestOn.forEach { g ->
                Text("· ${if (g.name.isNotBlank()) g.name else g.id}（${g.id}）")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "聊天主界面（Claude 风）随 DSH 入住沙箱后上线；现在先用下面的验收台验持久化。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        BenchSection(sandbox)
    }
}
