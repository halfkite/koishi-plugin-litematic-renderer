# Litematic GPU 渲染 使用教程

QQ 群里发一个 `.litematic` 投影文件，机器人自动渲染并发送正/反两张二轴测图。

整套系统由两部分组成：

| 组件 | 说明 |
|---|---|
| koishi-plugin-litematic-renderer | Koishi 插件：识别文件、排队渲染、发送结果 |
| Litematic GPU Agent | Windows 工具：真实 GPU 渲染（推荐），无需启动 Minecraft 客户端 |

两个渲染引擎二选一：

| 引擎 | 说明 |
|---|---|
| gpuAgent | 独立 GPU Agent（推荐），插件当队列，工具出图 |
| standalone | 插件内置独立 Java 渲染器，不装工具也能用 |

---

## 一、安装插件

1. Koishi 控制台 → 插件市场 → 搜索 `litematic-renderer` → 安装
2. 或离线安装：下载 `koishi-plugin-litematic-renderer-x.x.x.tgz`，在实例目录执行

```
npm install 路径\koishi-plugin-litematic-renderer-0.7.18.tgz
```

> 实例由 yarn 管理时，请把 tgz 路径写进实例 package.json 的 dependencies 再执行 yarn install。

---

## 二、接入 QQ 官方机器人

