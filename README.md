# koishi-plugin-litematic-renderer

[![npm](https://img.shields.io/npm/v/koishi-plugin-litematic-renderer?label=npm)](https://www.npmjs.com/package/koishi-plugin-litematic-renderer)

一个面向 Koishi 的 Minecraft Litematica 投影渲染插件。它会自动识别 QQ 群消息中的 `.litematic` 文件，渲染并发送两张 PNG：

- 正二轴测
- 反向正二轴测（在基准角度上旋转 180°）

支持合并转发或普通消息发送，并附带投影元数据：保存者游戏 ID、创建时间、方块数/体积、尺寸、Litematic 版本和游戏数据版本。合并转发完成后，会单独发送一条渲染成功提示；QQ 官方机器人不支持 OneBot 合并转发节点和单消息多媒体，因此会把各视图拼成一张总览图，与投影信息和成功提示作为一条消息发送。

默认使用 `standalone` 独立 Java 渲染器，只读取 Litematic、插件内置的 Minecraft 26.2 原版资源和可选材质包，不要求启动 Minecraft 客户端、进入世界或另行下载客户端 JAR。推荐 Java 21+；Java 可执行文件支持自动查找或自定义路径。材质包越靠后优先级越高。

## 独立 Java 渲染器

内置 JAR 支持标准资源包的 blockstate variants/multipart、模型继承、元素旋转、纹理覆盖与透明纹理，并能从游戏资源读取箱子、潜影盒、木牌、头颅和旗帜的实体纹理与专用几何。玩家头颅会读取 Litematic 中的皮肤属性，并且只允许从 Mojang 的 `textures.minecraft.net` 获取皮肤；旗帜支持新旧 NBT 的底色与图案叠加。尚未适配的特殊方块实体会明确显示缺失纹理，不会借用其他方块伪装；需要覆盖全部客户端实体渲染器时仍可切换到 `java` Fabric 桥接后端。

## Java 渲染桥

旧版 Minecraft 26.2 GPU 客户端后端继续兼容插件内置的 `quickcraft-mc26.2-1.0.5-renderbridge.jar`。新部署建议使用独立 **Litematic GPU Agent**：它拥有自己的 Minecraft 26.2/Fabric 运行目录，首次启动从 Mojang 与 Fabric 官方服务下载所需文件，不读取现有 `.minecraft`，不要求账号登录，也不会显示游戏窗口。

## 独立 GPU Agent

Windows 程序产物位于 `gpu-render-agent/build/distributions`，安装程序位于 `gpu-render-agent/build/jpackage`。首次本地渲染会下载 Minecraft 26.2 客户端、资源对象和 Fabric Loader；完成后运行时常驻并复用模型网格和 GPU 上下文。程序支持拖放 `.litematic`、任意 yaw/pitch/zoom、独立宽高、批量视角、透明背景、超采样、任务历史、托盘和资源包排序。开机启动默认关闭。

Agent 的资源包列表越靠后优先级越高。点击“应用并重载”后，程序会在空闲时停止隐藏运行时、写入按内容哈希命名的资源包集合并重新启动；如果重载失败，会恢复上一份 `options.txt` 和工作资源包配置。容器的 `Items`、战利品表等不可见 NBT 不进入动态渲染缓存，告示牌、旗帜、头颅等外观数据仍保留。

### WebSocket v2

Koishi 中选择 `gpuAgent`，设置：

```yaml
renderEngine: gpuAgent
gpuAgentEnabled: true
gpuAgentListenHost: 0.0.0.0
gpuAgentListenPort: 39180
gpuAgentPath: /litematic-renderer/agent/v2
gpuAgentTimeout: 240000
gpuAgentFallback: true
gpuAgentNodes:
  - agentId: windows-gpu-1
    sharedSecret: 请填写至少32字符的独立随机密钥
    enabled: true
```

Windows Agent 中填写相同的 `agentId` 和密钥，云端地址填写 `ws://服务器IP:39180/litematic-renderer/agent/v2` 或对应的 `wss://` 地址。连接由 Agent 主动发起，因此本地动态 IP 不需要 FRP。公网 `ws://` 不加密投影与图片，能使用 TLS 时应选择 `wss://`。

多个节点按“能力匹配、空闲、队列最短、最久未分配”调度，每个节点一次只运行一个 GPU 任务。节点离线、超时、纹理尺寸超限或运行时崩溃时，`gpuAgentFallback: true` 会自动调用现有独立 Java 渲染器。节点上报的运行时版本与资源包指纹参与插件缓存哈希，资源包变化后不会复用旧图。

### HTTP v1 兼容

旧配置可继续选择 `remoteAgent` 并使用 `remoteAgentUrl`、`remoteAgentSecret` 和 `remoteAgentTimeout`。Windows Agent 默认在 `127.0.0.1:39080/v1/render` 提供相同的时间戳、nonce、HMAC-SHA256 协议；原有 `remote-agent/remote-render-agent.js` 与 FRP 配置也继续随 npm 包发布。

主菜单下完全不建立世界时，Minecraft 26.2 不会提供完整的动态注册表、维度、群系染色和方块实体渲染环境，因此真实客户端渲染仍需要一个最小 `ClientLevel`。内置虚空世界是为此保留的最小运行环境。

将 `litematic-render-bridge-0.1.2.jar` 放入 `1.21.1-Fabric/mods`。机器人运行期间需要保持该 Minecraft 客户端打开并进入任意世界。桥接模组会在游戏目录的 `render-bridge` 下建立任务队列，并在隔离的原理图世界中渲染，不会添加或修改玩家世界中的方块。

当前配置要求同时启用以下资源包，且附加包应位于基础包上方：

1. `XeKr红显3.6forMC1.20.2~1.21.5.zip`
2. `XKRDA红显附加包0.3for1.19.4~1.21snapshot.zip`

Java 桥使用 Isometric Renders 的正交投影、旋转和光照流程，并以 Fabrishot 的高分辨率帧缓冲思路进行超采样后再缩小保存。

## 配置示例

```yaml
plugins:
  litematic-renderer:
    # 机器人接入
    qqBotType: official
    patchOneBotGroupUpload: true
    # 默认关闭；需要固定出口 IP 时再改为 ssh 或 proxy
    officialProxyMode: disabled
    officialProxyUrl: ''
    sshProxyExecutable: ssh
    sshProxyHost: ''
    sshProxyPort: 22
    sshProxyUser: root
    sshProxyPrivateKey: ''
    sshProxyPassword: ''
    sshProxyLocalPort: 1080

    # 渲染设置
    renderEngine: standalone
    gpuClientGameDirectory: ''
    maxFileSize: 1024
    outputSize: 1024
    background: '#000000'
    transparentBackground: false
    isometricFill: 0.78
    isometricRotation: 135
    isometricSlant: 36

    # 发送设置（official 始终为一张总览图；发送模式和标题仅 selfHosted 使用）
    allowPrivateRender: false
    sendAsForward: false
    showViewTitles: false
    replyAndMention: false
    sixFaceOverview: true
    sixFaceLayout: horizontal
    groupSendOptions: []

    # 独立渲染器
    javaPath: ''
    minecraftJarPath: ''
    resourcePackPaths: []
    standaloneRendererJar: ''
    standaloneRenderTimeout: 180000
    standaloneJavaMaxHeapMb: 200
    standaloneJavaRetryMaxHeapMb: 2048
    standaloneJavaMemoryRestartLimit: 1
    javaSupersampling: 1

    # 缓存与诊断
    renderTimeout: 30000
    cacheDirectory: data/litematic-renderer-cache
    cacheMaxSizeGb: 20
    diagnosticsFilePath: data/litematic-renderer-diagnostics.json

    # 高级设置
    isometricCellSize: 7
    javaRenderTimeout: 180000
    webglQuality: high
    webglWidth: 800
    webglHeight: 600
    isometricSquare: true
    javaBridgeDirectory: ''
    gpuRendererCommand: ''
```

`qqBotType` 位于配置页开头。选择 `official` 时，插件会将各视图合成一张总览图并通过 QQ 官方接口发送；官方群消息仅引用原消息，不发送无法正确解析的 OpenID @ 文本。选择 `selfHosted` 时，插件保留 OneBot/NapCat 合并转发、引用和 @ 行为；为兼容旧安装，未填写时默认使用 `selfHosted`。

`allowPrivateRender` 默认关闭。开启后，插件会在单人对话中自动识别 `.litematic` 附件，也允许在单人对话中执行 `litematic.render <文件 URL>`；关闭时只处理群聊和频道消息。私聊没有群号，因此不会应用 `groupSendOptions`，而是使用全局发送设置。

QQ 官方平台要求固定数字出口 IP 时，可将 `officialProxyMode` 设为 `ssh`。插件会在 `127.0.0.1:sshProxyLocalPort` 建立 SOCKS5 隧道，断线后每 5 秒自动重连，并让 Koishi 的 HTTP API 与 WebSocket 共用该出口；QQ 白名单填写中转服务器公网 IP。`sshProxyPrivateKey` 和 `sshProxyPassword` 均为选填，但至少填写一项：填写密钥时优先使用系统 OpenSSH，密钥为空时使用插件内置的密码 SSH 客户端。密码在控制台中遮罩显示，不会进入命令行或日志。Docker 使用密钥时需将其只读挂载进容器，并确保容器内已安装 `ssh`。若已有 SOCKS5/HTTP 代理，选择 `proxy` 并填写 `officialProxyUrl` 即可。Koishi 的 `proxy-agent` 插件必须保持启用（标准 Koishi 安装默认已启用）。

启用后可执行管理员命令 `litematic.proxy.check`，返回值应为中转服务器 IP；也可在 Koishi 所在环境执行 `curl --proxy socks5h://127.0.0.1:1080 https://ifconfig.me/ip` 验证。FRP 只负责入站端口转发，不能替代此出站代理。该代理作用于 Koishi 全局出站请求，因此投影下载等请求也会走中转。

配置页底部的“独立渲染器”“缓存与诊断”和“高级设置”默认折叠，需要时点击标题展开。插件不再按投影声明的方块数或区域体积拒绝渲染；文件大小仍由 `maxFileSize` 控制，实际渲染资源仍受 Java 堆内存和超时配置约束。

`javaPath` 留空时会自动查找 Java 21 或更高版本；推荐使用 Java 21+，也可以在控制台文件选择器中选择 Java 可执行文件。旧版 `standaloneJavaCommand` 会继续作为隐藏兼容字段读取。首次独立渲染默认将 Java 堆限制为 200 MiB；如果发生 OOM，插件会退出该进程并按 `standaloneJavaRetryMaxHeapMb`（默认 2048 MiB）启动全新进程重试。正反视图按顺序渲染，降低峰值内存；每次任务结束 Java 进程都会退出。无法正常解析的方块会写入 `diagnosticsFilePath`，可使用 `litematic.errors.export` 或 `/litematic-renderer/diagnostics` 导出。
插件卸载、重载或 Koishi 关闭时，会终止所有由本插件启动且仍在运行的独立渲染 Java 进程树；渲染超时也使用同一回收机制，不会影响 Minecraft 客户端或其他 Java 程序。
普通 `minecraft:chain` 已补充 26.2 模型兼容处理：按 `axis=x/y/z` 旋转链模型，不再因为原版资源包缺少普通链 blockstate 而退回完整立方体。

`minecraftJarPath` 是兼容旧配置的可选基础资源路径；留空、文件不存在或无需固定其他游戏版本时，独立渲染器直接使用插件内置的 26.2 原版资源包。`resourcePackPaths` 提供专用列表控件：点击“上传材质包”选择 ZIP，点击列表行后可用“上移”“下移”调整顺序，越靠后优先级越高。上传文件保存在 Koishi 持久目录 `data/litematic-resource-packs`，按内容哈希命名以避免误覆盖；单个文件上限为 256 MB。使用 `java` Fabric 桥接后端时，请填写 `javaBridgeDirectory`。

高级设置按后端区分：`isometricCellSize` 仅控制 CPU 回退渲染的方块尺寸；`webglQuality`、`webglWidth`、`webglHeight` 和 `isometricSquare` 仅用于 WebGL 后端；`javaRenderTimeout` 同时用于 Minecraft GPU 和旧 Fabric 桥，`javaBridgeDirectory` 仅用于旧 `java` 后端；`gpuClientGameDirectory` 用于自动安装和连接 26.2 GPU 渲染端；`gpuRendererCommand` 是可选外部 GPU 程序。

缓存按插件版本、投影文件 SHA-256 和渲染配置分目录，同时保存原始 `.litematic` 与两张轴测 PNG。合并转发启用六面图时，会按需补充缓存 `six-faces.png`，已有轴测图不会重新渲染。插件升级后使用新的版本目录，旧缓存不会因升级被主动删除；所有版本合计超过 `cacheMaxSizeGb` 时才按最久未使用顺序清理。

`outputSize` 是统一的图像清晰度，控制独立 Java、Fabric Java、CPU 和六面图的最终输出边长；旧版 `javaResolution` 仅保留为隐藏兼容字段，不再覆盖主配置。`isometricRotation` 控制第一张图的方向，第二张图始终自动增加 180°。`isometricSlant: 36` 对应 Isometric Renders 的正二轴测预设。参考 Minecraft 客户端 GPU 预览的同分辨率直出方式，`javaSupersampling` 默认改为 `1`；在 3000 像素输出下可显著减少时间和内存。WebGL 后端会使用 Chrome GPU，但部分透明材质与独立 Java 的真实资源渲染存在差异，因此不自动切换。

`sendAsForward` 是未配置群覆盖时的默认发送方式：`true` 为合并转发，`false` 为联合发送。`sixFaceOverview` 默认开启，仅在最终发送方式为合并转发时生成一张带中文方向标签的六面正交合成图；方向标签使用插件内置的 16×16“上、下、东、南、西、北”中文字形，不依赖容器字体。`sixFaceLayout` 可选择横向 `3×2` 或纵向 `2×3`，合成图最长边由 `outputSize` 控制。`standalone` 后端使用真实方块模型和材质渲染六个正交视图；其他后端或独立六面渲染失败时自动使用轻量方块颜色投影，不影响原有两张轴测图。

联合发送会把两张图片、投影信息和“投影名 已渲染成功，结果如上”放在同一条普通消息中。合并转发会先发送转发内容，再单独发送成功通知。`official` 模式始终把两张轴测图并排放在上方、六面图放在下方，合成为一张 `qq-overview.png`，并与投影信息和成功通知作为同一条消息发送。`replyAndMention` 在官方模式中只控制引用，在自建模式中控制引用并 @ 投影发送者；`groupSendOptions` 可按群号覆盖发送方式和回复设置，相同群号以最后一项为准。

可用命令 `litematic.render <文件 URL>` 手动渲染直链文件。管理员可执行 `litematic.cache.clear` 清理缓存。

`showViewTitles` 默认关闭，发送图片时不再附带“正二轴测”和“反向正二轴测（旋转 180°）”标题；打开后恢复这两行文字。`diagnosticsFilePath` 同时保留每次渲染目录中的 `render-diagnostics.json` 和一个全局汇总文件，方块问题写入 `blocks`，渲染失败写入 `errors`；可通过 `/litematic-renderer/diagnostics` 下载错误报告。缓存目录名会保留投影文件名（包括中文）。
附件大小超过 `maxFileSize` 时会直接回复“文件大小超过 X MB，无法渲染”，不会静默跳过。
插件版本更新时，旧的全局诊断会自动按旧版本和日期移动到 `data/litematic-renderer-diagnostics-archive`；管理员执行 `litematic.errors.disable` 后，当前快照会移动到 `data/litematic-renderer-diagnostics-disabled`，活动文件会重新开始统计。
独立渲染器支持 `item_frame` 和 `glow_item_frame` 实体，会绘制展示框及其 `Item.id` 指定的物品纹理。
`maxFileSize` 的配置单位为 KB，默认 `1024` 即 1 MB；超过限制会回复文件大小和“无法渲染”提示。
渲染失败消息不会显示本机文件路径；详细路径仅保留在 Koishi 日志和诊断文件中。
自动处理群文件时，渲染失败或文件超限会回复原消息；自建 QQ 模式还会 @ 发文件的用户。

## 降级后端

`webgl` 使用内置 Deepslate 方块模型与纹理，`cpu` 生成简化方块预览；两者的主渲染仍输出正反两张正二轴测图，合并转发的六面图使用插件内置的方块颜色投影。`standalone` 后端会直接生成真实模型和材质的六面图。Java 桥不可用时会明确报错，不会静默发送材质不一致的图片。

## 参考实现

Java 渲染桥基于以下 MIT 项目的公开渲染流程进行集成：

- Isometric Renders by glisco
- Fabrishot by Ramid Khan
