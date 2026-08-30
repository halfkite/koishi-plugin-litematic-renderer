# koishi-plugin-litematic-renderer

[![npm](https://img.shields.io/npm/v/koishi-plugin-litematic-renderer?label=npm)](https://www.npmjs.com/package/koishi-plugin-litematic-renderer)
[![教程](https://img.shields.io/badge/文档-使用教程-blue)](./TUTORIAL.md)

Koishi 的 Minecraft Litematica 投影渲染插件：QQ 群里发 `.litematic` 文件，自动渲染并发送正/反两张二轴测图（官方 QQ 合成一张总览图）。

**📖 完整使用教程见 [TUTORIAL.md](./TUTORIAL.md)**（安装、QQ 官方机器人接入、GPU Agent 配置、白名单、常见问题）

## 渲染引擎（二选一）

| 引擎 | 说明 |
|---|---|
| gpuAgent | 独立 GPU Agent 工具渲染（推荐）：真实 GPU 出图，插件当队列，支持并行渲染、内存保护、渲染记录 |
| standalone | 插件内置独立 Java 渲染器：无需额外工具，支持 blockstate/模型/实体纹理与自定义材质包 |

## 主要特性

- 群文件自动识别渲染，支持私聊（可开关）与 `litematic.render <直链>` 命令
- 群白名单 / 黑名单（独立开关，黑名单优先）
- 群聊与私聊独立的文件大小上限
- 渲染分辨率与视角由 GPU Agent 工具接管，本地与云端任务统一
- 多客户端并行渲染（1-4），网格缓存共享；内存超阈值自动完成任务后重启
- 缓存按版本 + 投影哈希分目录，同内容不重复存储；中文 JSON5 渲染记录（时间/群号/发送人/分辨率/哈希）
- 渲染诊断持久化，可导出错误报告与诊断日志包

## 快速开始

```yaml
# 插件「GPU Agent v2」
renderEngine: gpuAgent
gpuAgentEnabled: true
gpuAgentListenPort: 39181
gpuAgentNodes:
  - agentId: windows-gpu-1
    sharedSecret: 请填写至少32字符的独立随机密钥
```

Windows 工具从 [Releases](https://github.com/halfkite/koishi-plugin-litematic-renderer/releases) 下载便携包（自带 Java 运行时），连接地址填 `ws://插件所在IP:39181/litematic-renderer/agent/v2`。

详细步骤 → [TUTORIAL.md](./TUTORIAL.md)

## 命令

| 命令 | 说明 |
|---|---|
| `litematic.render <文件直链>` | 渲染可直接下载的 `.litematic` |
| `litematic.cache.clear` | 清理渲染缓存（管理员） |

## License

MIT