1. 到 [q.qq.com](https://q.qq.com) 创建机器人，拿到 **AppID** 和 **AppSecret**
2. 在开放平台「开发设置」里把 **Koishi 所在机器的公网 IP** 加入 IP 白名单（QQ 官方要求固定出口 IP）
3. Koishi 安装 `@koishijs/plugin-adapter-qq`，配置：

| 字段 | 值 |
|---|---|
| id | 机器人 AppID |
| secret | 机器人 AppSecret |
| intents | USER_MESSAGE |
| protocol | websocket |

4. 启动适配器，日志出现 `connect to server: wss://api.sgroup.qq.com/websocket` 即接入成功
5. 把机器人拉进群，群里发 `.litematic` 文件即可触发渲染

常见问题：QQ 群里机器人收不到消息 → 检查开放平台「沙箱环境」是否关闭、机器人是否已发布上线、消息意图是否订阅。

---

## 三、安装 GPU Agent 工具（推荐）

1. 到 [Releases](https://github.com/halfkite/koishi-plugin-litematic-renderer/releases) 下载 `litematic-gpu-agent-x.x.x-windows-portable-full.zip`
2. 解压到任意目录，运行 `Litematic GPU Agent.exe`（自带完整 Java 运行时，需要 NVIDIA 显卡）
3. 首次渲染会自动从 Mojang / Fabric 官方服务下载 Minecraft 26.2 运行时（约几分钟，仅一次），之后常驻复用

### 连接插件

| 位置 | 字段 | 说明 |
|---|---|---|
| 插件「GPU Agent v2」 | gpuAgentEnabled | 开 |
| | gpuAgentListenPort | 39181（本机部署默认） |
| | gpuAgentNodes | 添加节点：agentId + 共享密钥（至少 32 位随机字符） |
| 工具「连接设置」 | WebSocket 地址 | `ws://127.0.0.1:39181/litematic-renderer/agent/v2` |
| | Agent ID / 共享密钥 | 与插件节点配置一致 |

工具与插件分属两台电脑时，把 `127.0.0.1` 换成插件所在机器的 IP；连接由工具主动发起，工具侧无需公网。公网明文 `ws://` 不加密投影内容，能上 TLS 就用 `wss://`。

连接成功后：工具日志显示「已连接云端，认证成功」，插件日志显示 `GPU Agent connected`。

---

## 四、日常使用

| 操作 | 说明 |
|---|---|
| 群里发 `.litematic` 文件 | 自动渲染并发送（官方机器人合成一张总览图） |
| `litematic.render <文件直链>` | 命令渲染可下载的 URL |
| 私聊发文件 | 需开启 `allowPrivateRender` |
| 管理员 `litematic.cache.clear` | 清理插件缓存 |

单张图按工具设置的分辨率渲染；官方机器人会把正反两图横向拼接成一张发送。

---

## 五、群白名单 / 黑名单

在插件「发送设置」里，两套名单各有独立开关：

| 配置 | 说明 |
|---|---|
| groupWhitelistEnabled | 开启后只有白名单内的群可以渲染 |
| groupWhitelist | 群 ID 列表 |
| groupBlacklistEnabled | 开启后黑名单内的群始终不渲染（优先级高于白名单） |
| groupBlacklist | 群 ID 列表 |

群 ID 怎么拿：向未授权的群发送 `.litematic` 文件，机器人会回复本群 ID，复制进名单即可。官方 QQ 的群 ID 是开放平台哈希，不是 QQ 群号。

---

## 六、工具功能

| 功能 | 位置 |
|---|---|
| 渲染分辨率（宽/高，应用到全部视角） | 本地渲染页「分辨率」 |
| 视角表（数量/角度/缩放，本地与云端任务共用） | 本地渲染页列表 |
| 并行渲染数（1-4，默认 1） | 连接设置 |
| 内存重启阈值（默认 4GB，0=关闭） | 连接设置 |
| 群/私聊文件大小上限 | 插件「渲染设置」maxFileSize / privateMaxFileSize |
| 缓存目录、是否保存投影、容量上限 | 连接设置 |
| 导出诊断日志包 | 日志页「导出日志」 |

并行渲染：数量 ≥2 时每个任务用独立隐藏 Minecraft 客户端同时渲染，网格缓存自动共享；显存吃紧就调回 1。

内存保护：工具 + 全部渲染客户端的物理内存超过阈值后，停止接收新任务，手头任务完成后自动重启工具释放内存；10 分钟内重启超 3 次自动冷却 30 分钟。

---

## 七、缓存与渲染记录

默认缓存结构（`cacheDirectory` 可自定义）：

```
缓存根\
  index.json                        哈希→文件夹映射，同内容投影复用
  <工具版本>\<投影名-首次渲染时间>\
      投影原文件.litematic
      正-<时间>.png / 反-<时间>.png
      记录.json5                     每次渲染一条：时间/群号/发送人/分辨率/大小/哈希/图片名/来源
```

同一次渲染的图片按工具分辨率保存，不重复缩放；同哈希投影不重复保存文件。

---

## 八、常见问题

| 问题 | 处理 |
|---|---|
| 群里发文件没反应 | 插件是否启用；机器人沙箱是否关闭；消息意图是否订阅 |
| 工具日志刷「连接云端失败」 | 插件 GPU Agent v2 是否启用、端口/密钥是否一致 |
| 首次渲染很慢 | 正常：首次需构建网格缓存，同投影再次渲染秒出 |
| 发送失败 / 提示白名单 | QQ 开放平台 IP 白名单没加 Koishi 机器的公网 IP |
| 渲染结果缺方块 | 查看「日志」页或导出诊断包，缺失方块会记录在诊断里 |
| 任务一多个别失败 | 调大并行数前先确认显存充足；或提高内存阈值观察 |

---

## 九、排查与日志

| 日志 | 位置 |
|---|---|
| 工具运行日志 | 数据目录 `agent-gui.log`（自动落盘，日志页可一键导出诊断包） |
| 回传调试 | 数据目录 `send-debug.log` |
| 缓存异常 | 数据目录 `cache-debug.log` |
| 插件日志 | Koishi 控制台日志页 |
| 渲染诊断 | 插件 `diagnosticsFilePath`，或 `/litematic-renderer/diagnostics` 导出 |

跨设备求助时，用「日志 → 导出日志（诊断包）」打包全部关键日志发出去即可（配置文件中的密钥会自动脱敏）。
