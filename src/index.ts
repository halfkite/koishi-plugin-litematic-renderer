import { Context, h, Schema, Session } from 'koishi'
import { createHash, createHmac, randomUUID } from 'node:crypto'
import { constants, promises as fs } from 'node:fs'
import { basename, dirname, extname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { ChildProcess, spawn } from 'node:child_process'
import { createServer, Socket } from 'node:net'
import { Client as SshClient } from 'ssh2'
import { deflateSync, gunzipSync } from 'node:zlib'
import { PNG } from 'pngjs'
import { GpuAgentHub, GpuAgentNodeConfig, GpuRenderRequest, RenderView } from './gpu-agent-protocol'
import type {} from '@koishijs/plugin-console'

declare module '@koishijs/console' {
  interface Events {
    'litematic/resource-pack-upload'(filename: unknown, base64: unknown): Promise<string>
  }
}

export const name = 'litematic-renderer'
export const inject = { optional: ['puppeteer', 'server', 'console'] }
const CACHE_FORMAT_VERSION = 16
const RESOURCE_PACK_UPLOAD_LIMIT = 256 * 1024 * 1024
const GPU_CLIENT_JAR_NAME = 'quickcraft-mc26.2-1.0.5-renderbridge.jar'
const packageVersion = (require('../package.json') as { version?: unknown }).version
const PLUGIN_VERSION = typeof packageVersion === 'string' ? packageVersion : 'unknown'

export interface Config {
  qqBotType: QqBotType
  officialProxyMode: OfficialProxyMode
  officialProxyUrl: string
  sshProxyExecutable: string
  sshProxyHost: string
  sshProxyPort: number
  sshProxyUser: string
  sshProxyPrivateKey: string
  sshProxyPassword: string
  sshProxyLocalPort: number
  javaPath: string
  standaloneJavaCommand?: string
  minecraftJarPath: string
  resourcePackPaths: string[]
  renderEngine: 'standalone' | 'gpuClient' | 'gpuAgent' | 'remoteAgent' | 'java' | 'webgl' | 'cpu'
  standaloneRendererJar: string
  standaloneRenderTimeout: number
  standaloneJavaMaxHeapMb: number
  standaloneJavaRetryMaxHeapMb: number
  standaloneJavaMemoryRestartLimit: number
  maxFileSize: number
  outputSize: number
  isometricCellSize: number
  background: string
  transparentBackground: boolean
  allowPrivateRender: boolean
  sendAsForward: boolean
  showViewTitles: boolean
  replyAndMention: boolean
  sixFaceOverview: boolean
  sixFaceLayout: SixFaceLayout
  groupSendOptions: GroupSendOption[]
  renderTimeout: number
  cacheDirectory: string
  cacheMaxSizeGb: number
  diagnosticsFilePath: string
  patchOneBotGroupUpload: boolean
  javaBridgeDirectory: string
  gpuClientGameDirectory: string
  gpuRendererCommand: string
  javaRenderTimeout: number
  javaResolution: number
  javaSupersampling: number
  webglQuality: 'standard' | 'high' | 'ultra'
  webglWidth: number
  webglHeight: number
  isometricSquare: boolean
  isometricFill: number
  isometricRotation: number
  isometricSlant: number
  gpuAgentEnabled: boolean
  gpuAgentListenHost: string
  gpuAgentListenPort: number
  gpuAgentPath: string
  gpuAgentNodes: GpuAgentNodeConfig[]
  gpuAgentTimeout: number
  gpuAgentFallback: boolean
  remoteAgentUrl: string
  remoteAgentSecret: string
  remoteAgentTimeout: number
  remoteAgentClockSkewSeconds: number
}

export type SendMode = 'forward' | 'combined'
export type ReplyAndMentionOverride = 'inherit' | 'enabled' | 'disabled'
export type SixFaceLayout = 'horizontal' | 'vertical'
export type QqBotType = 'official' | 'selfHosted'
export type OfficialProxyMode = 'disabled' | 'proxy' | 'ssh'

export interface GroupSendOption {
  groupId: string
  sendMode: SendMode
  replyAndMention: ReplyAndMentionOverride
}

export const Config: Schema<Config> = Schema.intersect([
  Schema.object({
    qqBotType: Schema.union([
      Schema.const('official').description('QQ 官方机器人'),
      Schema.const('selfHosted').description('自建 QQ（OneBot / NapCat）'),
    ]).role('radio').default('selfHosted').description('机器人接入类型；决定图片、合并转发、引用和 @ 的发送方式。'),
    patchOneBotGroupUpload: Schema.boolean().default(true).description('自建 QQ：启动时修复 OneBot 群文件上传事件；官方机器人不使用此项。'),
    officialProxyMode: Schema.union([
      Schema.const('disabled').description('关闭中转'),
      Schema.const('proxy').description('使用已有代理'),
      Schema.const('ssh').description('自动建立 SSH 中转'),
    ]).role('radio').default('disabled').description('仅官方 QQ：让 Koishi 出站请求使用固定公网 IP；SSH 模式会自动创建本机 SOCKS5 隧道。'),
    officialProxyUrl: Schema.string().default('').description('已有代理地址；支持 socks5h、socks5、http 和 https。SSH 模式会根据本地端口自动生成。'),
    sshProxyExecutable: Schema.string().default('ssh').description('SSH 可执行文件或命令；Linux 通常为 ssh，Windows 可填写 ssh.exe 的完整路径。'),
    sshProxyHost: Schema.string().default('').description('SSH 中转服务器的数字 IP，例如 203.0.113.10（IPv4 数字地址）。'),
    sshProxyPort: Schema.natural().min(1).max(65535).default(22).description('SSH 中转服务器端口。'),
    sshProxyUser: Schema.string().default('root').description('SSH 登录用户名。'),
    sshProxyPrivateKey: Schema.path({ filters: ['file'] }).default('').description('SSH 私钥路径（选填）；填写后优先使用密钥登录。Docker 需要先将密钥只读挂载进容器。'),
    sshProxyPassword: Schema.string().role('secret').default('').description('SSH 登录密码（选填）；私钥为空时使用密码登录。密钥与密码至少填写一项。'),
    sshProxyLocalPort: Schema.natural().min(1).max(65535).default(1080).description('本机 SOCKS5 监听端口；只监听 127.0.0.1，不对公网开放。'),
  }).description('机器人接入'),
  Schema.object({
    renderEngine: Schema.union([
      Schema.const('gpuAgent').description('独立 GPU Agent（主动连接）'),
      Schema.const('standalone').description('真实材质快速渲染（推荐）'),
      Schema.const('gpuClient').description('Minecraft 26.2 GPU 客户端（最快）').hidden(),
      Schema.const('remoteAgent').description('旧版 Remote Agent（HTTP / FRP）').hidden(),
      Schema.const('webgl').description('GPU WebGL（轻量，部分透明材质可能不准确）').hidden(),
      Schema.const('java').description('Fabric 客户端桥接').hidden(),
      Schema.const('cpu').description('简化方块颜色渲染').hidden(),
    ]).default('gpuAgent').description('渲染引擎；真实材质快速渲染无需启动 Minecraft 客户端。'),
    maxFileSize: Schema.natural().min(1).default(1024).description('自动处理的最大文件大小（KB）。'),
    outputSize: Schema.natural().min(256).max(4096).default(1024).description('最终输出边长（CPU 渲染时生效）。GPU Agent 模式下分辨率由 Agent 端「分辨率」设置接管，此项不生效。'),
    background: Schema.string().default('#000000').description('PNG 背景颜色。'),
    transparentBackground: Schema.boolean().default(false).description('输出透明背景。'),
    isometricFill: Schema.percent().default(0.78).description('正二轴测主体在画布中的最大占比。'),
    isometricRotation: Schema.number().min(0).max(360).step(1).default(135).description('Isometric Renders 绕竖直轴的基准旋转角。'),
    isometricSlant: Schema.number().min(-90).max(90).step(1).default(36).description('Isometric Renders 俯仰角；36° 为正二轴测预设。'),
  }).description('渲染设置'),
  Schema.object({
    allowPrivateRender: Schema.boolean().default(false).description('允许在单人对话中自动识别投影附件，并使用渲染命令；关闭时仅处理群聊和频道消息。'),
    sendAsForward: Schema.boolean().default(false).description('仅自建 QQ：开启为合并转发，关闭为联合发送；官方 QQ 忽略此项。'),
    showViewTitles: Schema.boolean().default(false).description('仅自建 QQ：发送图片时显示视图标题。'),
    replyAndMention: Schema.boolean().default(false).description('自建 QQ 会引用并 @ 发送者；官方 QQ 仅引用，避免显示 OpenID。'),
    sixFaceOverview: Schema.boolean().default(true).description('合并转发时生成并附加上、下、东、南、西、北六面正交合成图。'),
    sixFaceLayout: Schema.union([
      Schema.const('horizontal').description('横向 3×2'),
      Schema.const('vertical').description('纵向 2×3'),
    ]).default('horizontal').description('六面正交合成图布局；最长边由 outputSize 控制。'),
    groupSendOptions: Schema.array(Schema.object({
      groupId: Schema.string().description('QQ群号。'),
      sendMode: Schema.union([
        Schema.const('forward').description('合并转发'),
        Schema.const('combined').description('联合发送（一条普通消息）'),
      ]).default('forward'),
      replyAndMention: Schema.union([
        Schema.const('inherit').description('继承全局设置'),
        Schema.const('enabled').description('开启回复 @'),
        Schema.const('disabled').description('关闭回复 @'),
      ]).default('inherit'),
    })).default([]).description('按群覆盖发送方式和回复设置；官方 QQ 只使用引用开关，自建 QQ 同时使用发送模式和 @。'),
  }).description('发送设置'),
  Schema.object({
    javaPath: Schema.path({ filters: ['file'] }).default('').description('Java 路径：独立渲染器使用的 Java 可执行文件；推荐 Java 21+，留空自动查找。'),
    standaloneJavaCommand: Schema.string().default('').hidden().description('旧版 Java 路径字段，仅用于兼容已有配置。'),
    minecraftJarPath: Schema.string().default('').description('可选的 Minecraft 客户端 JAR 或基础资源包；留空使用内置 26.2 原版资源。'),
    resourcePackPaths: Schema.array(Schema.string()).role('table').default([]).description('自定义资源包：点击上传材质包添加 ZIP；越靠后优先级越高，可选中后上移或下移。'),
    standaloneRendererJar: Schema.string().default('').description('独立渲染器 JAR；留空使用插件内置版本。'),
    standaloneRenderTimeout: Schema.natural().min(10000).default(180000).description('独立 Java 渲染超时（毫秒）。'),
    standaloneJavaMaxHeapMb: Schema.natural().min(128).max(32768).step(8).default(200).description('首次独立渲染的最大堆内存（MiB）。'),
    standaloneJavaRetryMaxHeapMb: Schema.natural().min(256).max(32768).step(128).default(2048).description('内存不足时新进程重试的最大堆内存（MiB）。'),
    standaloneJavaMemoryRestartLimit: Schema.natural().max(3).default(1).description('检测到内存不足后启动全新进程重试的次数。'),
    javaResolution: Schema.natural().min(256).max(4096).default(1024).hidden().description('旧版 Java 分辨率字段，仅用于兼容已有配置；现在统一使用图像清晰度。'),
    javaSupersampling: Schema.natural().min(1).max(4).default(1).description('离屏超采样倍数；1 为按目标分辨率直接输出，速度最快。2 及以上会显著增加耗时和内存。'),
  }).description('独立渲染器').collapse(),
  Schema.object({
    renderTimeout: Schema.natural().min(1000).default(30000).description('文件下载超时（毫秒）。'),
    cacheDirectory: Schema.string().default('data/litematic-renderer-cache').description('持久缓存目录；按插件版本和投影 SHA-256 分区。'),
    cacheMaxSizeGb: Schema.number().min(1).max(1024).step(1).default(20).description('所有版本缓存总上限（GiB），超出后按最久未使用清理。'),
    diagnosticsFilePath: Schema.string().default('data/litematic-renderer-diagnostics.json').description('渲染诊断持久化文件路径；导出地址固定为 /litematic-renderer/diagnostics。'),
  }).description('缓存与诊断').collapse(),
  Schema.object({
    isometricCellSize: Schema.natural().min(2).max(32).default(7).description('仅 CPU 后端：方块菱形半宽；数值越大，原始轴测图越细致，也越耗内存。'),
    javaRenderTimeout: Schema.natural().min(10000).default(180000).description('Minecraft GPU/Fabric 客户端后端：等待渲染任务完成的超时（毫秒）。'),
    webglQuality: Schema.union([Schema.const('standard'), Schema.const('high'), Schema.const('ultra')]).default('high').description('仅 WebGL 后端：模型与材质渲染质量；越高越清晰，也越耗浏览器资源。'),
    webglWidth: Schema.natural().min(256).max(2048).default(800).hidden().description('旧版 WebGL 宽度字段，仅用于兼容；现在统一使用图像清晰度。'),
    webglHeight: Schema.natural().min(256).max(2048).default(600).hidden().description('旧版 WebGL 高度字段，仅用于兼容；现在统一使用图像清晰度。'),
    isometricSquare: Schema.boolean().default(true).description('仅 WebGL 后端：将轴测图画布调整为正方形。'),
    javaBridgeDirectory: Schema.path({ filters: ['directory'] }).default('').description('仅 java/Fabric 桥后端：Minecraft Java 渲染桥任务目录；standalone 后端无需填写。'),
    gpuClientGameDirectory: Schema.path({ filters: ['directory'] }).default('').description('仅 Minecraft 26.2 GPU 客户端：游戏目录（例如 26.2-Fabricjqr）。插件会自动安装内置单 JAR 渲染端并连接任务队列。'),
    gpuRendererCommand: Schema.path({ filters: ['file'] }).default('').description('可选外部 GPU 渲染器程序；填写后最先尝试，失败时自动回退到 renderEngine。'),
  }).description('高级设置').collapse(),
  Schema.object({
    gpuAgentEnabled: Schema.boolean().default(false).description('启用由 GPU Agent 主动连接的 WebSocket v2 服务。'),
    gpuAgentListenHost: Schema.string().default('0.0.0.0').description('GPU Agent WebSocket 监听地址。'),
    gpuAgentListenPort: Schema.natural().min(1).max(65535).default(39180).description('GPU Agent WebSocket 监听端口。'),
    gpuAgentPath: Schema.string().default('/litematic-renderer/agent/v2').description('GPU Agent WebSocket 路径。'),
    gpuAgentNodes: Schema.array(Schema.object({
      agentId: Schema.string().description('GPU 节点唯一 ID。'),
      sharedSecret: Schema.string().role('secret').description('该节点的 HMAC 共享密钥，建议至少 32 个字符。'),
      enabled: Schema.boolean().default(true).description('允许此节点连接。'),
    })).default([]).description('允许连接的 GPU 节点；每个节点使用独立密钥。'),
    gpuAgentTimeout: Schema.natural().min(10000).max(3600000).default(240000).description('等待 GPU Agent 完成任务的超时（毫秒）。'),
    gpuAgentFallback: Schema.boolean().default(true).description('GPU Agent 不可用或失败时自动回退到独立 Java 渲染器。'),
  }).description('GPU Agent v2').collapse(),
  Schema.object({
    remoteAgentUrl: Schema.string().default('').description('旧版 Remote Agent HTTP 地址，用于兼容已有 FRP 部署。'),
    remoteAgentSecret: Schema.string().role('secret').default('').description('旧版 Remote Agent HMAC 共享密钥。'),
    remoteAgentTimeout: Schema.natural().min(10000).max(900000).default(240000).description('旧版 Remote Agent 请求超时（毫秒）。'),
    remoteAgentClockSkewSeconds: Schema.natural().min(10).max(600).default(90).description('旧版 HMAC 请求允许的时钟偏差（秒）。'),
  }).description('Remote Agent v1 兼容').collapse(),
])

interface FileElement {
  name?: string
  filename?: string
  file_name?: string
  fileName?: string
  url?: string
  src?: string
  file?: string | Record<string, unknown>
  size?: string | number
  id?: string
  file_id?: string
  fileId?: string
  busid?: string | number
}
interface Block { x: number, y: number, z: number, name: string }
interface Bounds { minX: number, minY: number, minZ: number, maxX: number, maxY: number, maxZ: number }
interface ImageResult { title: string, path: string }
interface RenderedImage { title: string, png: Buffer }
interface RenderResult { images: ImageResult[], metadata: string, projectionName: string }

const activeStandaloneJavaProcesses = new Set<ChildProcess>()
let standaloneRenderQueue = Promise.resolve()

async function acquireStandaloneRenderSlot() {
  const previous = standaloneRenderQueue
  let release!: () => void
  standaloneRenderQueue = new Promise<void>(resolveRelease => { release = resolveRelease })
  await previous.catch(() => undefined)
  return release
}

export interface LitematicMetadata {
  author: string
  createdAt: string
  totalBlocks?: number
  totalVolume?: number
  size?: [number, number, number]
  litematicVersion?: number
  minecraftDataVersion?: number
  minecraftVersion?: string
}

type OfficialProxyConfig = Pick<Config,
  'officialProxyMode' | 'officialProxyUrl' | 'sshProxyExecutable' | 'sshProxyHost' | 'sshProxyPort'
  | 'sshProxyUser' | 'sshProxyPrivateKey' | 'sshProxyPassword' | 'sshProxyLocalPort'>

const SUPPORTED_PROXY_PROTOCOLS = new Set(['http:', 'https:', 'socks:', 'socks5:', 'socks5h:'])

export function resolveOfficialProxyUrl(config: OfficialProxyConfig) {
  if (config.officialProxyMode === 'disabled') return undefined
  const value = config.officialProxyMode === 'ssh'
    ? `socks5h://127.0.0.1:${config.sshProxyLocalPort}`
    : config.officialProxyUrl.trim()
  if (!value) throw new Error('已开启官方 QQ 中转，但代理地址为空')
  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`代理地址格式无效：${value}`)
  }
  if (!SUPPORTED_PROXY_PROTOCOLS.has(url.protocol)) {
    throw new Error(`不支持的代理协议：${url.protocol}`)
  }
  return url.href
}

