# 0.6.0 云端 Koishi + 本机 GPU Agent 部署

## 架构

```text
QQ 官方适配器 -> 云端 Koishi -> frpc visitor -> FRP STCP -> 本机 frpc provider -> 127.0.0.1:39080 Agent -> Minecraft GPU 客户端
```

云端 Koishi 下载 `.litematic`，以 HMAC 签名请求发送给本机 Agent；Agent 使用本机 QuickCraft GPU bridge 渲染，返回两张 PNG；云端 Koishi 最终通过 QQ 官方适配器发送图片。

Agent 与 FRP visitor 的端口不得暴露到公网。云端只需开放 `frps` 的 `7000/TCP`。

## 1. 生成三个不同密钥

在可信机器运行三次：

```powershell
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
```

分别用于 FRP `auth.token`、STCP `secretKey`、Agent `sharedSecret`。不要复用，也不要写入 Git 或日志。

## 2. 本机：Minecraft、Agent 与 FRP provider

1. 启动 Minecraft 26.2-Fabric GPU 客户端，确认 `quickcraft-render-bridge/status.json` 在五秒内更新。
2. 将 `agent.config.example.json` 复制为 `agent.config.json`，填写游戏目录和 Agent 密钥。
3. 启动 Agent：

```powershell
node remote-render-agent.js --config agent.config.json
```

4. 将 `frpc-provider.toml.example` 复制为 `frpc-provider.toml`，填写 FRP token 与 STCP 密钥，启动：

```powershell
frpc.exe -c frpc-provider.toml
```

## 3. 云端：FRP 服务端与 visitor

`frps.toml`：

```toml
bindPort = 7000

[auth]
method = "token"
token = "第一个密钥"
```

将 `frpc-visitor.toml.example` 复制为 `frpc-visitor.toml`，填写相同 FRP token 与 STCP 密钥。用 `docker-compose.fragment.yml` 添加 `frps` 与 `frpc-visitor` sidecar。

不要为 `frpc-visitor` 添加 Docker `ports:`。Koishi 仅在 Docker 网络中访问：

```text
http://frpc-visitor:39080/v1/render
```

## 4. 云端 Koishi 配置

```yaml
renderEngine: remoteAgent
remoteAgentUrl: http://frpc-visitor:39080/v1/render
remoteAgentSecret: 第三个密钥
remoteAgentTimeout: 240000
remoteAgentClockSkewSeconds: 90
```

保留既有 QQ 官方适配器与 `koishi.yml` 其他配置。云端与本机必须启用 NTP。

## 5. 验证

1. 检查两端 `frpc` 日志，确认 STCP 已连通。
2. 容器内请求 `http://frpc-visitor:39080/v1/render` 应返回 `404`，说明通道联通且无签名请求被拒绝。
3. 在 QQ 群上传 `.litematic`；本机应创建 GPU bridge job，云端随后回发 PNG。

常见错误：`401 request timestamp` 表示需校时；`401 invalid request signature` 表示 Agent 密钥不一致；GPU client not running 表示 Minecraft bridge 未刷新；QQ 图片 `Bad Request` 是官方 QQ 发送接口问题，不是 FRP/GPU 通道问题。
