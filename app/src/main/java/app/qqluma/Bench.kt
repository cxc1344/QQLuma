package app.qqluma

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
* The persistence test bench, kept as a section of the home screen until the real
* Claude-style chat UI replaces it. It proves QQLuma's red lines on a real device:
* sentinels written inside the rootfs and in /qqluma-data must survive app restarts,
* process kills, and — the BotHive bug — damaged bookkeeping markers.
*/
@Composable
fun BenchSection(sandbox: Sandbox) {
val scope = rememberCoroutineScope()
var log by remember { mutableStateOf("持久化验收台\n")}
var busy by remember { mutableStateOf(false)}
fun run(label: String, block: () -> String) {
if (busy) return
busy = true
scope.launch {
val out = withContext(Dispatchers.IO) {
try {
block()
} catch (e: Exception) {
"错误: ${e.message}"
}
}
log += "\n\n$out\n"
busy = false
}
}
Column {
Text("验收台", style = MaterialTheme.typography.titleMedium)
Spacer(Modifier.height(8.dp))
Button(
onClick = { run("环境状态") { sandbox.status()}},
enabled =!busy
) { Text("查看环境状态")}
Button(
onClick = {
run("初始化/进入沙箱") {
sandbox.exec("echo GUEST_OK && uname -a && head -2 /etc/os-release")
}
},
enabled =!busy
) { Text("初始化 / 进入沙箱")}
Button(
onClick = {
run("写入哨兵") {
sandbox.exec(
"echo sentinel-rootfs-$(date +%s) > /root/sentinel.txt && " +
"echo sentinel-data-$(date +%s) > /qqluma-data/sentinel.txt && " +
"echo WROTE && cat /root/sentinel.txt /qqluma-data/sentinel.txt"
)
}
},
enabled =!busy
) { Text("写入哨兵（rootfs 内 + 数据区）")}
Button(
onClick = {
run("校验哨兵") {
sandbox.exec(
"echo rootfs:; cat /root/sentinel.txt 2>/dev/null || echo MISSING; " +
"echo data:; cat /qqluma-data/sentinel.txt 2>/dev/null || echo MISSING"
)
}
},
enabled =!busy
) { Text("校验哨兵")}
Button(
onClick = {
run("模拟标记损坏") {
sandbox.damageMarkersForTest()
"标记已损坏（删标记 + 立待装旗），执行命令触发自检：\n" +
sandbox.exec("echo HEALED && cat /root/sentinel.txt")
}
},
enabled =!busy
) { Text("模拟标记损坏 → 应自愈且哨兵不丢")}
Button(
onClick = {
run("快照数据区") {
sandbox.ensureDataLayout()
val snap = sandbox.snapshotData()
if (snap == null) "数据区为空，无可快照" else "已快照到 ${snap.path}"
}
},
enabled =!busy
) { Text("快照数据区（保会话）")}
Spacer(Modifier.height(12.dp))
Text(log, style = MaterialTheme.typography.bodySmall)
}
}