export function buildSshProxyArguments(config: OfficialProxyConfig) {
  const host = config.sshProxyHost.trim()
  const user = config.sshProxyUser.trim()
  const privateKey = config.sshProxyPrivateKey.trim()
  if (!host) throw new Error('SSH 中转服务器 IP 不能为空')
  if (!user) throw new Error('SSH 登录用户名不能为空')
  if (!privateKey && !config.sshProxyPassword) throw new Error('SSH 私钥和密码至少填写一项')
  const args = [
    '-NT',
    '-D', `127.0.0.1:${config.sshProxyLocalPort}`,
    '-p', String(config.sshProxyPort),
    '-o', 'BatchMode=yes',
    '-o', 'ExitOnForwardFailure=yes',
    '-o', 'ServerAliveInterval=30',
    '-o', 'ServerAliveCountMax=3',
    '-o', 'StrictHostKeyChecking=accept-new',
  ]
  if (privateKey) args.push('-i', resolve(privateKey))
  args.push(`${user}@${host}`)
  return args
}

function isTcpPortOpen(host: string, port: number, timeout = 1000) {
  return new Promise<boolean>((done) => {
    const socket = new Socket()
    let settled = false
    const finish = (result: boolean) => {
      if (settled) return
      settled = true
      socket.destroy()
      done(result)
    }
    socket.setTimeout(timeout)
    socket.once('connect', () => finish(true))
    socket.once('timeout', () => finish(false))
    socket.once('error', () => finish(false))
    socket.connect(port, host)
  })
}

function startPasswordSshProxyTunnel(config: OfficialProxyConfig, logger: PluginLogger) {
  let client: SshClient | undefined
  let server: ReturnType<typeof createServer> | undefined
  let restartTimer: NodeJS.Timeout | undefined
  let stopped = false
  const sockets = new Set<Socket>()

  const scheduleRestart = () => {
    if (stopped || restartTimer) return
    restartTimer = setTimeout(() => {
      restartTimer = undefined
      launch()
    }, 5000)
  }
  const launch = () => {
    if (stopped) return
    client = new SshClient()
    client.once('ready', () => {
      if (stopped || !client) return
      server = createServer((socket) => {
        sockets.add(socket)
        socket.once('close', () => sockets.delete(socket))
        handleSocks5Connection(socket, client!)
      })
      server.once('error', (error) => {
        logger.warn(`本地 SOCKS5 监听失败：${error.message}`)
        client?.end()
      })
      server.listen(config.sshProxyLocalPort, '127.0.0.1', () => {
        logger.info(`官方 QQ 中转已就绪：socks5h://127.0.0.1:${config.sshProxyLocalPort}`)
      })
    })
    client.once('error', (error) => logger.warn(`SSH 密码登录失败：${error.message}`))
    client.once('close', () => {
      client = undefined
      server?.close()
      server = undefined
      for (const socket of sockets) socket.destroy()
      if (!stopped) scheduleRestart()
    })
    logger.info(`正在使用密码建立官方 QQ SSH 中转：${config.sshProxyUser}@${config.sshProxyHost}`)
    client.connect({
      host: config.sshProxyHost.trim(),
      port: config.sshProxyPort,
      username: config.sshProxyUser.trim(),
      password: config.sshProxyPassword,
      keepaliveInterval: 30000,
      keepaliveCountMax: 3,
      readyTimeout: 15000,
    })
  }
  launch()
  return () => {
    stopped = true
    if (restartTimer) clearTimeout(restartTimer)
    server?.close()
    client?.end()
    for (const socket of sockets) socket.destroy()
  }
}

function handleSocks5Connection(socket: Socket, client: SshClient) {
  let buffer = Buffer.alloc(0)
  let state: 'greeting' | 'request' = 'greeting'
  const fail = (code = 1) => {
    if (state === 'request') socket.write(Buffer.from([5, code, 0, 1, 0, 0, 0, 0, 0, 0]))
    socket.destroy()
  }
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk])
    if (state === 'greeting') {
      if (buffer.length < 2) return
      const length = 2 + buffer[1]
      if (buffer.length < length) return
      if (buffer[0] !== 5 || !buffer.subarray(2, length).includes(0)) return fail()
      buffer = buffer.subarray(length)
      state = 'request'
      socket.write(Buffer.from([5, 0]))
    }
    if (state !== 'request' || buffer.length < 4) return
    if (buffer[0] !== 5 || buffer[1] !== 1) return fail(7)
    const type = buffer[3]
    let host: string
    let offset: number
    if (type === 1) {
      if (buffer.length < 10) return
      host = [...buffer.subarray(4, 8)].join('.')
      offset = 8
    } else if (type === 3) {
      const length = buffer[4]
      if (buffer.length < 7 + length) return
      host = buffer.subarray(5, 5 + length).toString('utf8')
      offset = 5 + length
    } else if (type === 4) {
      if (buffer.length < 22) return
      const groups = []
      for (let index = 4; index < 20; index += 2) groups.push(buffer.readUInt16BE(index).toString(16))
      host = groups.join(':')
      offset = 20
    } else {
      return fail(8)
    }
    const port = buffer.readUInt16BE(offset)
    socket.pause()
    socket.removeAllListeners('data')
    client.forwardOut('127.0.0.1', 0, host, port, (error, channel) => {
      if (error) return fail(5)
      socket.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]))
      socket.pipe(channel).pipe(socket)
      socket.resume()
    })
  })
  socket.once('error', () => socket.destroy())
}

function startSshProxyTunnel(config: OfficialProxyConfig, logger: PluginLogger) {
  buildSshProxyArguments(config)
  if (!config.sshProxyPrivateKey.trim()) return startPasswordSshProxyTunnel(config, logger)
  const command = config.sshProxyExecutable.trim() || 'ssh'
  const args = buildSshProxyArguments(config)
  let child: ChildProcess | undefined
  let restartTimer: NodeJS.Timeout | undefined
  let stopped = false

  const scheduleRestart = () => {
    if (stopped || restartTimer) return
    restartTimer = setTimeout(() => {
      restartTimer = undefined
      void launch()
    }, 5000)
  }
  const launch = async () => {
    if (stopped) return
    if (await isTcpPortOpen('127.0.0.1', config.sshProxyLocalPort)) {
      logger.info(`官方 QQ 中转已就绪：socks5h://127.0.0.1:${config.sshProxyLocalPort}`)
      return
    }
    try {
      await fs.access(resolve(config.sshProxyPrivateKey), constants.R_OK)
    } catch {
      logger.warn(`SSH 私钥不可读：${resolve(config.sshProxyPrivateKey)}`)
      scheduleRestart()
      return
    }
    child = spawn(command, args, { stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true })
    let stderr = ''
    child.stderr?.on('data', (data: Buffer) => {
      stderr = `${stderr}${data.toString()}`.slice(-2000)
    })
    child.once('spawn', () => logger.info(`正在建立官方 QQ SSH 中转：${config.sshProxyUser}@${config.sshProxyHost}`))
    child.once('error', (error) => {
      logger.warn(`SSH 中转启动失败：${error.message}`)
      scheduleRestart()
    })
    child.once('close', (code) => {
      child = undefined
      if (stopped) return
      const detail = stderr.trim().split(/\r?\n/).at(-1)
      logger.warn(`SSH 中转已断开（退出码 ${code ?? 'unknown'}）${detail ? `：${detail}` : ''}`)
      scheduleRestart()
    })
    for (let attempt = 0; attempt < 20 && child && !stopped; attempt++) {
      if (await isTcpPortOpen('127.0.0.1', config.sshProxyLocalPort, 300)) {
        logger.info(`官方 QQ 中转已就绪：socks5h://127.0.0.1:${config.sshProxyLocalPort}`)
        break
      }
      await new Promise(resolve => setTimeout(resolve, 250))
    }
  }
  void launch()
  return () => {
    stopped = true
    if (restartTimer) clearTimeout(restartTimer)
    child?.kill()
  }
}

