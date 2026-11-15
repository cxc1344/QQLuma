package app.qqluma

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One chat message, persisted as JSON in the data dir so history survives restarts. */
data class ChatMsg(val role: String, val text: String, val ts: Long)

object ChatStore {
    private fun file(sandbox: Sandbox): File =
        File(sandbox.dataDir, "chat/history.json").apply { parentFile?.mkdirs() }

    fun load(sandbox: Sandbox): List<ChatMsg> {
        val f = file(sandbox)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatMsg(o.optString("role"), o.optString("text"), o.optLong("ts"))
            }
        }.getOrElse { emptyList() }
    }

    fun save(sandbox: Sandbox, msgs: List<ChatMsg>) {
        val arr = JSONArray()
        msgs.takeLast(500).forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("text", m.text).put("ts", m.ts))
        }
        runCatching { file(sandbox).writeText(arr.toString()) }
    }
}

/**
 * The chat home: opening the app lands here. Header shows the persona's name and the
 * QQ link state (polled from the guest's published status), the digest card mirrors
 * the preview's group toggle, and messages persist locally.
 *
 * Replies: digest commands are executed for real through DigestStore (the same state
 * file the in-sandbox agent uses); everything else gets a short persona-flavoured
 * acknowledgement until the DSH brain moves in and answers for real.
 */
@Composable
fun ChatScreen(sandbox: Sandbox, onOpenConsole: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val persona = remember { AppConfig.loadPersona(sandbox) }
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var qqState by remember { mutableStateOf("starting") }
    var digestGroups by remember { mutableStateOf<List<DigestGroup>>(emptyList()) }
    var replyIdx by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { ChatStore.load(sandbox) }
        messages.addAll(loaded)
        if (messages.isEmpty()) {
            val hello = ChatMsg(
                "assistant",
                "我是${persona.name}。QQ 小号住在沙箱里，有事直接说；想给哪个群开概括模式，也是在这里跟我说。",
                System.currentTimeMillis()
            )
            messages.add(hello)
            withContext(Dispatchers.IO) { ChatStore.save(sandbox, messages) }
        }
        digestGroups = withContext(Dispatchers.IO) { DigestStore.list(sandbox) }
        while (true) {
            qqState = withContext(Dispatchers.IO) {
                sandbox.qrStatusJson()?.let {
                    runCatching { JSONObject(it).optString("qq") }.getOrNull()
                } ?: "starting"
            }
            delay(3000)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank()) return
        input = ""
        val now = System.currentTimeMillis()
        messages.add(ChatMsg("user", text, now))
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                answerLocally(sandbox, persona, text, replyIdx)
            }
            replyIdx++
            messages.add(ChatMsg("assistant", reply, System.currentTimeMillis()))
            digestGroups = withContext(Dispatchers.IO) { DigestStore.list(sandbox) }
            withContext(Dispatchers.IO) { ChatStore.save(sandbox, messages) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (qqState) {
                            "hooked" -> Color(0xFF4C9A52)
                            "window" -> Color(0xFFD47755)
                            else -> Color(0xFFB9AE97)
                        }
                    )
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(persona.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    when (qqState) {
                        "hooked" -> "QQ 已接通"
                        "window" -> "QQ 等待扫码（去控制台或重走配置可看码）"
                        else -> "QQ 未启动"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onOpenConsole) { Text("控制台") }
        }

        if (digestGroups.isNotEmpty()) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("概括模式", style = MaterialTheme.typography.titleMedium)
                    digestGroups.forEach { g ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (g.name.isNotBlank()) g.name else g.id,
                                Modifier.weight(1f)
                            )
                            Switch(
                                checked = g.enabled,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            DigestStore.set(sandbox, g.id, g.name, on)
                                        }
                                        digestGroups = DigestStore.list(sandbox)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(messages) { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    if (m.role == "user") Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .widthIn(max = 300.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (m.role == "user") Color(0xFFF3D9C8) else Color(0xFFFFFFFF)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(m.text)
                    }
                    if (m.role != "user") Spacer(Modifier.weight(1f))
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("跟 ${persona.name} 说点什么…") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = input.isNotBlank()) { Text("发送") }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Local answers before the DSH brain moves in. Digest commands run for real against
 * the shared state file; other messages get a rotating persona-flavoured receipt.
 */
private fun answerLocally(
    sandbox: Sandbox,
    persona: AppConfig.Persona,
    text: String,
    idx: Int
): String {
    if (text.contains("概括")) {
        val wantOn = !text.contains("关") && !text.contains("停")
        val id = Regex("\\d{5,}").find(text)?.value
        val known = DigestStore.list(sandbox)
        val byName = known.firstOrNull { it.name.isNotBlank() && text.contains(it.name) }
        val gid = id ?: byName?.id
        if (gid != null) {
            val gname = byName?.name ?: known.firstOrNull { it.id == gid }?.name ?: ""
            DigestStore.set(sandbox, gid, gname, wantOn)
            val label = if (gname.isNotBlank()) "「$gname」" else "群 $gid"
            return "好，$label 的概括模式${if (wantOn) "开" else "关"}了。"
        }
        return "要给哪个群开？你把群号发我，或者先告诉我群名，我记下来。"
    }
    val receipts = listOf(
        "收到，先记下了。",
        "嗯，我在。等大脑（DSH）入住沙箱，这种事我就能直接动手办了。",
        "好，这条存本地了，不会丢。"
    )
    return receipts[idx % receipts.size]
}
