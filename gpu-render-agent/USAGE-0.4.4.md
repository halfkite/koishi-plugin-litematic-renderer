# Litematic GPU Agent 0.4.4 使用文档

## 1. 功能说明

Litematic GPU Agent 使用隐藏的 Minecraft 26.2 / Fabric 0.19.3 Runtime，通过本机 GPU 渲染 `.litematic` 投影。它可以独立运行，也可以由 Koishi 插件通过 WebSocket v2 调度。

本版本重点修复长时间运行时的内存保护和 Runtime 重启：达到内存阈值后，Agent 会暂停领取新任务，等待当前任务完成，完整关闭 Minecraft/Fabric/GLFW 进程，再继续处理剩余队列。机器人连接、Web 服务和等待中的任务不会因为 Runtime 回收而丢失。

## 2. 启动方式

### Windows 便携包

解压 `litematic-gpu-agent-0.4.4-windows-x64.zip`，运行：

```text
Litematic GPU Agent/Litematic GPU Agent.exe
```

便携包自带 Java 25。Minecraft 26.2、Fabric 和资源文件首次渲染时从官方服务下载，不需要登录 Minecraft 账号。

### 跨平台 JAR

目标机器需要 Java 25：

```bash
java -jar litematic-gpu-agent-0.4.4-all.jar
java -jar litematic-gpu-agent-0.4.4-all.jar --web
java -jar litematic-gpu-agent-0.4.4-all.jar --bot
java -jar litematic-gpu-agent-0.4.4-all.jar --render FILE.litematic --output DIRECTORY
java -jar litematic-gpu-agent-0.4.4-all.jar --version
java -jar litematic-gpu-agent-0.4.4-all.jar --help
```

| 参数 | 作用 |
|---|---|
| 无参数 | 启动桌面 GUI |
| `--web` | 启动 Web 管理后台、机器人和渲染服务，默认端口 2618 |
| `--bot` | 启动机器人和渲染服务，不启动 Web 页面 |
| `--render` | 使用本地 GPU Runtime 渲染指定投影 |

Linux 无桌面环境推荐使用 `--web`，访问 `http://服务器IP:2618/`。首次启动会在终端和数据目录的 `web-credentials.txt` 输出随机 12 位数字密码，用户名默认为 `admin`。

## 3. 配置机器人

在桌面 GUI 的“连接设置”和“机器人账号”，或 Web 页面的配置页面中设置：

- 官方 QQ：AppID、AppSecret、沙箱、消息意图和群消息模式；
- OneBot/NekoBot：正向或反向 WebSocket、地址、端口、路径和 Token；
- 是否允许私聊渲染；
- 群白名单、黑名单和独立开关；
- 群聊/私聊文件大小限制；
- 合并转发、联合发送、引用、@ 和投影信息字段。

官方 QQ 的群消息模式支持：接收群文件自动识别、仅被 @ 时识别、都可以。启用“接收群文件自动识别”时，发送 `.litematic` 文件不需要 @ 机器人。

## 4. GPU 与视角

“本地渲染”页面的视角表同时用于本地批量渲染和云端任务。每个视角可单独设置：

- 水平角度、俯仰角度；
- 缩放或智能填充；
- 宽度、高度和超采样；
- 背景与透明背景；
- 亮度。普通视角 100% 为默认亮度，底视图以旧版 150% 实际亮度作为新的 100% 基准。

工具视角表增加视角后，云端返回的多视角拼接图也会包含这些视角。拼接方向支持横向和竖向；本地拼接可以单独开启。

拖放多个 `.litematic` 文件到“导入投影”，再点击“开始渲染”即可批量处理。导出目录会保留每张视角原图；开启本地拼接时还会生成 `merged.png`。

## 5. 内存保护与队列

### 内存看门狗

默认内存阈值为 4GB，统计 Agent 与所有 Minecraft Runtime 的物理内存：

1. 超过阈值后暂停领取新任务；
2. 当前已经开始的任务继续完成；
3. 同步终止 Java/Fabric/GLFW 进程树，并确认进程退出；
4. 恢复队列，下一项任务按需启动新的 Minecraft Runtime。

这不是整个 Agent 的重启，因此机器人连接、Web 后台和等待中的任务保持不变。10 分钟内自动回收超过 3 次后进入 30 分钟冷却，避免 Runtime 反复启动。

在 GUI 中可通过“内存重启阈值”修改阈值，设置为 `0` 可关闭自动回收。修改后立即影响后续检测。

### 队列内存上限

`agent.json` 中的 `maxQueuedRequestBytes` 默认是 `536870912`，即 512MB。它限制队列和正在处理任务仍保留的投影数据总量，防止大量大文件排队导致 Agent 自身爆内存。

```json
{
  "maxQueuedRequestBytes": 536870912
}
```

设置为 `0` 表示不限制，不建议在公网机器人或高并发环境使用。超过上限的新任务会返回 `QUEUE_MEMORY_LIMIT`，稍后重试即可。

Web 状态接口 `/api/status` 会返回 `queueLength`、`queuedRequestBytes` 和 `queuedRequestLimitBytes`，可据此区分 Runtime 内存增长和排队文件过多。

## 6. 缓存与数据目录

默认数据目录：

- Windows：`%LOCALAPPDATA%\LitematicGpuAgent`
- Linux/macOS：`~/.litematic-gpu-agent`

可用环境变量指定目录：

```bash
LITEMATIC_GPU_AGENT_HOME=/opt/litematic-gpu-agent java -jar litematic-gpu-agent-0.4.4-all.jar --web
```

统一图片缓存结构：

```text
litematic-renderer-cache/
  index.json5
  <投影 SHA-256>/
    原始投影文件.litematic
    isometric.png
    isometric-reverse.png
    six-faces.png
    about.json5
```

图片分辨率、视角、缩放、亮度、超采样、资源包、夜视配置或工具版本变化时会重新生成图片。内置 Minecraft Runtime 另有自己的网格缓存，用于减少重复建模时间；它不会替代图片缓存。

## 7. 常见问题

### 运行时间长后无响应

先查看桌面状态栏或 Web `/api/status`：

- 队列字节数接近上限：等待当前任务完成或调高 `maxQueuedRequestBytes`；
- 内存超过阈值：等待日志出现“Minecraft GPU 运行时已完全退出”；
- Runtime 仍显示存活但没有进度：查看 `minecraft-runtime.log`，确认是否为 Minecraft/驱动崩溃；
- 多次回收后进入冷却：检查资源包、显卡驱动和投影是否导致 Runtime 持续增长。

### 内置客户端没有真正退出

0.4.4 会使用 Windows `taskkill /T /F` 回收整棵进程树，并在启动新客户端前等待旧 Java/Fabric/GLFW 进程退出。若仍有残留，请导出日志包并检查是否有外部启动的 Minecraft 进程；外部进程不属于 Agent 的进程树。

### 首次渲染较慢

首次运行需要下载运行时、加载资源包并构建模型/网格。后续相同投影会优先命中图片缓存；未命中图片缓存但网格缓存有效时，可以跳过部分建网格工作。

## 8. 构建

在 `gpu-render-agent` 目录执行：

```powershell
.\gradlew.bat clean test fatJar
```

Windows 便携包：

```powershell
.\package-windows.ps1 -JavaHome 'C:\Program Files\Java\jdk-25.0.3'
```

本版本正式交付文件：

```text
litematic-gpu-agent-0.4.4-all.jar
litematic-gpu-agent-0.4.4-windows-x64.zip
```

Minecraft 专有文件和平台 native 库不打入 JAR，由程序首次运行时从官方服务下载。