export function apply(ctx: Context, config: Config) {
  const logger = ctx.logger(name)
  const gpuAgentHub = config.gpuAgentEnabled
    ? new GpuAgentHub({
      host: config.gpuAgentListenHost,
      port: config.gpuAgentListenPort,
      path: config.gpuAgentPath.startsWith('/') ? config.gpuAgentPath : `/${config.gpuAgentPath}`,
      nodes: config.gpuAgentNodes,
      heartbeatTimeout: 30_000,
      logger,
    })
    : undefined
    gpuAgentHub?.start()
    if (gpuAgentHub) ctx.on('dispose', () => void gpuAgentHub.close())
    // GPU Agent 同一时间只接受一个任务；大量文件同时到达时在此排队依次渲染，而不是直接拒绝
    let gpuAgentQueue: Promise<unknown> = Promise.resolve()
    const enqueueGpuAgentRender = <T>(task: () => Promise<T>): Promise<T> => {
      const run = gpuAgentQueue.then(task, task)
      gpuAgentQueue = run.catch(() => undefined)
      return run
    }
  if (config.renderEngine === 'gpuClient' && config.gpuClientGameDirectory?.trim()) {
    void installBundledGpuClient(config.gpuClientGameDirectory, logger)
  }
  if (config.qqBotType === 'official') {
    const proxyUrl = resolveOfficialProxyUrl(config)
    if (proxyUrl) {
      const removeProxyConfig = ctx.root.on('http/config', (httpConfig) => {
        ;(httpConfig as typeof httpConfig & { proxyAgent?: string }).proxyAgent = proxyUrl
      })
      const stopTunnel = config.officialProxyMode === 'ssh'
        ? startSshProxyTunnel(config, logger)
        : () => undefined
      logger.info(`官方 QQ 出站代理已启用：${proxyUrl}`)
      // 网关看门狗：官方适配器偶发静默掉线（无报错、不再收消息）。
      // 每分钟主动请求一次 QQ 网关接口；连续失败（中转断/网络断）只记录，
      // 但能确保 http 栈经代理保持活跃，适配器底层的 token 刷新会随之自然重连。
      let lastIncomingMessage = Date.now()
      ctx.on('message', () => { lastIncomingMessage = Date.now() })
      const keepalive = setInterval(async () => {
        try {
          await ctx.http.get('https://api.sgroup.qq.com/gateway', { timeout: 10_000, responseType: 'json' })
        } catch {
          // 401/4xx 属预期（无凭证也能到达网关），静默；网络级失败同样只记录
        }
      }, 60_000)
      // 网关看门狗：官方适配器偶发“静默掉线”（WebSocket 半死、不报错、不重连、收不到任何消息）。
      // 超过 8 分钟没有任何入站消息时，热重载本插件所在 loader 中的 QQ 适配器，强制重新鉴权。
      const reloadAdapter = async () => {
        try {
          const loader = (ctx as any).loader
          if (!loader?.entries) return
          for (const entry of loader.entries.values()) {
            if (entry?.config && 'secret' in entry.config && 'intents' in entry.config && String(entry.name ?? '').includes('adapter-qq')) {
              logger.warn('网关看门狗：超过 8 分钟未收到任何 QQ 消息，热重载 QQ 适配器以重建网关连接')
              await loader.reload(entry)
              lastIncomingMessage = Date.now()
              return
            }
          }
        } catch (error) {
          logger.warn(`网关看门狗热重载失败：${error instanceof Error ? error.message : String(error)}`)
        }
      }
      const watchdog = setInterval(() => {
        if (Date.now() - lastIncomingMessage > 8 * 60_000) void reloadAdapter()
      }, 60_000)
      ctx.on('dispose', () => { clearInterval(keepalive); clearInterval(watchdog) })
      ctx.on('dispose', () => {
        removeProxyConfig()
        stopTunnel()
      })
    }
  }
  ctx.inject(['console'], (consoleCtx) => {
    const root = resolve(__dirname, '..')
    consoleCtx.console.addEntry({
      dev: join(root, 'client/index.ts'),
      prod: join(root, 'dist'),
    })
    consoleCtx.console.addListener('litematic/resource-pack-upload', async (filename, base64) => {
      return saveUploadedResourcePack(filename, base64, resolve(consoleCtx.baseDir, 'data/litematic-resource-packs'))
    }, { authority: 4 })
  })
  const maxFileSizeBytes = config.maxFileSize * 1024
  ctx.on('dispose', () => terminateActiveStandaloneJavaProcesses(logger))
  if (config.qqBotType === 'selfHosted') {
    void ensureOneBotGroupUploadCompatibility(logger, config.patchOneBotGroupUpload)
  }
  const diagnosticsPath = resolve(stringOrDefault(config.diagnosticsFilePath, 'data/litematic-renderer-diagnostics.json'))
  const diagnosticsArchiveDirectory = join(dirname(diagnosticsPath), 'litematic-renderer-diagnostics-archive')
  const diagnosticsDisabledDirectory = join(dirname(diagnosticsPath), 'litematic-renderer-diagnostics-disabled')
  void rotateDiagnosticsFile(diagnosticsPath, diagnosticsArchiveDirectory, logger)
  const cacheDirectory = resolve(stringOrDefault(config.cacheDirectory, 'data/litematic-renderer-cache'))
  const versionCacheDirectory = join(cacheDirectory, `v${cachePathSegment(PLUGIN_VERSION)}`)
  const cacheMaxBytes = Math.floor(config.cacheMaxSizeGb * 1024 ** 3)
  const inFlight = new Map<string, Promise<void>>()
  void fs.mkdir(versionCacheDirectory, { recursive: true })
    .then(() => enforceCacheLimit(cacheDirectory, cacheMaxBytes))
    .catch(error => logger.warn(`缓存初始化失败：${error instanceof Error ? error.message : String(error)}`))

  const render = async (url: string, filename = 'schematic.litematic', preparedBytes?: Buffer, includeSixFace = false,
                        source?: { group?: string, user?: string }): Promise<RenderResult> => {
    const renderSource = source
    const bytes = preparedBytes ?? await download(ctx, url, maxFileSizeBytes, config.renderTimeout)
    const parsedMetadata = parseLitematicMetadata(bytes)
    const metadata = formatLitematicMetadata(parsedMetadata, filename)
    const projectionName = projectionNameFromFilename(filename)
    const fileHash = createHash('sha256').update(bytes).digest('hex')
    const needsStandaloneResources = config.renderEngine === 'standalone'
      || (config.renderEngine === 'gpuAgent' && config.gpuAgentFallback)
    const minecraftJarPath = needsStandaloneResources
      ? await resolveMinecraftResources(config)
      : ''
    const resourceFingerprint = needsStandaloneResources
      ? await fingerprintFiles([minecraftJarPath, ...resourcePackPaths(config.resourcePackPaths)])
      : []
    const renderHash = hashRenderConfiguration({
        version: CACHE_FORMAT_VERSION,
        outputSize: config.outputSize,
        isometricCellSize: config.isometricCellSize,
        background: config.background,
        transparentBackground: config.transparentBackground,
        gpuRendererCommand: config.gpuRendererCommand,
        renderEngine: config.renderEngine,
        standaloneRendererJar: config.standaloneRendererJar,
        resourceFingerprint,
        javaSupersampling: config.javaSupersampling,
        webglQuality: config.webglQuality,
        webglWidth: config.webglWidth,
        webglHeight: config.webglHeight,
        isometricSquare: config.isometricSquare,
        isometricFill: config.isometricFill,
        isometricRotation: config.isometricRotation,
        isometricSlant: config.isometricSlant,
        sixFaceLayout: config.sixFaceLayout,
        gpuAgentFingerprint: config.renderEngine === 'gpuAgent' ? gpuAgentHub?.capabilityFingerprint() ?? 'disabled' : undefined,
      })
    const cacheProjectionName = cacheNameSegment(filename)
    const output = join(versionCacheDirectory, `${cacheProjectionName}-${fileHash}-${renderHash}`)
    const input = join(output, 'projection.litematic')
    await fs.mkdir(output, { recursive: true })
    if (!(await exists(input))) await fs.writeFile(input, bytes)
    await ensureCacheMetadata(output, {
      pluginVersion: PLUGIN_VERSION,
      cacheFormatVersion: CACHE_FORMAT_VERSION,
      contentSha256: fileHash,
      renderConfigSha256: renderHash,
      sourceFilename: basename(filename),
      createdAt: new Date().toISOString(),
    })
    const expected = ['isometric.png', 'isometric-reverse.png'].map(file => join(output, file))
    const mergedExpected = join(output, 'isometric.png')
    if (!(await Promise.all(expected.map(exists))).every(Boolean) && !(await exists(mergedExpected))) {
      let task = inFlight.get(output)
      if (!task) {
        task = (async () => {
          try {
          if ((await Promise.all(expected.map(exists))).every(Boolean)) return
          const gpuSucceeded = Boolean(config.gpuRendererCommand
            && await renderWithGpu(config.gpuRendererCommand, input, output, config.renderTimeout, logger))
          if (!gpuSucceeded || !(await Promise.all(expected.map(exists))).every(Boolean)) {
            if (config.renderEngine === 'standalone') {
              await renderWithStandalone(input, output, minecraftJarPath, config, logger)
              await mergeRenderDiagnostics(output, diagnosticsPath, logger)
            } else if (config.renderEngine === 'gpuAgent') {
              try {
                if (!gpuAgentHub) throw new Error('GPU Agent v2 服务未启用')
                const result = await enqueueGpuAgentRender(() => gpuAgentHub.render(createGpuRenderRequest(filename, config, renderSource), bytes, config.gpuAgentTimeout))
                for (const image of result.images) {
                  if (image.id === 'merged' || image.name === 'merged.png') {
                    // Agent 已把正反两图拼为一张：直接作为唯一结果
                    await fs.writeFile(join(output, 'isometric.png'), image.png)
                    break
                  }
                  if (image.id === 'isometric' || image.name === 'isometric.png') {
                    await fs.writeFile(join(output, 'isometric.png'), image.png)
                  } else if (image.id === 'isometric-reverse' || image.name === 'isometric-reverse.png') {
                    await fs.writeFile(join(output, 'isometric-reverse.png'), image.png)
                  }
                }
              } catch (error) {
                if (!config.gpuAgentFallback) throw error
                logger.warn(`GPU Agent 渲染失败，回退独立 Java：${error instanceof Error ? error.message : String(error)}`)
                await renderWithStandalone(input, output, minecraftJarPath, config, logger)
                await mergeRenderDiagnostics(output, diagnosticsPath, logger)
              }
            } else if (config.renderEngine === 'remoteAgent') {
              await renderWithRemoteAgent(bytes, output, filename, config)
            } else if (config.renderEngine === 'java' || config.renderEngine === 'gpuClient') {
              await renderWithJavaBridge(input, output, config)
            } else {
              const schematic = parseLitematic(bytes)
              let images: RenderedImage[]
              if (config.renderEngine === 'webgl' && (ctx as any).puppeteer) {
                try {
                  images = await renderWithWebgl(ctx, bytes, config)
                } catch (error) {
                  logger.warn(`WebGL 渲染失败，回退 CPU：${error instanceof Error ? error.message : String(error)}`)
                  images = renderSchematic(schematic, config)
                }
              } else {
                images = renderSchematic(schematic, config)
              }
              await Promise.all(images.map(image => fs.writeFile(join(output, image.title), image.png)))
            }
          }
          // Agent 合并模式下只生成一张拼接图（isometric.png），此时不要求两张
          if (!(await Promise.all(expected.map(exists))).every(Boolean) && !(await exists(mergedExpected))) throw new Error('渲染器没有生成两张正二轴测 PNG')
          } catch (error) {
            await appendRenderError(output, diagnosticsPath, config.renderEngine, error, logger)
            throw error
          }
        })().finally(() => inFlight.delete(output))
        inFlight.set(output, task)
      } else {
        logger.debug(`等待相同投影的缓存任务：${fileHash}`)
      }
      await task
    } else {
      logger.debug(`命中投影缓存：${fileHash}`)
    }
    const images: ImageResult[] = (await exists(expected[1]))
      ? expected.map((path, index) => ({
          title: ['正二轴测', '反向正二轴测（旋转 180°）'][index],
          path,
        }))
      : [{ title: '正二轴测', path: expected[0] }]
    if (includeSixFace) {
      const sixFacePath = join(output, 'six-faces.png')
      if (!(await exists(sixFacePath))) {
        const sixFaceTaskKey = `${output}:six-face`
        let task = inFlight.get(sixFaceTaskKey)
        if (!task) {
          task = (async () => {
            try {
              if (config.renderEngine === 'standalone') {
                try {
                  await renderWithStandalone(input, output, minecraftJarPath, config, logger, 'six-face')
                } catch (error) {
                  logger.warn(`真实材质六面图生成失败，回退方块颜色投影：${error instanceof Error ? error.message : String(error)}`)
                }
              }
              if (!(await exists(sixFacePath))) {
                const blocks = parseLitematic(bytes)
                const png = renderSixFaceOverview(blocks, config, config.sixFaceLayout)
                await fs.writeFile(sixFacePath, png)
              }
            } catch (error) {
              logger.warn(`六面正交图生成失败，继续发送轴测图：${error instanceof Error ? error.message : String(error)}`)
            }
          })().finally(() => inFlight.delete(sixFaceTaskKey))
          inFlight.set(sixFaceTaskKey, task)
        }
        await task
      }
      if (await exists(sixFacePath)) images.push({ title: '六面正投影', path: sixFacePath })
    }
    await touchCacheEntry(output)
    await enforceCacheLimit(cacheDirectory, cacheMaxBytes, output)
    return {
      images,
      metadata,
      projectionName,
    }
  }

  ctx.middleware(async (session, next) => {
    if (!canRenderInSession(session, config.allowPrivateRender)) return next()
    const file = findLitematicFile(session)
    if (!file) return next()
    const url = await resolveFileUrl(session, file)
    if (!url) {
      logger.warn(`检测到 Litematic 文件但无法获取下载地址：${fileName(file) ?? 'unknown.litematic'}；请检查 OneBot/NapCat 的 get_group_file_url 接口。`)
      await session.send([...replyElements(session, config.qqBotType), h('text', { content: '检测到投影文件，但无法获取群文件下载地址，请检查机器人文件接口。' })])
      return next()
    }
    if (isOverLimit(file.size, maxFileSizeBytes)) {
      await appendGlobalRenderError(diagnosticsPath, `input:${basename(fileName(file) ?? 'unknown.litematic')}`, 'input', `file size exceeds ${config.maxFileSize} KB`, logger)
      await session.send([...replyElements(session, config.qqBotType), h('text', { content: `文件大小超过 ${(config.maxFileSize / 1024).toFixed(2)} MB，无法渲染。` })])
      return next()
      await session.send(`文件大小超过 ${(config.maxFileSize / 1024).toFixed(2)} MB，无法渲染。`)
      return next()
    }
    if (!isUnderLimit(file.size, maxFileSizeBytes)) return next()
    try {
      const sendOptions = resolveSendOptions(config, session.guildId)
      const result = await render(url, fileName(file) ?? 'schematic.litematic', undefined, sendOptions.sixFaceOverview,
        { group: session.guildId ?? undefined, user: session.userId ?? undefined })
      await sendImages(session, result.images, result.metadata, sendOptions, result.projectionName)
    } catch (error) {
      logger.warn(error)
      await session.send([...replyElements(session, config.qqBotType), h('text', { content: formatRenderError(error, config) })])
      return next()
      // @ts-expect-error unreachable legacy error text is retained for compatibility
      await session.send(`投影渲染失败：${error instanceof Error ? error.message : String(error)}`)
    }
    return next()
  })

  ctx.command('litematic.render <url:string>', '渲染可直接下载的 .litematic 文件')
    .action(async ({ session }, url) => {
      if (!url) return '请提供 .litematic 文件的直链。'
      if (!session) return '此命令只能在消息会话中执行。'
      if (!canRenderInSession(session, config.allowPrivateRender)) return '单人对话渲染未开启，请在插件发送设置中启用。'
      try {
        const sendOptions = resolveSendOptions(config, session.guildId)
        const result = await render(url, 'schematic.litematic', undefined, sendOptions.sixFaceOverview)
        await sendImages(session, result.images, result.metadata, sendOptions, result.projectionName)
      } catch (error) {
        return formatRenderError(error, config)
        // @ts-expect-error unreachable legacy error text is retained for compatibility
        return `渲染失败：${error instanceof Error ? error.message : String(error)}`
      }
    })
  ctx.command('litematic.cache.clear', '清理投影渲染缓存', { authority: 3 })
    .action(async () => {
      await fs.rm(cacheDirectory, { recursive: true, force: true })
      await fs.mkdir(versionCacheDirectory, { recursive: true })
      return '投影渲染缓存已清理。'
    })
  ctx.command('litematic.errors.export', 'export render diagnostics', { authority: 3 })
    .action(async () => `Diagnostics file: ${diagnosticsPath}`)
  ctx.command('litematic.errors.disable', 'disable current render diagnostics snapshot', { authority: 3 })
    .action(async () => disableDiagnosticsSnapshot(diagnosticsPath, diagnosticsDisabledDirectory))
  ctx.command('litematic.proxy.check', '检查 Koishi 当前公网出口 IP', { authority: 3 })
    .action(async () => {
      try {
        const address = await ctx.http.get<string>('https://ifconfig.me/ip', {
          responseType: 'text',
          timeout: 10000,
          headers: { 'User-Agent': 'koishi-plugin-litematic-renderer' },
        })
        return `当前 Koishi 出口 IP：${String(address).trim()}`
      } catch (error) {
        return `出口 IP 检查失败：${error instanceof Error ? error.message : String(error)}`
      }
    })
  const server = (ctx as any).server
  if (server?.get) {
    server.get('/litematic-renderer/diagnostics', async (koa: any) => {
      koa.type = 'application/json'
      koa.set('Content-Disposition', 'attachment; filename=\"litematic-renderer-diagnostics.json\"')
      koa.body = await fs.readFile(diagnosticsPath, 'utf8').catch(() => '{\"format\":2,\"blocks\":[],\"errors\":[]}')
    })
  }
}

