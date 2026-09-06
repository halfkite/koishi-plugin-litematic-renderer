# Litematic GPU Agent 0.4.4

跨平台可执行 JAR 与 Windows x64 便携 GPU Agent，用于 Minecraft 26.2 / Fabric 0.19.3 投影渲染。

## 运行

JAR 需要目标机器安装 Java 25：

```text
java -jar litematic-gpu-agent-0.4.4-all.jar
java -jar litematic-gpu-agent-0.4.4-all.jar --version
java -jar litematic-gpu-agent-0.4.4-all.jar --help
java -jar litematic-gpu-agent-0.4.4-all.jar --web
java -jar litematic-gpu-agent-0.4.4-all.jar --bot
```

也可以直接双击 `start-agent.bat` 启动桌面工具；把 `--web` 或 `--bot` 作为参数传给脚本即可启动对应模式。Linux/macOS 可执行 `./start-agent.sh --web`，脚本会自动查找 Java 和同目录 JAR。

Windows 用户也可以解压 `litematic-gpu-agent-0.4.4-windows-x64.zip` 后直接启动 `Litematic GPU Agent/Litematic GPU Agent.exe`；该包已经内置 Java 25 Runtime，不需要安装 Java。首次渲染时，程序会从官方地址下载 Minecraft 26.2 客户端、资源和 Fabric Loader，不需要登录账号，也不使用已有游戏目录。

### Linux Web 后台

Linux 无桌面环境直接运行 `--web`，默认访问 `http://服务器IP:2618/`：

```text
java -jar litematic-gpu-agent-0.4.4-all.jar --web
```

首次启动会在终端和 `web-credentials.txt` 输出随机 12 位数字密码，用户名默认是 `admin`。网页可配置多个官方 QQ 和 OneBot/NekoBot 账号、视角、亮度、拼接、缓存、资源包和云端连接。群消息模式有“接收群文件自动识别（不要求 @）”“仅被 @ 时识别”和“都可以（文件或 @）”三种选择。官方 QQ 与 OneBot 账号会在 Agent 内直接连接，不依赖 Koishi；OneBot 支持正向 WebSocket 与反向 WebSocket。`--bot` 只启动机器人和渲染服务，不启动网页。

桌面 GUI 无参数启动时默认不打开网页。进入“连接设置”，勾选“启用 Web 管理后台”即可直接启动；同一页也可以填写两次新 Web 密码，保存后立即更新密码和 `web-credentials.txt`，留空则保持原密码不变。网页和本地 GUI 的“投影信息”设置共用同一份 `agent.json`，可以分别控制投影名称、保存者游戏 ID、创建时间、方块数/体积、尺寸、Litematic 版本和游戏版本是否发送。账号、云端开关、视角、亮度、拼接、投影信息和内存阈值保存后即时应用；资源包会事务式重载。缓存目录、并行客户端数、Java 路径和 Web 监听地址/端口需要重启。

可以创建 `/etc/systemd/system/litematic-gpu-agent.service`：

```ini
[Unit]
Description=Litematic GPU Agent
After=network-online.target

[Service]
WorkingDirectory=/opt/litematic-gpu-agent
ExecStart=/usr/bin/java -jar /opt/litematic-gpu-agent/litematic-gpu-agent-0.4.4-all.jar --web
Restart=on-failure
User=litematic

[Install]
WantedBy=multi-user.target
```

默认监听 `0.0.0.0`，公网部署请使用防火墙和 HTTPS 反向代理或 VPN，不要直接暴露管理端口。

图形界面的“本地渲染”页支持一次选择多个 `.litematic` 文件，也支持把多个文件直接拖到“导入投影”按钮或投影列表；点击“开始渲染”后会按列表逐个输出。该页的“视角表”同时决定本地渲染和云端任务回传的视角数量、角度、缩放、亮度与分辨率，亮度范围为 `25%`–`300%`，`100%` 为该视角基准亮度；底视图以原来的 `150%` 实际亮度作为新的 `100%`，其他视角 `100%` 保持原始亮度。云端合成图可选择横向或竖向拼接。

## 缓存

默认缓存目录为数据目录下的 `litematic-renderer-cache`，也可以在设置中指定为 Koishi 使用的同一目录：

```text
litematic-renderer-cache/
  index.json5
  <投影 SHA-256>/
    原始投影文件名.litematic
    isometric.png
    isometric-reverse.png
    six-faces.png
    about.json5
```

图片分辨率、视角、缩放、亮度、超采样、材质包、夜视或有效工具版本变化时会重新渲染。`about.json5` 保存每个视角的实际亮度、亮度百分比和“出图配置识别数”；缓存读取会同时核对识别数和视角参数。文件使用严格 JSON 子集编写，扩展名保留为 JSON5，方便 Node、Java 和文本编辑器共同读取；哈希目录和图片仍可直接手动打开。

“本地渲染”页可控制本地拼接、拼接方向、缓存目录、并行客户端、空闲关闭和内存重启阈值；夜视配置字段仍保留在配置文件中，但当前界面暂时隐藏，方便以后恢复。本地拼接开启后，每个投影结果目录会额外生成 `merged.png`，各视角原图仍会保留；拼接方向同时用于本地拼接和云端多视角结果。修改后点击“保存渲染设置并重启”，让内置 Minecraft 客户端加载新设置。连接地址、共享密钥和托盘/开机选项仍在“连接设置”页。

## 构建

```text
gradlew.bat test fatJar
.\package-windows.ps1 -JavaHome "C:\Program Files\Java\jdk-25.0.3"
```

0.4.4 发布文件为 `litematic-gpu-agent-0.4.4-all.jar` 和 `litematic-gpu-agent-0.4.4-windows-x64.zip`，不生成安装器、Linux/macOS 包或 npm 包。完整操作说明见 [USAGE-0.4.4.md](./USAGE-0.4.4.md)。

运行时数据默认位于 Windows 的 `%LOCALAPPDATA%\LitematicGpuAgent`，Linux/macOS 使用用户数据目录。设置 `LITEMATIC_GPU_AGENT_HOME` 可指定便携或服务目录。

HTTP v1 默认只监听回环地址。WebSocket v2 由 Agent 主动连接云端；公网 `ws://` 会暴露传输内容，建议使用 `wss://`。
