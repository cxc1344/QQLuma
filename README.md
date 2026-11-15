# QQLuma

一个住在手机本地隔离沙箱里的 QQ 助手：安卓 APK 内嵌一个**持久**的 Ubuntu 环境，
里面跑 DSH 引擎（用户自配 API key）+ SnowLuma，管控一个独立 QQ 小号——进群、回消息、
发说说、点赞，并可给指定群单独开「概括模式」。

## 为什么会有这个项目

同类沙箱（BotHive）的验收里有一条硬指标没过：**沙箱刷新后不持久**。根因是自检逻辑——
rootfs 的标记文件一旦损坏，运行时就把整棵 Ubuntu 树删掉重装，装进去的环境和登录
会话全部陪葬。对普通 Bot 这只是麻烦，对 QQ 这种「扫码一次、长期在线」的场景是绝症：
沙箱一刷新，就要重新扫码。

QQLuma 就是为治这个病立的项：**在已有沙箱实现的基础上，把它变成持久的。**

## 持久化红线（设计约束，不许退让）

1. **会话数据外置**：SnowLuma/QQ 的数据目录放在 rootfs 之外的宿主目录，绑定挂载为
   guest 的 `/qqluma-data`。rootfs 就算整棵重建，登录态不死。
2. **只修不删**：标记损坏但核心二进制（apt/bash）完好时，就地修复标记，保留整棵树；
   只有核心真坏了才重装，且重装前旧树改名备份，永不直接删除。
3. **常驻进程托管**：前台服务托管一个长驻 PRoot 会话（`QqHostService`），进程退出
   自动拉回；数据在外置目录，拉回不需要重新扫码。
4. **假设会话会死**：万一被 QQ 风控踢下线，恢复路径只有「重新扫码」一步，
   配置与历史不丢。

## 架构

```
APK (app.qqluma)
├─ filesDir/linux-rootfs/qqluma   → guest /            Ubuntu 24.04 (PRoot)，可重建
├─ filesDir/sandbox/qqluma        → guest /workspace   工作区
├─ filesDir/qqluma-data           → guest /qqluma-data 会话/配置（红线 1）
├─ Sandbox / LinuxRuntime         一次性命令 + 环境自愈
├─ QqHostService                  常驻 PRoot 宿主（红线 3），入口 /qqluma-data/host-init.sh
├─ KeepAlive                      前台服务 + 开机自启 + 闹钟复活（沿用 BotHive 方案）
└─ DSH + SnowLuma                 里程碑 2/3 入住沙箱
```

## 里程碑

- [x] v0.1 骨架：持久化改造 + 验收台 UI（哨兵写入/校验/模拟标记损坏自愈/常驻宿主开关）
- [x] 首启向导：Start 页 → 三段进度条（两个里程碑点）+ 实时步骤文案 → 扫码（占位，待里程碑 2）→ 配 Key → 欢迎使用
- [x] 进程监督加固（借鉴 astrbot-for-android-snowluma 的实战经验）：孤儿进程回收、唤醒锁租约续期、期望状态持久化、启动宽限期后才报 running、数据区快照
- [ ] 真机验收持久化：重启 App、杀进程、标记损坏，哨兵与环境不丢
- [ ] SnowLuma 入沙箱：Xvfb + arm64 QQ 扫码登录、会话稳住
- [ ] DSH 入沙箱：用户配 key，经 OneBot 读到 QQ 消息
- [ ] 正式 UI：配置向导（环境 → 扫码 → 配 key）+ 聊天界面 + 群概括模式

## 构建

本项目不在普通开发机本地跑 Gradle 出包，走 GitHub Actions（见 `.github/workflows/build.yml`）：

1. `bash tools/fetch_proot_android.sh` 拉取 PRoot 二进制到 jniLibs
2. `gradle assembleRelease`（Gradle 8.7 / JDK 17 / compileSdk 35）
3. 可选固定签名：仓库 Secrets 配 `KEYSTORE_B64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
   `KEY_PASSWORD` 后，构建自动用项目密钥签名，保证新版能覆盖安装旧版

## 参考与致谢

- 沙箱基底源自我们自己的 BotHive（PRoot/Ubuntu 运行时）。
- 进程监督与 SnowLuma 接入的若干实战模式（孤儿进程回收、SnowLuma v2.2.1 版本锁定、
  HOME/XDG 外置、HOOK_AUTOLOAD 关闭）参考了 HZP-BT 的
  [astrbot-for-android-snowluma](https://github.com/HZP-BT/astrbot-for-android-snowluma)
  （其原创代码为 MIT 许可）——同路人的先行实测，少踩很多坑。

## 安全说明

- 只用独立小号，**永不接主号**：SnowLuma 是非官方客户端路线，有冻结/封号风险，使用者已知情。
- API key 由使用者自己在 App 内配置（BYOK），不经任何第三方服务器中转。