export async function saveUploadedResourcePack(filename: unknown, base64: unknown, directory: string) {
  if (typeof filename !== 'string' || !/\.zip$/i.test(filename)) {
    throw new Error('只允许上传 ZIP 材质包')
  }
  if (typeof base64 !== 'string' || !base64.length) {
    throw new Error('材质包内容为空')
  }
  const bytes = Buffer.from(base64, 'base64')
  if (!bytes.length) throw new Error('材质包内容为空')
  if (bytes.length > RESOURCE_PACK_UPLOAD_LIMIT) {
    throw new Error('材质包超过 256 MB 上传上限')
  }
  const signature = bytes.readUInt32LE(0)
  if (![0x04034b50, 0x06054b50, 0x08074b50].includes(signature)) {
    throw new Error('文件不是有效的 ZIP 材质包')
  }

  const original = basename(filename).replace(/\.zip$/i, '')
  const stem = original.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/[. ]+$/g, '').trim() || 'resource-pack'
  const hash = createHash('sha256').update(bytes).digest('hex').slice(0, 12)
  const output = join(directory, `${stem}-${hash}.zip`)
  await fs.mkdir(directory, { recursive: true })
  await fs.writeFile(output, bytes)
  return output.replace(/\\/g, '/')
}

interface CacheMetadata {
  pluginVersion: string
  cacheFormatVersion: number
  contentSha256: string
  renderConfigSha256: string
  sourceFilename: string
  createdAt: string
}

export function hashRenderConfiguration(configuration: unknown) {
  return createHash('sha256').update(JSON.stringify(configuration)).digest('hex').slice(0, 16)
}

interface SourcePatchResult {
  source: string
  changed: boolean
  alreadyCompatible: boolean
}

type PluginLogger = {
  info(message: string): void
  warn(message: string): void
  debug(message: string): void
}

export function patchOneBotAdapterSource(source: string): SourcePatchResult {
  const activeGroupUpload = /^\s*case ["']group_upload["']:/m.test(source)
  if (activeGroupUpload) return { source, changed: false, alreadyCompatible: true }

  const importName = source.match(/\(0,\s*(import_[\w$]+)\.hyphenate\)\(data\.sub_type\)/)?.[1] ?? 'import_koishi2'
  const commentedBlock = /      \/\/ https:\/\/github\.com\/koishijs\/koishi-plugin-adapter-onebot\/issues\/33\r?\n      \/\/ case 'offline_file':\r?\n      \/\/   session\.elements = \[h\('file', data\.file\)\]\r?\n      \/\/   session\.type = 'message'\r?\n      \/\/   session\.subtype = 'private'\r?\n      \/\/   session\.isDirect = true\r?\n      \/\/   session\.subsubtype = 'offline-file-added'\r?\n      \/\/   break\r?\n      \/\/ case 'group_upload':\r?\n      \/\/   session\.elements = \[h\('file', data\.file\)\]\r?\n      \/\/   session\.type = 'message'\r?\n      \/\/   session\.subtype = 'group'\r?\n      \/\/   session\.subsubtype = 'guild-file-added'\r?\n      \/\/   break/
  if (!commentedBlock.test(source)) return { source, changed: false, alreadyCompatible: false }

  const replacement = [
    '      // Restored by koishi-plugin-litematic-renderer so NapCat group file uploads reach middleware.',
    '      case "offline_file":',
    `        session.elements = [(0, ${importName}.h)("file", data.file)];`,
    '        session.type = "message";',
    '        session.subtype = "private";',
    '        session.isDirect = true;',
    '        session.subsubtype = "offline-file-added";',
    '        break;',
    '      case "group_upload":',
    `        session.elements = [(0, ${importName}.h)("file", data.file)];`,
    '        session.type = "message";',
    '        session.subtype = "group";',
    '        session.subsubtype = "guild-file-added";',
    '        break;',
  ].join('\n')
  return { source: source.replace(commentedBlock, replacement), changed: true, alreadyCompatible: false }
}

async function ensureOneBotGroupUploadCompatibility(logger: PluginLogger, enabled: boolean) {
  if (!enabled) return
  let packageJsonPath: string
  try {
    packageJsonPath = require.resolve('koishi-plugin-adapter-onebot/package.json', { paths: [process.cwd(), __dirname] })
  } catch {
    logger.debug('未安装 koishi-plugin-adapter-onebot，跳过群文件上传事件兼容修复。')
    return
  }

  try {
    const packageDirectory = dirname(packageJsonPath)
    const packageJson = JSON.parse(await fs.readFile(packageJsonPath, 'utf8')) as { version?: string }
    const adapterEntry = join(packageDirectory, 'lib', 'index.js')
    const source = await fs.readFile(adapterEntry, 'utf8')
    const patch = patchOneBotAdapterSource(source)
    if (patch.alreadyCompatible) {
      logger.debug(`koishi-plugin-adapter-onebot@${packageJson.version ?? 'unknown'} 已支持 group_upload。`)
      return
    }
    if (!patch.changed) {
      logger.warn(`koishi-plugin-adapter-onebot@${packageJson.version ?? 'unknown'} 未识别到可自动修复的 group_upload 代码段。`)
      return
    }

    const backup = `${adapterEntry}.litematic-renderer.bak`
    if (!(await exists(backup))) await fs.writeFile(backup, source)
    await fs.writeFile(adapterEntry, patch.source)
    logger.warn(`已修复 koishi-plugin-adapter-onebot@${packageJson.version ?? 'unknown'} 的群文件上传事件映射；请重启 Koishi 让已加载的 OneBot 适配器生效。`)
  } catch (error) {
    logger.warn(`修复 OneBot 群文件上传事件失败：${error instanceof Error ? error.message : String(error)}`)
  }
}

function cachePathSegment(value: string) {
  return value.replace(/[^a-zA-Z0-9._-]/g, '_') || 'unknown'
}

export function cacheNameSegment(filename: string) {
  const name = basename(filename || 'schematic.litematic', extname(filename || ''))
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_')
    .replace(/[. ]+$/g, '')
    .trim()
  return (name || 'schematic').slice(0, 80)
}

function projectionNameFromFilename(filename: string) {
  return basename(filename || 'schematic.litematic').replace(/\.litematic$/i, '').trim() || 'schematic'
}

async function ensureCacheMetadata(directory: string, metadata: CacheMetadata) {
  const path = join(directory, 'cache.json')
  if (await exists(path)) return
  await fs.writeFile(path, JSON.stringify(metadata, null, 2) + '\n')
}

async function touchCacheEntry(directory: string) {
  const now = new Date()
  await fs.utimes(directory, now, now).catch(() => undefined)
}

async function directorySize(directory: string): Promise<number> {
  let total = 0
  const entries = await fs.readdir(directory, { withFileTypes: true }).catch((error: any) => {
    if (error?.code === 'ENOENT') return []
    throw error
  })
  for (const entry of entries) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) total += await directorySize(path)
    else if (entry.isFile()) total += (await fs.stat(path)).size
  }
  return total
}

export async function enforceCacheLimit(cacheDirectory: string, maxBytes: number, protectedDirectory?: string) {
  const entries: Array<{ path: string, size: number, lastUsed: number }> = []
  const versions = await fs.readdir(cacheDirectory, { withFileTypes: true }).catch((error: any) => {
    if (error?.code === 'ENOENT') return []
    throw error
  })
  for (const version of versions) {
    if (!version.isDirectory()) continue
    const versionPath = join(cacheDirectory, version.name)
    const cached = await fs.readdir(versionPath, { withFileTypes: true })
    for (const item of cached) {
      if (!item.isDirectory()) continue
      const path = join(versionPath, item.name)
      const [size, stat] = await Promise.all([directorySize(path), fs.stat(path)])
      entries.push({ path, size, lastUsed: stat.mtimeMs })
    }
  }

  let totalBytes = entries.reduce((sum, entry) => sum + entry.size, 0)
  let removedBytes = 0
  let removedEntries = 0
  const protectedPath = protectedDirectory && resolve(protectedDirectory)
  entries.sort((left, right) => left.lastUsed - right.lastUsed)
  for (const entry of entries) {
    if (totalBytes <= maxBytes) break
    if (protectedPath && resolve(entry.path) === protectedPath) continue
    await fs.rm(entry.path, { recursive: true, force: true })
    totalBytes -= entry.size
    removedBytes += entry.size
    removedEntries++
  }
  return { totalBytes, removedBytes, removedEntries }
}

function fileElements(session: Pick<Session, 'elements' | 'content'>) {
  return [...(session.elements ?? []), ...h.parse(session.content ?? '')]
}

export function findLitematicFile(session: Pick<Session, 'elements' | 'content'>): FileElement | undefined {
  const elements = fileElements(session).filter((element: any) => element.type === 'file')
  const matching = elements.find((element: any) => {
    return extname(fileName(element.attrs as FileElement) ?? '').toLowerCase() === '.litematic'
  })
  if (matching?.attrs) return matching.attrs as FileElement

  // NapCat/OneBot versions place the file payload at different event levels.
  const rawRoots = [(session as any).event, (session as any)._data, (session as any).data, (session as any).message]
  for (const root of rawRoots) {
    const rawFile = findLitematicInFilePayload(root)
    if (rawFile) return rawFile
  }

  // Some adapters expose a nameless file element but retain the displayed name in text.
  const inferredName = inferLitematicName(session.content)
  if (inferredName && elements[0]?.attrs) {
    return { ...(elements[0].attrs as FileElement), name: inferredName }
  }
  return undefined
}

export function canRenderInSession(
  session: Pick<Session, 'guildId' | 'isDirect'>,
  allowPrivateRender: boolean,
) {
  return Boolean(session.guildId) || (Boolean(session.isDirect) && allowPrivateRender)
}

function findLitematicInFilePayload(value: unknown, seen = new Set<object>(), depth = 0): FileElement | undefined {
  if (!value || typeof value !== 'object' || depth > 6) return undefined
  if (seen.has(value as object)) return undefined
  seen.add(value as object)
  const object = value as Record<string, unknown>
  const candidate = object as FileElement
  const nested = candidate.file && typeof candidate.file === 'object' ? candidate.file as FileElement : undefined
  if (nested && fileName(nested) && extname(fileName(nested)!).toLowerCase() === '.litematic') {
    return { ...nested, id: nested.id ?? candidate.id, file_id: nested.file_id ?? candidate.file_id, fileId: nested.fileId ?? candidate.fileId, busid: nested.busid ?? candidate.busid }
  }
  if (fileName(candidate) && extname(fileName(candidate)!).toLowerCase() === '.litematic') return candidate
  for (const [key, child] of Object.entries(object)) {
    if (!child || typeof child !== 'object') continue
    if (/file|upload|message|data|event/i.test(key)) {
      const found = findLitematicInFilePayload(child, seen, depth + 1)
      if (found) return found
    }
  }
  return undefined
}

