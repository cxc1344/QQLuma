# 概括模式工具契约（给 DSH 的工具层）

目标体验：用户在聊天里发一句话，例如「给班级群开概括模式」，模型调用工具，
**单个群**的概括模式随即打开。开启后，助手对该群只潜水收集消息、定时或按需出摘要，
不在群里发言（QQ 桥时代班级群潜水汇报的同一套规矩）。

## 状态与执行面

- 唯一状态文件：`/qqluma-data/digest/groups.json`（rootfs 之外，沙箱重建不丢）
- App 侧读写：`DigestStore.kt`；沙箱侧读写：命令 `qqluma-digest`（在 `/qqluma-data/bin/`，
  需在 PATH 中，或用全路径调用）
- 两侧写文件都是「临时文件 + 改名」的原子写，格式见 `DigestStore.kt` 文件头

## 函数调用模式（function calling）

```json
{
  "name": "set_group_digest",
  "description": "打开或关闭某一个 QQ 群的概括模式。开启后助手只收集该群消息并生成摘要，不在群里发言。用户说「给X群开/关概括模式」「盯着X群」「别在X群说话，只给我总结」时调用。",
  "parameters": {
    "type": "object",
    "properties": {
      "group_id": { "type": "string", "description": "QQ 群号（纯数字字符串）" },
      "group_name": { "type": "string", "description": "群名称，用于界面展示" },
      "enabled": { "type": "boolean", "description": "true 开启，false 关闭" }
    },
    "required": ["group_id", "enabled"]
  }
}
```

```json
{
  "name": "list_group_digests",
  "description": "列出所有群的概括模式状态。用户问「哪些群开了概括」时调用。",
  "parameters": { "type": "object", "properties": {} }
}
```

执行映射（DSH 工具层把函数调用落到沙箱命令）：

| 函数 | 命令 |
| --- | --- |
| `set_group_digest` | `qqluma-digest set <group_id> <on\|off> <group_name>` |
| `list_group_digests` | `qqluma-digest list` |

## 给模型的系统提示片段（接入 DSH 时原样并入）

> 你管控着一个 QQ 小号。当用户要求给某个群开启或关闭「概括模式」时：
> 1. 先确认群号——用户通常只说群名，用你能用的群列表能力把群名对到群号，对不上就问用户；
> 2. 调用 set_group_digest（或执行 qqluma-digest set）写入状态；
> 3. 成功后用一句话向用户确认：「已给「群名」开启概括模式」。
>
> 概括模式开启的群：你只收集消息，按用户要求或定时生成摘要发给用户，**绝不在群里发言**。

## 待办（随里程碑推进）

- [ ] 里程碑 2/3：DSH 入住后把上面的函数模式注册进 DSH 工具层，并给模型接上群列表能力（OneBot get_group_list）
- [ ] 摘要的生成与投递节奏（定时？攒够 N 条？用户点名才出？）确定后补进系统提示