function inferLitematicName(content: string | undefined) {
  if (!content) return undefined
  const marked = content.match(/(?:file|\u6587\u4ef6)\s+(.+?\.litematic)(?=$|[\]\u3011\r\n])/i)
  if (marked?.[1]) return marked[1].trim()
  const plain = content.match(/([^\s\]\u3011<>"']+\.litematic)(?=$|[\s\]\u3011])/i)
  return plain?.[1]?.trim()
}

function fileName(file: FileElement): string | undefined {
  const nested = file.file && typeof file.file === 'object' ? file.file : undefined
  const value = file.name ?? file.filename ?? file.file_name ?? file.fileName
    ?? nested?.name ?? nested?.filename ?? nested?.file_name ?? nested?.fileName
  return typeof value === 'string' && value.trim() ? value.trim() : undefined
}

async function resolveFileUrl(session: Session, file: FileElement) {
  const nested = file.file && typeof file.file === 'object' ? file.file : undefined
  const direct = file.url ?? file.src ?? (typeof file.file === 'string' ? file.file : undefined)
    ?? nested?.url ?? nested?.src
  if (typeof direct === 'string' && direct) return direct
  const fileId = file.id ?? file.file_id ?? file.fileId
    ?? nested?.id ?? nested?.file_id ?? nested?.fileId
  const internal = (session.bot as any).internal
  if (!session.guildId || !fileId || typeof internal?.getGroupFileUrl !== 'function') return
  const result = await internal.getGroupFileUrl(session.guildId, fileId, Number(file.busid ?? nested?.busid ?? 0))
  return typeof result === 'string' ? result : result?.url
}

function isUnderLimit(size: string | number | undefined, limit: number) {
  if (size == null || size === '') return true
  const numeric = Number(size)
  return Number.isFinite(numeric) && numeric > 0 && numeric <= limit
}

async function download(ctx: Context, url: string, maxSize: number, timeout: number) {
  const response = await ctx.http.get<ArrayBuffer>(url, { responseType: 'arraybuffer', timeout })
  const bytes = Buffer.from(response)
  if (bytes.byteLength > maxSize) throw new Error(`文件超过 ${(maxSize / 1024 / 1024).toFixed(2)} MB 限制`)
  return bytes
}

async function exists(path: string) {
  try { await fs.access(path, constants.R_OK); return true } catch { return false }
}

function stringOrDefault(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value : fallback
}

function resourcePackPaths(value: unknown) {
  return Array.isArray(value)
    ? value.filter((path): path is string => typeof path === 'string' && Boolean(path.trim()))
    : []
}

async function fingerprintFiles(paths: unknown[]) {
  return Promise.all(paths.filter((path): path is string => typeof path === 'string' && Boolean(path.trim())).map(async path => {
    const absolute = resolve(path)
    try {
      const stat = await fs.stat(absolute)
      return { path: absolute, size: stat.size, modified: stat.mtimeMs }
    } catch {
      return { path: absolute, missing: true }
    }
  }))
}

async function resolveStandaloneJavaCommand(configuredCommand: string) {
  const command = configuredCommand.trim()
  if (command) return command

  const executable = process.platform === 'win32' ? 'java.exe' : 'java'
  const candidates = new Set<string>()
  if (process.env.JAVA_HOME) candidates.add(join(process.env.JAVA_HOME, 'bin', executable))

  if (process.platform === 'win32') {
    const roots = new Set([
      join(process.env.ProgramFiles ?? 'C:/Program Files', 'Java'),
      join(process.env.ProgramFiles ?? 'C:/Program Files', 'Eclipse Adoptium'),
      join(process.env.ProgramFiles ?? 'C:/Program Files', 'Microsoft', 'jdk'),
    ])
    for (const root of roots) {
      try {
        const directories = await fs.readdir(root, { withFileTypes: true })
        for (const directory of directories) {
          if (directory.isDirectory() && /(?:^|[-_])21(?:[._-]|$)/.test(directory.name)) {
            candidates.add(join(root, directory.name, 'bin', executable))
          }
        }
      } catch { /* The JDK vendor is not installed at this location. */ }
    }
  }
  candidates.add('java')

  for (const candidate of candidates) {
    if (supportsStandaloneJavaVersion(await javaMajorVersion(candidate))) return candidate
  }
  throw new Error('未找到 Java 21+；请安装 Java 21 或更高版本，设置 JAVA_HOME，或填写 javaPath')
}

function isOverLimit(size: string | number | undefined, limit: number) {
  if (size == null || size === '') return false
  const numeric = Number(size)
  return Number.isFinite(numeric) && numeric > limit
}

async function javaMajorVersion(command: string) {
  return new Promise<number | undefined>(resolveVersion => {
    let output = ''
    let settled = false
    const settle = (version?: number) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolveVersion(version)
    }
    const child = spawn(command, ['-version'], { shell: false, windowsHide: true })
    const append = (chunk: Buffer) => { output = (output + String(chunk)).slice(-2000) }
    child.stdout?.on('data', append)
    child.stderr?.on('data', append)
    child.once('error', () => settle())
    child.once('close', () => {
      const match = output.match(/(?:java|openjdk) version "(\d+)/i)
      settle(match ? Number(match[1]) : undefined)
    })
    const timer = setTimeout(() => {
      child.kill()
      settle()
    }, 5000)
  })
}

export function supportsStandaloneJavaVersion(version: number | undefined) {
  return version !== undefined && version >= 21
}

export function standaloneJvmOptions(maxHeapMb: number) {
  const heap = Math.max(128, Math.min(32768, Math.floor(maxHeapMb)))
  return [
    '-Xms128m',
    `-Xmx${heap}m`,
    '-XX:+UseG1GC',
    '-XX:+UseStringDeduplication',
    '-XX:+ExitOnOutOfMemoryError',
  ]
}

export function effectiveRenderResolution(config: Pick<Config, 'outputSize'>) {
  return Math.max(256, Math.min(4096, Math.floor(config.outputSize)))
}

export function isJavaMemoryFailure(output: string, exitCode?: number | null) {
  if (exitCode === 3 || exitCode === 137) return true
  return /outofmemoryerror|java heap space|gc overhead limit|cannot reserve enough space|native memory allocation|insufficient memory|malloc failed|os::commit_memory/i.test(output)
}

async function resolveMinecraftResources(config: Config) {
  const configuredPath = typeof config.minecraftJarPath === 'string' ? config.minecraftJarPath.trim() : ''
  if (configuredPath && await exists(resolve(configuredPath))) return resolve(configuredPath)
  const bundled = resolve(join(__dirname, '../assets/vanilla-resources/Minecraft-26.2-Vanilla-Resources.zip'))
  if (!(await exists(bundled))) throw new Error(`插件内置的 Minecraft 26.2 原版资源包不存在：${bundled}`)
  return bundled
}

async function renderWithStandalone(input: string, output: string, minecraftJarPath: string, config: Config,
                                    logger: ReturnType<Context['logger']>, mode: 'isometric' | 'six-face' = 'isometric') {
  const releaseRenderSlot = await acquireStandaloneRenderSlot()
  try {
  const rendererJar = resolve(config.standaloneRendererJar || join(__dirname, '../assets/standalone-renderer/litematic-standalone-renderer-0.2.8.jar'))
  if (!(await exists(rendererJar))) throw new Error(`独立 Java 渲染器不存在：${rendererJar}`)
  if (!(await exists(minecraftJarPath))) throw new Error(`Minecraft 资源 JAR 不存在：${minecraftJarPath}`)
  for (const pack of resourcePackPaths(config.resourcePackPaths)) {
    if (!(await exists(resolve(pack)))) throw new Error(`材质包不存在：${pack}`)
  }

  const rendererArgs = [
    '-Djava.awt.headless=true', '-jar', rendererJar,
    '--input', resolve(input), '--output', resolve(output),
    '--minecraft-jar', minecraftJarPath,
    '--resolution', String(effectiveRenderResolution(config)),
    '--supersampling', String(config.javaSupersampling),
    '--rotation', String(config.isometricRotation),
    '--slant', String(config.isometricSlant),
    '--fill', String(config.isometricFill),
    '--background', config.background,
  ]
  if (mode === 'six-face') {
    rendererArgs.push(
      '--six-face-only',
      '--six-face-resolution', String(config.outputSize),
      '--six-face-layout', config.sixFaceLayout,
    )
  }
  if (config.transparentBackground) rendererArgs.push('--transparent-background')
  for (const pack of resourcePackPaths(config.resourcePackPaths)) rendererArgs.push('--resource-pack', resolve(pack))
  const javaCommand = await resolveStandaloneJavaCommand(config.javaPath || config.standaloneJavaCommand || '')

  for (let attempt = 0; attempt <= config.standaloneJavaMemoryRestartLimit; attempt++) {
    const heap = attempt === 0 ? config.standaloneJavaMaxHeapMb : config.standaloneJavaRetryMaxHeapMb
    const result = await runStandaloneJava(javaCommand, [...standaloneJvmOptions(heap), ...rendererArgs], config.standaloneRenderTimeout)
    if (result.code === 0) return
    const memoryFailure = isJavaMemoryFailure(result.stderr, result.code)
    if (memoryFailure && attempt < config.standaloneJavaMemoryRestartLimit) {
      logger.warn(`独立 Java 渲染超出 ${config.standaloneJavaMaxHeapMb} MiB 堆内存限制；正在启动全新进程重试（${attempt + 1}/${config.standaloneJavaMemoryRestartLimit}）`)
      const outputs = mode === 'six-face' ? ['six-faces.png'] : ['isometric.png', 'isometric-reverse.png']
      await Promise.all(outputs.map(file => fs.rm(join(output, file), { force: true })))
      continue
    }
    const memoryHint = memoryFailure
      ? `；Java 内存不足（本次最大堆 ${heap} MiB，已重启 ${attempt} 次）`
      : ''
    throw new Error(`独立 Java 渲染器退出码 ${result.code}${memoryHint}${result.stderr ? `：${result.stderr.trim()}` : ''}`)
  }
  } finally {
    releaseRenderSlot()
  }
}

async function runStandaloneJava(command: string, args: string[], timeout: number) {
  return new Promise<{ code: number | null, stderr: string }>((resolveRun, reject) => {
    const child = spawn(command, args, { shell: false, windowsHide: true })
    activeStandaloneJavaProcesses.add(child)
    let stderr = ''
    let settled = false
    const cleanup = () => activeStandaloneJavaProcesses.delete(child)
    child.stderr?.on('data', chunk => { stderr = (stderr + String(chunk)).slice(-6000) })
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      void (async () => {
        await terminateStandaloneJavaProcess(child)
        cleanup()
        reject(new Error('Standalone Java render timed out; process terminated and memory reclaimed'))
      })()
      return
      reject(new Error(`独立 Java 渲染超过 ${Math.round(timeout / 1000)} 秒；Java 进程已终止并回收内存`))
    }, timeout)
    child.once('error', error => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      void terminateStandaloneJavaProcess(child)
      cleanup()
      reject(error)
    })
    child.once('close', code => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      cleanup()
      resolveRun({ code, stderr })
    })
  })
}

async function terminateStandaloneJavaProcess(child: ChildProcess) {
  const pid = child.pid
  if (!pid) return
  if (process.platform === 'win32') {
    await new Promise<void>(resolveTaskkill => {
      const killer = spawn('taskkill.exe', ['/PID', String(pid), '/T', '/F'], { windowsHide: true })
      killer.once('close', () => resolveTaskkill())
      killer.once('error', () => resolveTaskkill())
    })
  }
  if (child.exitCode == null && !child.killed) child.kill()
}

async function terminateActiveStandaloneJavaProcesses(logger: ReturnType<Context['logger']>) {
  const processes = [...activeStandaloneJavaProcesses]
  if (!processes.length) return
  await Promise.all(processes.map(process => terminateStandaloneJavaProcess(process)))
  logger.info(`已终止 ${processes.length} 个独立渲染 Java 进程并回收其子进程`)
}

async function installBundledGpuClient(gameDirectory: string, logger: ReturnType<Context['logger']>) {
  const game = resolve(gameDirectory)
  const source = resolve(join(__dirname, `../assets/gpu-client/${GPU_CLIENT_JAR_NAME}`))
  if (!(await exists(source))) {
    logger.warn(`内置 GPU 客户端 JAR 不存在：${source}`)
    return
  }
  if (!(await exists(game))) {
    logger.warn(`GPU 客户端游戏目录不存在：${game}`)
    return
  }
  const mods = join(game, 'mods')
  const destination = join(mods, GPU_CLIENT_JAR_NAME)
  try {
    await fs.mkdir(mods, { recursive: true })
    const sourceHash = await fileSha256(source)
    const destinationHash = await fileSha256(destination)
    if (sourceHash !== destinationHash) {
      await fs.copyFile(source, destination)
    }
    for (const entry of await fs.readdir(mods)) {
      if (entry === GPU_CLIENT_JAR_NAME || !/^quickcraft-mc26\.2-.*\.jar$/i.test(entry)) continue
      const path = join(mods, entry)
      const disabled = `${path}.disabled`
      if (!(await exists(disabled))) await fs.rename(path, disabled)
    }
    logger.info(`Minecraft 26.2 GPU 渲染端已安装：${destination}`)
  } catch (error) {
    logger.warn(`GPU 渲染端安装失败，请关闭 Minecraft 后重载插件：${error instanceof Error ? error.message : String(error)}`)
  }
}

async function fileSha256(path: string) {
  try {
    return createHash('sha256').update(await fs.readFile(path)).digest('hex')
  } catch (error: any) {
    if (error?.code === 'ENOENT') return undefined
    throw error
  }
}

interface JavaBridgeStatus {
  timestamp?: number
  inWorld?: boolean
  resourcePacks?: string[]
  renderer?: string
  rendererVersion?: string
}

interface JavaBridgeResult {
  success?: boolean
  error?: string
}

async function renderWithJavaBridge(input: string, output: string, config: Config) {
  const quickCraftGpu = config.renderEngine === 'gpuClient'
  if (quickCraftGpu && !config.gpuClientGameDirectory?.trim()) {
    throw new Error('Minecraft 26.2 GPU 客户端游戏目录未配置')
  }
  if (!quickCraftGpu && !config.javaBridgeDirectory.trim()) {
    throw new Error('Fabric Java 渲染桥任务目录未配置；使用 java 后端时请填写 javaBridgeDirectory')
  }
  const bridge = quickCraftGpu
    ? join(resolve(config.gpuClientGameDirectory), 'quickcraft-render-bridge')
    : resolve(config.javaBridgeDirectory)
  const status = await readJson<JavaBridgeStatus>(join(bridge, 'status.json'))
  if (!status?.timestamp || Date.now() - status.timestamp > 5000) {
    throw new Error(quickCraftGpu
      ? 'Minecraft 26.2 GPU 渲染端未运行；请启动已配置的 Fabric 客户端，无需手动进入存档'
      : 'Minecraft Java 渲染桥未运行；请启动 1.21.1-Fabric 客户端并进入一个世界')
  }
  if (!quickCraftGpu && !status.inWorld) throw new Error('Minecraft Java 渲染桥已启动，但客户端尚未进入世界')

  if (!quickCraftGpu) {
    const packs = status.resourcePacks ?? []
    const required = [
      ['XeKr', '3.6forMC1.20.2~1.21.5.zip'],
      ['XKRDA', '1.19.4~1.21snapshot.zip'],
    ]
    if (!required.every(parts => packs.some(id => parts.every(part => id.includes(part))))) {
      throw new Error('Minecraft 客户端没有同时启用 XeKr 红显基础包和 XKRDA 附加包')
    }
  }

  const id = randomUUID()
  const jobs = join(bridge, 'jobs')
  const resultPath = join(bridge, 'results', `${id}.result.json`)
  const jobPath = join(jobs, `${id}.job.json`)
  const temporaryPath = `${jobPath}.tmp`
  await fs.mkdir(jobs, { recursive: true })
  await fs.rm(resultPath, { force: true })
  await fs.writeFile(temporaryPath, JSON.stringify({
    id,
    input: resolve(input),
    outputDirectory: resolve(output),
    resolution: effectiveRenderResolution(config),
    supersampling: config.javaSupersampling,
    rotation: config.isometricRotation,
    pitch: config.isometricSlant,
    slant: config.isometricSlant,
    fill: config.isometricFill,
    background: config.background,
    transparentBackground: config.transparentBackground,
  }))
  await fs.rename(temporaryPath, jobPath)

  const deadline = Date.now() + config.javaRenderTimeout
  while (Date.now() < deadline) {
    const result = await readJson<JavaBridgeResult>(resultPath)
    if (result) {
      await fs.rm(resultPath, { force: true })
      if (!result.success) throw new Error(`Minecraft Java 渲染失败：${result.error ?? '未知错误'}`)
      return
    }
    await new Promise(resolveDelay => setTimeout(resolveDelay, 200))
  }
  throw new Error(`Minecraft Java 渲染超过 ${Math.round(config.javaRenderTimeout / 1000)} 秒`)
}

export function createGpuRenderRequest(filename: string, config: Pick<Config,
  'outputSize' | 'background' | 'transparentBackground' | 'javaSupersampling'
  | 'isometricRotation' | 'isometricSlant' | 'isometricFill'>,
  source?: { group?: string, user?: string }): GpuRenderRequest {
  const size = effectiveRenderResolution(config)
  const view = (id: string, name: string, yaw: number): RenderView => ({
    id,
    name,
    yaw,
    pitch: config.isometricSlant,
    zoom: Math.max(0.05, config.isometricFill / 0.95),
    autoFill: true,
    width: size,
    height: size,
    background: config.background,
    transparentBackground: config.transparentBackground,
    supersampling: config.javaSupersampling,
  })
  return {
    version: 2,
    id: randomUUID(),
    filename: basename(filename),
    views: [
      view('isometric', '正二轴测', config.isometricRotation),
      view('isometric-reverse', '反向正二轴测（旋转 180°）', config.isometricRotation + 180),
    ],
    sourceGroup: source?.group,
    sourceUser: source?.user,
  }
}

export function createRemoteAgentSignature(secret: string, timestamp: string, nonce: string, body: Buffer) {
  return createHmac('sha256', secret)
    .update(timestamp).update('.').update(nonce).update('.').update(body).digest('hex')
}

async function renderWithRemoteAgent(bytes: Buffer, output: string, filename: string, config: Config) {
  const endpoint = config.remoteAgentUrl.trim()
  const secret = config.remoteAgentSecret.trim()
  if (!endpoint) throw new Error('Remote GPU Agent URL 未配置')
  if (!secret) throw new Error('Remote GPU Agent HMAC 密钥未配置')
  let target: URL
  try { target = new URL(endpoint) } catch { throw new Error(`Remote GPU Agent URL 无效：${endpoint}`) }
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('Remote GPU Agent URL 必须使用 HTTP(S)')
  const id = randomUUID()
  const body = Buffer.from(JSON.stringify({
    version: 1,
    id,
    filename: basename(filename),
    litematicBase64: bytes.toString('base64'),
    options: {
      outputSize: effectiveRenderResolution(config),
      background: config.background,
      transparentBackground: config.transparentBackground,
      supersampling: config.javaSupersampling,
      rotation: config.isometricRotation,
      slant: config.isometricSlant,
      fill: config.isometricFill,
      timeout: config.remoteAgentTimeout,
    },
  }))
  const timestamp = String(Date.now())
  const nonce = randomUUID()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), config.remoteAgentTimeout)
  let raw: Response
  try {
    raw = await fetch(target, {
      method: 'POST', body, signal: controller.signal,
      headers: {
        'content-type': 'application/json',
        'x-litematic-agent-timestamp': timestamp,
        'x-litematic-agent-nonce': nonce,
        'x-litematic-agent-signature': createRemoteAgentSignature(secret, timestamp, nonce, body),
      },
    })
  } catch (error) {
    throw new Error(`Remote GPU Agent 请求失败：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    clearTimeout(timer)
  }
  if (!raw.ok) throw new Error(`Remote GPU Agent HTTP ${raw.status}: ${(await raw.text()).slice(0, 500)}`)
  const response = await raw.json() as any
  if (response?.version !== 1 || response.id !== id || !Array.isArray(response.images)) {
    throw new Error('Remote GPU Agent 返回了无效响应')
  }
  const accepted = new Set(['isometric.png', 'isometric-reverse.png'])
  const written = new Set<string>()
  for (const image of response.images) {
    if (!accepted.has(image?.title) || typeof image?.base64 !== 'string') continue
    const png = Buffer.from(image.base64, 'base64')
    if (png.length < 8 || !png.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) continue
    await fs.writeFile(join(output, image.title), png)
    written.add(image.title)
  }
  if (written.size !== accepted.size) throw new Error('Remote GPU Agent 返回的 PNG 不完整')
}

async function readJson<T>(path: string): Promise<T | undefined> {
  try { return JSON.parse(await fs.readFile(path, 'utf8')) as T } catch (error: any) {
    if (error?.code === 'ENOENT' || error instanceof SyntaxError) return
    throw error
  }
}

async function mergeRenderDiagnostics(output: string, destination: string, logger: ReturnType<Context['logger']>) {
  const sourcePath = join(output, 'render-diagnostics.json')
  const source = await readJson<any>(sourcePath)
  if (!source) return
  const targetPath = resolve(destination)
  const existing = await readJson<any>(targetPath) ?? { format: 2, generatedAt: new Date().toISOString(), blocks: [], errors: [] }
  const entries = new Map<string, any>()
  for (const entry of existing.blocks ?? []) entries.set(`${entry.state}\u0000${entry.reason}`, entry)
  for (const entry of source.blocks ?? []) {
    const key = `${entry.state}\u0000${entry.reason}`
    const previous = entries.get(key)
    if (!previous) entries.set(key, { ...entry, samples: entry.samples ?? [] })
    else {
      previous.count = Number(previous.count ?? 0) + Number(entry.count ?? 0)
      previous.samples = [...(previous.samples ?? []), ...(entry.samples ?? [])].slice(0, 20)
    }
  }
  const errors = new Map<string, any>()
  for (const entry of existing.errors ?? []) errors.set(`${entry.renderId}\u0000${entry.engine}\u0000${entry.message}`, entry)
  for (const entry of source.errors ?? []) {
    const key = `${entry.renderId}\u0000${entry.engine}\u0000${entry.message}`
    const previous = errors.get(key)
    if (!previous) errors.set(key, { ...entry })
    else previous.count = Number(previous.count ?? 0) + Number(entry.count ?? 0)
  }
  const result = { format: 2, pluginVersion: PLUGIN_VERSION, generatedAt: new Date().toISOString(), blocks: [...entries.values()], errors: [...errors.values()] }
  await fs.mkdir(resolve(destination, '..'), { recursive: true }).catch(() => undefined)
  await fs.writeFile(targetPath, JSON.stringify(result, null, 2) + '\n')
  logger.warn(`记录 ${source.blocks?.length ?? 0} 类无法正常渲染的方块；诊断已保存到 ${targetPath}`)
}

async function rotateDiagnosticsFile(path: string, archiveDirectory: string, logger: ReturnType<Context['logger']>) {
  const document = await readJson<any>(path)
  if (!document || document.pluginVersion === PLUGIN_VERSION) return
  await fs.mkdir(archiveDirectory, { recursive: true })
  const oldVersion = cachePathSegment(typeof document.pluginVersion === 'string' ? document.pluginVersion : 'legacy')
  const stamp = formatDiagnosticsTimestamp(new Date())
  const target = join(archiveDirectory, `litematic-renderer-diagnostics-v${oldVersion}-${stamp}.json`)
  await fs.rename(path, target)
  logger.info(`已将旧版诊断汇总归档到 ${target}`)
}

async function disableDiagnosticsSnapshot(path: string, disabledDirectory: string) {
  const document = await readJson<any>(path)
  if (!document) return '当前没有可禁用的诊断汇总。'
  await fs.mkdir(disabledDirectory, { recursive: true })
  const target = join(disabledDirectory, `litematic-renderer-diagnostics-disabled-${formatDiagnosticsTimestamp(new Date())}.json`)
  await fs.rename(path, target)
  await fs.writeFile(path, JSON.stringify({ format: 2, pluginVersion: PLUGIN_VERSION, generatedAt: new Date().toISOString(), blocks: [], errors: [] }, null, 2) + '\n')
  return `当前诊断已移入禁用文件夹：${target}`
}

async function appendGlobalRenderError(destination: string, renderId: string, engine: string, message: string, logger: ReturnType<Context['logger']>) {
  const path = resolve(destination)
  const document = await readJson<any>(path) ?? { format: 2, pluginVersion: PLUGIN_VERSION, generatedAt: new Date().toISOString(), blocks: [], errors: [] }
  document.format = 2
  document.pluginVersion = PLUGIN_VERSION
  document.errors ??= []
  const existing = document.errors.find((entry: any) => entry.renderId === renderId && entry.engine === engine && entry.message === message)
  if (existing) existing.count = Number(existing.count ?? 0) + 1
  else document.errors.push({ renderId, engine, message, count: 1, generatedAt: new Date().toISOString() })
  await fs.mkdir(dirname(path), { recursive: true })
  await fs.writeFile(path, JSON.stringify(document, null, 2) + '\n')
  logger.warn(`渲染问题已记录到 ${path}`)
}

function formatDiagnosticsTimestamp(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
}

async function appendRenderError(output: string, destination: string, engine: Config['renderEngine'], error: unknown, logger: ReturnType<Context['logger']>) {
  const path = join(output, 'render-diagnostics.json')
  const document = await readJson<any>(path) ?? { format: 2, generatedAt: new Date().toISOString(), blocks: [], errors: [] }
  document.format = 2
  document.errors ??= []
  const message = error instanceof Error ? error.message : String(error)
  const renderId = basename(output)
  const existing = document.errors.find((entry: any) => entry.renderId === renderId && entry.engine === engine && entry.message === message)
  if (existing) existing.count = Number(existing.count ?? 0) + 1
  else document.errors.push({ renderId, engine, message, count: 1, generatedAt: new Date().toISOString() })
  await fs.writeFile(path, JSON.stringify(document, null, 2) + '\n')
  await mergeRenderDiagnostics(output, destination, logger)
}

export function formatRenderError(error: unknown, config: Pick<Config, 'maxFileSize'>) {
  const message = error instanceof Error ? error.message : String(error)
  if (/投影方块数或区域体积超过/i.test(message)) {
    return `${message}，不渲染。`
  }
  if (/文件超过|file size|file.*limit/i.test(message)) {
    return `文件大小超过 ${(config.maxFileSize / 1024).toFixed(2)} MB，不渲染。`
  }
  if (/java 21\+|未找到.*java|java.*not found/i.test(message)) {
    return '未找到 Java 21+，请在插件配置中检查 Java 可执行文件设置。'
  }
  if (/standalone renderer|独立.*渲染器.*不存在|渲染器.*not exist/i.test(message)) {
    return '独立渲染器不可用，请重装插件或检查 Java 渲染配置。'
  }
  if (/minecraft.*resource|resource pack|原版资源|材质包.*不存在/i.test(message)) {
    return 'Minecraft 原版资源包不可用，请检查资源包配置。'
  }
  if (/Minecraft 26\.2 GPU 渲染端未运行/i.test(message)) {
    return '请启动 Minecraft 26.2-Fabricjqr GPU 渲染客户端，停在主菜单即可，无需手动进入存档。'
  }
  return '投影渲染失败，请检查渲染器配置或导出诊断文件。'
}

async function renderWithGpu(command: string, input: string, output: string, timeout: number, logger: ReturnType<Context['logger']>) {
  try {
    await new Promise<void>((resolveRun, reject) => {
      const child = spawn(command, ['--input', input, '--output', output, '--views', 'isometric,isometric-reverse'], { shell: false, windowsHide: true })
      const timer = setTimeout(() => { child.kill(); reject(new Error('GPU 渲染超时')) }, timeout)
      child.once('error', reject)
      child.once('exit', code => code === 0 ? resolveRun() : reject(new Error(`GPU 渲染器退出码：${code}`)))
      child.once('close', () => clearTimeout(timer))
    })
    return true
  } catch (error) {
    logger.warn(`GPU 渲染器不可用，回退 CPU：${error instanceof Error ? error.message : String(error)}`)
    return false
  }
}

async function renderWithWebgl(ctx: Context, data: Buffer, config: Config): Promise<RenderedImage[]> {
  const page = await (ctx as any).puppeteer.page()
  try {
    const viewer = pathToFileURL(resolve(__dirname, '../assets/threeviews/index.html')).href
    await page.goto(viewer, { waitUntil: 'networkidle0', timeout: config.renderTimeout })
    await page.waitForFunction(() => Boolean((window as any).litematicViewerAPI && (window as any).deepslateResources), { timeout: config.renderTimeout })
    const views = await page.evaluate(async ({ base64, width, height, quality, isometricSquare, isometricFill, isometricRotation, isometricSlant }: { base64: string, width: number, height: number, quality: string, isometricSquare: boolean, isometricFill: number, isometricRotation: number, isometricSlant: number }) => {
      const binary = atob(base64)
      const bytes = new Uint8Array(binary.length)
      for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
      const file = new File([bytes], 'schematic.litematic', { type: 'application/octet-stream' })
      await (window as any).litematicViewerAPI.loadFromFile(file)
      return (window as any).litematicViewerAPI.renderIsometricViews({ width, height, quality, isometricSquare, isometricFill, isometricRotation, isometricSlant })
    }, { base64: data.toString('base64'), width: effectiveRenderResolution(config), height: effectiveRenderResolution(config), quality: config.webglQuality, isometricSquare: config.isometricSquare, isometricFill: config.isometricFill, isometricRotation: config.isometricRotation, isometricSlant: config.isometricSlant })
    return [
      { title: 'isometric.png', png: decodeDataUrl(views.isometricView) },
      { title: 'isometric-reverse.png', png: decodeDataUrl(views.reverseIsometricView) },
    ]
  } finally {
    await page.close().catch(() => undefined)
  }
}

function decodeDataUrl(value: unknown) {
  if (typeof value !== 'string' || !value.startsWith('data:image/')) throw new Error('WebGL 渲染器没有返回 PNG 数据')
  const comma = value.indexOf(',')
  if (comma < 0) throw new Error('WebGL 渲染器返回了无效图像数据')
  return Buffer.from(value.slice(comma + 1), 'base64')
}

export interface ResolvedSendOptions {
  qqBotType: QqBotType
  sendMode: SendMode
  replyAndMention: boolean
  showViewTitles: boolean
  sixFaceOverview: boolean
}

type SendConfig = Pick<Config, 'sendAsForward' | 'replyAndMention' | 'groupSendOptions'>
  & Partial<Pick<Config, 'qqBotType' | 'showViewTitles' | 'sixFaceOverview'>>

export function resolveSendOptions(config: SendConfig, groupId?: string): ResolvedSendOptions {
  const override = groupId
    ? [...config.groupSendOptions].reverse().find(item => item.groupId.trim() === groupId)
    : undefined
  const replyAndMention = override?.replyAndMention === 'enabled'
    ? true
    : override?.replyAndMention === 'disabled'
      ? false
      : config.replyAndMention
  const sendMode = override?.sendMode ?? (config.sendAsForward ? 'forward' : 'combined')
  const qqBotType = config.qqBotType ?? 'selfHosted'
  return {
    qqBotType,
    sendMode,
    replyAndMention,
    showViewTitles: config.showViewTitles ?? false,
    sixFaceOverview: (qqBotType === 'official' || sendMode === 'forward') && (config.sixFaceOverview ?? true),
  }
}

export async function sendImages(session: Session, images: ImageResult[], metadata: string, options: ResolvedSendOptions, projectionName = 'schematic') {
  const messages = images.map(({ title, path }) => h('message', { userId: session.selfId, nickname: '投影渲染' }, [
    ...(options.showViewTitles ? [h('text', { content: title })] : []), h.image(path),
  ]))
  const metadataMessage = h('message', { userId: session.selfId, nickname: '投影信息' }, [h('text', { content: metadata })])
  const reply = options.replyAndMention ? replyElements(session, options.qqBotType) : []
  // 「结果如上」仅自建 QQ 开启合并转发时提示准确；官方 QQ 不发成功文案，只发图和投影信息
  const successMessage = options.qqBotType === 'selfHosted' && options.sendMode === 'forward'
    ? `${projectionName} 已渲染成功，结果如上`
    : `${projectionName} 已渲染成功`
  if (options.qqBotType === 'official') {
    const overviewPath = await composeQqOverview(images)
    const message = [
      ...(options.replyAndMention && session.messageId ? [h('quote', { id: session.messageId })] : []),
      h.image(pathToFileURL(overviewPath).href),
      ...(metadata ? [h('text', { content: metadata })] : []),
    ]
    await session.send(message)
    return
  }
  if (options.sendMode === 'forward') {
    await session.send(h('figure', {}, [...messages, metadataMessage]))
    await session.send(options.replyAndMention
      ? [...reply, h('text', { content: successMessage })]
      : successMessage)
    return
  }
  const combined = images.flatMap(({ title, path }, index) => [
    ...(options.showViewTitles ? [h('text', { content: `${index ? '\n' : ''}${title}\n` })] : []),
    h.image(path),
  ])
  await session.send([...reply, ...combined, h('text', { content: `\n${metadata}\n${successMessage}` })])
}

export async function composeQqOverview(images: ImageResult[]) {
  if (!images.length) throw new Error('没有可合并的渲染图片')
  const sources = await Promise.all(images.map(async image => PNG.sync.read(await fs.readFile(resolve(image.path)))))
  const width = Math.max(...sources.map(image => image.width))
  const gap = Math.max(4, Math.round(width * 0.01))
  const placements: Array<{ image: PNG, x: number, y: number, width: number, height: number }> = []
  let y = 0
  const top = sources.slice(0, Math.min(2, sources.length))
  const topWidth = top.length === 1 ? width : Math.floor((width - gap) / 2)
  const topHeights = top.map(image => Math.max(1, Math.round(image.height * topWidth / image.width)))
  const topHeight = Math.max(...topHeights)
  for (let index = 0; index < top.length; index++) {
    placements.push({
      image: top[index],
      x: index * (topWidth + gap),
      y: Math.floor((topHeight - topHeights[index]) / 2),
      width: topWidth,
      height: topHeights[index],
    })
  }
  y += topHeight
  for (const image of sources.slice(2)) {
    y += gap
    const height = Math.max(1, Math.round(image.height * width / image.width))
    placements.push({ image, x: 0, y, width, height })
    y += height
  }
  const output = new PNG({ width, height: y })
  for (const placement of placements) drawScaledPng(output, placement)
  const outputPath = resolve(dirname(images[0].path), 'qq-overview.png')
  await fs.writeFile(outputPath, PNG.sync.write(output))
  return outputPath
}

function drawScaledPng(target: PNG, placement: { image: PNG, x: number, y: number, width: number, height: number }) {
  const { image, x: targetX, y: targetY, width, height } = placement
  for (let y = 0; y < height; y++) {
    const sourceY = Math.min(image.height - 1, Math.floor((y + 0.5) * image.height / height))
    for (let x = 0; x < width; x++) {
      const sourceX = Math.min(image.width - 1, Math.floor((x + 0.5) * image.width / width))
      const sourceOffset = (sourceY * image.width + sourceX) * 4
      const targetOffset = ((targetY + y) * target.width + targetX + x) * 4
      target.data[targetOffset] = image.data[sourceOffset]
      target.data[targetOffset + 1] = image.data[sourceOffset + 1]
      target.data[targetOffset + 2] = image.data[sourceOffset + 2]
      target.data[targetOffset + 3] = image.data[sourceOffset + 3]
    }
  }
}

export function replyElements(session: Session, qqBotType: QqBotType = session.platform === 'qq' ? 'official' : 'selfHosted') {
  const result = []
  if (session.messageId) result.push(h('quote', { id: session.messageId }))
  if (qqBotType === 'selfHosted' && session.userId) {
    result.push(h('at', { id: session.userId }))
    result.push(h('text', { content: '\n' }))
  }
  return result
}

// Minimal NBT reader: Litematica uses a gzip-compressed named compound.
class NbtReader {
  private offset = 0
  constructor(private readonly data: Buffer) {}
  private byte() { return this.data.readInt8(this.offset++) }
  private ubyte() { return this.data.readUInt8(this.offset++) }
  private short() { const value = this.data.readInt16BE(this.offset); this.offset += 2; return value }
  private int() { const value = this.data.readInt32BE(this.offset); this.offset += 4; return value }
  private float() { const value = this.data.readFloatBE(this.offset); this.offset += 4; return value }
  private double() { const value = this.data.readDoubleBE(this.offset); this.offset += 8; return value }
  private long() { const value = this.data.readBigInt64BE(this.offset); this.offset += 8; return value }
  private text() { const length = this.data.readUInt16BE(this.offset); this.offset += 2; const value = this.data.toString('utf8', this.offset, this.offset + length); this.offset += length; return value }
  readNamedRoot() { if (this.ubyte() !== 10) throw new Error('不是 NBT Compound 数据'); this.text(); return this.value(10) as Record<string, unknown> }
  private value(type: number): unknown {
    switch (type) {
      case 0: return null
      case 1: return this.byte()
      case 2: return this.short()
      case 3: return this.int()
      case 4: return this.long()
      case 5: return this.float()
      case 6: return this.double()
      case 7: { const count = this.int(); const result = this.data.subarray(this.offset, this.offset + count); this.offset += count; return result }
      case 8: return this.text()
      case 9: { const element = this.ubyte(), count = this.int(); return Array.from({ length: count }, () => this.value(element)) }
      case 10: { const result: Record<string, unknown> = {}; for (;;) { const child = this.ubyte(); if (!child) return result; result[this.text()] = this.value(child) } }
      case 11: { const count = this.int(); return Array.from({ length: count }, () => this.int()) }
      case 12: { const count = this.int(); return Array.from({ length: count }, () => this.long()) }
      default: throw new Error(`不支持的 NBT 标签类型：${type}`)
    }
  }
}

const MINECRAFT_DATA_VERSIONS = new Map<number, string>([
  [4903, '26.2'],
  [4790, '26.1.2'],
  [4671, '1.21.11'],
  [4557, '1.21.10'],
  [4555, '1.21.9'],
  [4440, '1.21.8'],
  [4438, '1.21.7'],
  [4435, '1.21.6'],
  [4325, '1.21.5'],
  [4189, '1.21.4'],
  [4082, '1.21.3'],
  [4080, '1.21.2'],
  [3955, '1.21.1'],
  [3953, '1.21'],
  [3839, '1.20.6'],
  [3837, '1.20.5'],
  [3700, '1.20.4'],
  [3698, '1.20.3'],
  [3578, '1.20.2'],
  [3465, '1.20.1'],
  [3463, '1.20'],
  [3337, '1.19.4'],
  [3218, '1.19.3'],
  [3120, '1.19.2'],
  [3117, '1.19.1'],
  [3105, '1.19'],
  [2975, '1.18.2'],
])

function readLitematicRoot(data: Buffer) {
  let raw: Buffer
  try { raw = gunzipSync(data) } catch { raw = data }
  return new NbtReader(raw).readNamedRoot()
}


function compoundValue(value: unknown) {
  return value && typeof value === 'object' && !Array.isArray(value) && !Buffer.isBuffer(value)
    ? value as Record<string, unknown>
    : undefined
}

function numericValue(value: unknown) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'bigint') {
    const numeric = Number(value)
    if (Number.isSafeInteger(numeric)) return numeric
  }
}

function readMetadataSize(root: Record<string, unknown>, metadata: Record<string, unknown>): [number, number, number] | undefined {
  const enclosing = compoundValue(metadata.EnclosingSize)
  const direct = enclosing && [numericValue(enclosing.x), numericValue(enclosing.y), numericValue(enclosing.z)]
  if (direct?.every(value => value != null)) return direct.map(value => Math.abs(value!)) as [number, number, number]

  const regions = compoundValue(root.Regions)
  if (!regions) return
  let minX = Infinity, minY = Infinity, minZ = Infinity
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity
  for (const regionValue of Object.values(regions)) {
    const region = compoundValue(regionValue)
    const position = compoundValue(region?.Position)
    const size = compoundValue(region?.Size)
    const x = numericValue(position?.x), y = numericValue(position?.y), z = numericValue(position?.z)
    const sx = numericValue(size?.x), sy = numericValue(size?.y), sz = numericValue(size?.z)
    if ([x, y, z, sx, sy, sz].some(value => value == null) || sx === 0 || sy === 0 || sz === 0) continue
    const endX = x! + sx! - Math.sign(sx!), endY = y! + sy! - Math.sign(sy!), endZ = z! + sz! - Math.sign(sz!)
    minX = Math.min(minX, x!, endX); minY = Math.min(minY, y!, endY); minZ = Math.min(minZ, z!, endZ)
    maxX = Math.max(maxX, x!, endX); maxY = Math.max(maxY, y!, endY); maxZ = Math.max(maxZ, z!, endZ)
  }
  if (!Number.isFinite(minX)) return
  return [maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1]
}

function formatCreatedAt(value: number | undefined) {
  if (value == null || value <= 0) return '未知'
  const milliseconds = value < 10_000_000_000 ? value * 1000 : value
  const date = new Date(milliseconds)
  if (Number.isNaN(date.getTime())) return '未知'
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day} ${values.hour}:${values.minute}:${values.second}`
}

export function parseLitematicMetadata(data: Buffer): LitematicMetadata {
  const root = readLitematicRoot(data)
  const metadata = compoundValue(root.Metadata) ?? {}
  const minecraftDataVersion = numericValue(root.MinecraftDataVersion)
  return {
    author: typeof metadata.Author === 'string' && metadata.Author.trim() ? metadata.Author.trim() : '未知',
    createdAt: formatCreatedAt(numericValue(metadata.TimeCreated)),
    totalBlocks: numericValue(metadata.TotalBlocks),
    totalVolume: numericValue(metadata.TotalVolume),
    size: readMetadataSize(root, metadata),
    litematicVersion: numericValue(root.Version),
    minecraftDataVersion,
    minecraftVersion: minecraftDataVersion == null ? undefined : MINECRAFT_DATA_VERSIONS.get(minecraftDataVersion),
  }
}

export function formatLitematicMetadata(metadata: LitematicMetadata, filename?: string) {
  const blocks = metadata.totalBlocks == null ? '未知' : String(metadata.totalBlocks)
  const volume = metadata.totalVolume == null ? '未知' : String(metadata.totalVolume)
  const size = metadata.size?.join(' × ') ?? '未知'
  const litematicVersion = metadata.litematicVersion == null ? '未知' : String(metadata.litematicVersion)
  const gameVersion = metadata.minecraftVersion ?? '未知'
  const dataVersion = metadata.minecraftDataVersion == null ? '' : `（数据版本：${metadata.minecraftDataVersion}）`
  const projectionName = typeof filename === 'string' && filename.trim()
    ? projectionNameFromFilename(filename)
    : ''
  return [
    ...(projectionName ? [`投影名称：${projectionName}`] : []),
    `保存者游戏 ID：${metadata.author}`,
    `创建时间：${metadata.createdAt}`,
    `方块数/体积：${blocks}/${volume}`,
    `尺寸：${size}`,
    `Litematic 版本：${litematicVersion}`,
    `游戏版本：${gameVersion}${dataVersion}`,
  ].join('\n')
}

export function parseLitematic(data: Buffer) {
  const root = readLitematicRoot(data)
  const regions = root.Regions as Record<string, Record<string, unknown>> | undefined
  if (!regions || typeof regions !== 'object') throw new Error('文件不含 Litematica Regions 数据')
  const blocks: Block[] = []
  for (const region of Object.values(regions)) parseRegion(region, blocks)
  if (!blocks.length) throw new Error('投影中没有非空气方块')
  return blocks
}

function parseRegion(region: Record<string, unknown>, target: Block[]) {
  const position = region.Position as Record<string, number>
  const size = region.Size as Record<string, number>
  const palette = region.BlockStatePalette as Array<Record<string, string>>
  const states = region.BlockStates as bigint[]
  if (!position || !size || !Array.isArray(palette) || !Array.isArray(states)) return
  const sx = Math.abs(size.x), sy = Math.abs(size.y), sz = Math.abs(size.z)
  const total = sx * sy * sz
  if (!total) return
  const bits = Math.max(2, Math.ceil(Math.log2(palette.length)))
  const dx = size.x < 0 ? -1 : 1, dy = size.y < 0 ? -1 : 1, dz = size.z < 0 ? -1 : 1
  for (let index = 0; index < total; index++) {
    const paletteIndex = unpackState(states, index, bits)
    const blockName = palette[paletteIndex]?.Name
    if (!blockName || blockName === 'minecraft:air' || blockName === 'minecraft:cave_air' || blockName === 'minecraft:void_air') continue
    const x = index % sx, z = Math.floor(index / sx) % sz, y = Math.floor(index / (sx * sz))
    target.push({ x: position.x + x * dx, y: position.y + y * dy, z: position.z + z * dz, name: blockName })
  }
}

function unpackState(words: bigint[], index: number, bits: number) {
  const bitIndex = BigInt(index * bits), word = Number(bitIndex / 64n), shift = bitIndex % 64n
  if (word >= words.length) return 0
  const mask = (1n << BigInt(bits)) - 1n
  let value = (BigInt.asUintN(64, words[word]) >> shift) & mask
  if (shift + BigInt(bits) > 64n && word + 1 < words.length) value |= (BigInt.asUintN(64, words[word + 1]) << (64n - shift)) & mask
  return Number(value)
}

export function renderSchematic(blocks: Block[], config: Config) {
  const bounds = getBounds(blocks)
  return [
    { title: 'isometric.png', png: renderIsometric(blocks, bounds, config, false) },
    { title: 'isometric-reverse.png', png: renderIsometric(blocks, bounds, config, true) },
  ]
}

function getBounds(blocks: Block[]): Bounds {
  return blocks.reduce((box, block) => ({ minX: Math.min(box.minX, block.x), minY: Math.min(box.minY, block.y), minZ: Math.min(box.minZ, block.z), maxX: Math.max(box.maxX, block.x), maxY: Math.max(box.maxY, block.y), maxZ: Math.max(box.maxZ, block.z) }), { minX: Infinity, minY: Infinity, minZ: Infinity, maxX: -Infinity, maxY: -Infinity, maxZ: -Infinity })
}

type OrthographicFace = 'up' | 'down' | 'east' | 'south' | 'west' | 'north'

const SIX_FACE_ORDER: OrthographicFace[] = ['up', 'down', 'east', 'south', 'west', 'north']
export const SIX_FACE_LABELS: Record<OrthographicFace, string[]> = {
  up: [
    '0000011000000000', '0000011000000000', '0000011000000000', '0000011000000000',
    '0000011111110000', '0000011111110000', '0000011000000000', '0000011000000000',
    '0000011000000000', '0000011000000000', '0000011000000000', '0000011000000000',
    '1111111111111000', '0000000000000000', '0000000000000000', '0000000000000000',
  ],
  down: [
    '0000000000000000', '1111111111111000', '0000011000000000', '0000011000000000',
    '0000011000000000', '0000011010000000', '0000011011000000', '0000011001100000',
    '0000011000110000', '0000011000000000', '0000011000000000', '0000011000000000',
    '0000011000000000', '0000000000000000', '0000000000000000', '0000000000000000',
  ],
  east: [
    '0000011000000000', '0000010000000000', '0111111111110000', '0000100000000000',
    '0001001100000000', '0011001100000000', '0110001100000000', '0111111111110000',
    '0000001100000000', '0001001101100000', '0011001100110000', '0110001100011000',
    '0100011000000000', '0000000000000000', '0000000000000000', '0000000000000000',
  ],
  south: [
    '0000001000000000', '1111111111111000', '0000001000000000', '0000001000000000',
    '1111111111110000', '1100100010010000', '1100110110010000', '1101111111010000',
    '1100001000010000', '1111111111110000', '1100001000010000', '1100001000010000',
    '1100001000110000', '0000000000000000', '0000000000000000', '0000000000000000',
  ],
  west: [
    '0000000000000000', '1111111111111000', '0000010100000000', '0000010100000000',
    '0111111111110000', '0100010100010000', '0100100100010000', '0100100100010000',
    '0101100111010000', '0101000000010000', '0100000000010000', '0111111111110000',
    '0100000000010000', '0000000000000000', '0000000000000000', '0000000000000000',
  ],
  north: [
    '0000100110000000', '0000100110000000', '0000100110000000', '0000100110000000',
    '1111100110110000', '0000100111100000', '0000100110000000', '0000100110000000',
    '0000100110000000', '0000100110000000', '0111100110001000', '1100100110001000',
    '0000100011111000', '0000000000000000', '0000000000000000', '0000000000000000',
  ],
}

export function renderSixFaceOverview(blocks: Block[], config: Config, layout: SixFaceLayout = config.sixFaceLayout ?? 'horizontal') {
  if (!blocks.length) throw new Error('投影中没有可生成六面图的方块')
  const outputSize = Math.max(128, Math.floor(config.outputSize))
  const gap = Math.max(4, Math.round(outputSize * 0.01))
  const columns = layout === 'vertical' ? 2 : 3
  const rows = layout === 'vertical' ? 3 : 2
  const tileSize = Math.floor((outputSize - gap * (layout === 'vertical' ? rows + 1 : columns + 1))
    / (layout === 'vertical' ? rows : columns))
  const width = layout === 'vertical' ? columns * tileSize + gap * (columns + 1) : outputSize
  const height = layout === 'vertical' ? outputSize : rows * tileSize + gap * (rows + 1)
  const canvas = new Raster(width, height, config)
  const bounds = getBounds(blocks)
  for (const [index, face] of SIX_FACE_ORDER.entries()) {
    const column = index % columns
    const row = Math.floor(index / columns)
    const x = gap + column * (tileSize + gap)
    const y = gap + row * (tileSize + gap)
    drawOrthographicFace(canvas, blocks, bounds, face, x, y, tileSize, gap)
    drawFaceLabel(canvas, face, x, y, tileSize, gap)
  }
  return canvas.png()
}

function drawOrthographicFace(canvas: Raster, blocks: Block[], bounds: Bounds, face: OrthographicFace,
  tileX: number, tileY: number, tileSize: number, gap: number) {
  const horizontalSpan = face === 'east' || face === 'west'
    ? bounds.maxZ - bounds.minZ + 1
    : bounds.maxX - bounds.minX + 1
  const verticalSpan = face === 'up' || face === 'down'
    ? bounds.maxZ - bounds.minZ + 1
    : bounds.maxY - bounds.minY + 1
  const visible = new Map<string, { block: Block, a: number, b: number, depth: number }>()
  for (const block of blocks) {
    const projected = orthographicCoordinates(block, bounds, face)
    const key = `${projected.a},${projected.b}`
    const old = visible.get(key)
    if (!old || projected.depth > old.depth) visible.set(key, { block, ...projected })
  }
  const padding = Math.max(2, Math.floor(gap / 2))
  const available = Math.max(1, tileSize - padding * 2)
  const scale = Math.min(available / horizontalSpan, available / verticalSpan)
  const renderedWidth = horizontalSpan * scale
  const renderedHeight = verticalSpan * scale
  const originX = tileX + padding + (available - renderedWidth) / 2
  const originY = tileY + padding + (available - renderedHeight) / 2
  const projectedBlocks = [...visible.values()].sort((first, second) => first.b - second.b || first.a - second.a)
  for (const { block, a, b } of projectedBlocks) {
    const x0 = Math.floor(originX + a * scale)
    const y0 = Math.floor(originY + b * scale)
    const x1 = Math.max(x0 + 1, Math.ceil(originX + (a + 1) * scale))
    const y1 = Math.max(y0 + 1, Math.ceil(originY + (b + 1) * scale))
    canvas.rect(x0, y0, x1 - x0, y1 - y0, colorFor(block.name))
  }
}

function orthographicCoordinates(block: Block, bounds: Bounds, face: OrthographicFace) {
  switch (face) {
    case 'up': return { a: block.x - bounds.minX, b: block.z - bounds.minZ, depth: block.y }
    case 'down': return { a: bounds.maxX - block.x, b: block.z - bounds.minZ, depth: -block.y }
    case 'east': return { a: bounds.maxZ - block.z, b: bounds.maxY - block.y, depth: block.x }
    case 'south': return { a: block.x - bounds.minX, b: bounds.maxY - block.y, depth: block.z }
    case 'west': return { a: block.z - bounds.minZ, b: bounds.maxY - block.y, depth: -block.x }
    case 'north': return { a: bounds.maxX - block.x, b: bounds.maxY - block.y, depth: -block.z }
  }
}

function drawFaceLabel(canvas: Raster, face: OrthographicFace, tileX: number, tileY: number, tileSize: number, gap: number) {
  const glyph = SIX_FACE_LABELS[face]
  const scale = Math.max(1, Math.min(6, Math.floor(tileSize / 80)))
  const padding = Math.max(1, Math.floor(scale / 2))
  const x = tileX + Math.max(2, Math.floor(gap / 2))
  const y = tileY + Math.max(2, Math.floor(gap / 2))
  canvas.rect(x, y, glyph[0].length * scale + padding * 2, glyph.length * scale + padding * 2, '#000000')
  for (let row = 0; row < glyph.length; row++) {
    for (let column = 0; column < glyph[row].length; column++) {
      if (glyph[row][column] === '1') {
        canvas.rect(x + padding + column * scale, y + padding + row * scale, scale, scale, '#ffffff')
      }
    }
  }
}

function renderIsometric(blocks: Block[], bounds: Bounds, config: Config, reverse: boolean) {
  const c = config.isometricCellSize, halfHeight = Math.ceil(c / 2)
  const width = (bounds.maxX - bounds.minX + bounds.maxZ - bounds.minZ + 2) * c + 4
  const height = (bounds.maxX - bounds.minX + bounds.maxZ - bounds.minZ + 2) * halfHeight + (bounds.maxY - bounds.minY + 2) * c + 4
  const scale = Math.max(1, Math.min(1, config.outputSize / Math.max(width, height)))
  const canvas = new Raster(Math.ceil(width * scale), Math.ceil(height * scale), config)
  const coordinate = (block: Block) => ({
    x: reverse ? bounds.maxX - block.x : block.x - bounds.minX,
    z: reverse ? bounds.maxZ - block.z : block.z - bounds.minZ,
  })
  const sorted = [...blocks].sort((a, b) => {
    const first = coordinate(a), second = coordinate(b)
    return (first.x + first.z + a.y) - (second.x + second.z + b.y)
  })
  for (const block of sorted) {
    const projected = coordinate(block)
    const x = (projected.x - projected.z) * c + width / 2
    const y = ((projected.x + projected.z) * halfHeight) + (bounds.maxY - block.y + 1) * c
    canvas.voxel(Math.round(x * scale), Math.round(y * scale), Math.max(2, Math.round(c * scale)), colorFor(block.name))
  }
  return canvas.png()
}

const COLORS: Record<string, string> = {
  stone: '#777777', cobblestone: '#707070', deepslate: '#4b4b50', dirt: '#855b37', grass_block: '#63913d', sand: '#dbca85', glass: '#a7d9df', water: '#3f76e4', lava: '#e05b22', oak_planks: '#b9915a', spruce_planks: '#765033', birch_planks: '#d9c485', iron_block: '#d7d7d7', gold_block: '#f6c543', diamond_block: '#5ed6c7', redstone_block: '#b21d1d', netherrack: '#823332', quartz_block: '#e9e4dc', obsidian: '#211d2d', white_wool: '#e9e9e9', black_wool: '#1d1d1d', red_wool: '#a12722', blue_wool: '#3549a7', green_wool: '#4c702c', yellow_wool: '#e5c440'
}
function colorFor(name: string) {
  const id = name.replace(/^minecraft:/, '')
  if (COLORS[id]) return COLORS[id]
  for (const [key, color] of Object.entries(COLORS)) if (id.includes(key)) return color
  const digest = createHash('md5').update(id).digest()
  return `#${(90 + digest[0] % 120).toString(16).padStart(2, '0')}${(90 + digest[1] % 120).toString(16).padStart(2, '0')}${(90 + digest[2] % 120).toString(16).padStart(2, '0')}`
}

class Raster {
  private data: Buffer
  private background: [number, number, number, number]
  constructor(readonly width: number, readonly height: number, config: Config) {
    this.background = parseColor(config.background, config.transparentBackground ? 0 : 255)
    this.data = Buffer.alloc(width * height * 4)
    for (let i = 0; i < this.data.length; i += 4) this.data.set(this.background, i)
  }
  rect(x: number, y: number, width: number, height: number, color: string) { for (let yy = Math.max(0, y); yy < Math.min(this.height, y + height); yy++) for (let xx = Math.max(0, x); xx < Math.min(this.width, x + width); xx++) this.pixel(xx, yy, color) }
  pixel(x: number, y: number, color: string) { if (x < 0 || y < 0 || x >= this.width || y >= this.height) return; this.data.set(parseColor(color), (y * this.width + x) * 4) }
  voxel(x: number, y: number, c: number, color: string) {
    const top = adjust(color, 1.15), left = adjust(color, .78), right = adjust(color, .6), h = Math.ceil(c / 2)
    for (let row = 0; row < h; row++) for (let col = -c + row; col <= c - row; col++) this.pixel(x + col, y - h + row, top)
    for (let row = 0; row < c; row++) for (let col = -c + Math.floor(row / 2); col <= 0; col++) this.pixel(x + col, y + row, left)
    for (let row = 0; row < c; row++) for (let col = 0; col <= c - Math.floor(row / 2); col++) this.pixel(x + col, y + row, right)
  }
  png() {
    const rows = Buffer.alloc((this.width * 4 + 1) * this.height)
    for (let y = 0; y < this.height; y++) { rows[y * (this.width * 4 + 1)] = 0; this.data.copy(rows, y * (this.width * 4 + 1) + 1, y * this.width * 4, (y + 1) * this.width * 4) }
    const header = Buffer.alloc(13); header.writeUInt32BE(this.width, 0); header.writeUInt32BE(this.height, 4); header.set([8, 6, 0, 0, 0], 8)
    return Buffer.concat([Buffer.from('\x89PNG\r\n\x1a\n', 'binary'), chunk('IHDR', header), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))])
  }
}
function parseColor(value: string, alpha = 255): [number, number, number, number] { const hex = /^#?([0-9a-f]{6})$/i.exec(value)?.[1] ?? '182026'; return [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16), alpha] }
function adjust(color: string, factor: number) { const [r, g, b] = parseColor(color); return `#${[r, g, b].map(c => Math.round(Math.min(255, c * factor)).toString(16).padStart(2, '0')).join('')}` }
function chunk(type: string, data: Buffer) { const name = Buffer.from(type); const size = Buffer.alloc(4); size.writeUInt32BE(data.length); const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([name, data])) >>> 0); return Buffer.concat([size, name, data, crc]) }
const CRC_TABLE = Array.from({ length: 256 }, (_, i) => { let c = i; for (let bit = 0; bit < 8; bit++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0 })
function crc32(data: Buffer) { let c = 0xffffffff; for (const byte of data) c = CRC_TABLE[(c ^ byte) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
