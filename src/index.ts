import { Context, h, Schema, Session } from 'koishi'
import { createHash, randomUUID } from 'node:crypto'
import { constants, promises as fs } from 'node:fs'
import { basename, extname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { spawn } from 'node:child_process'
import { deflateSync, gunzipSync } from 'node:zlib'

export const name = 'litematic-renderer'
export const inject = { optional: ['puppeteer'] }
const CACHE_FORMAT_VERSION = 10
const packageVersion = (require('../package.json') as { version?: unknown }).version
const PLUGIN_VERSION = typeof packageVersion === 'string' ? packageVersion : 'unknown'

export interface Config {
  maxFileSize: number
  outputSize: number
  cellSize: number
  isometricCellSize: number
  background: string
  transparentBackground: boolean
  sendAsForward: boolean
  replyAndMention: boolean
  groupSendOptions: GroupSendOption[]
  maxBlocks: number
  renderTimeout: number
  cacheDirectory: string
  cacheMaxSizeGb: number
  gpuRendererCommand: string
  renderEngine: 'standalone' | 'java' | 'webgl' | 'cpu'
  standaloneJavaCommand: string
  standaloneRendererJar: string
  standaloneRenderTimeout: number
  minecraftJarPath: string
  resourcePackPaths: string[]
  javaBridgeDirectory: string
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
}

export type SendMode = 'forward' | 'combined'
export type ReplyAndMentionOverride = 'inherit' | 'enabled' | 'disabled'

export interface GroupSendOption {
  groupId: string
  sendMode: SendMode
  replyAndMention: ReplyAndMentionOverride
}

export const Config: Schema<Config> = Schema.object({
  maxFileSize: Schema.natural().default(1024 * 1024).description('自动处理的最大文件大小（字节）。'),
  outputSize: Schema.natural().min(128).max(4096).default(1024).description('正交视图最长边的最大像素数。'),
  cellSize: Schema.natural().min(1).max(32).default(8).description('正交投影每个方块的基础像素大小。'),
  isometricCellSize: Schema.natural().min(2).max(32).default(7).description('正二轴测方块菱形半宽。'),
  background: Schema.string().default('#000000').description('PNG 背景颜色。'),
  transparentBackground: Schema.boolean().default(false).description('输出透明背景。'),
  sendAsForward: Schema.boolean().default(false).description('默认发送方式；开启为合并转发，关闭为两张图片和信息组成一条普通消息。'),
  replyAndMention: Schema.boolean().default(false).description('默认是否引用原消息并 @ 投影发送者。'),
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
  })).default([]).description('按群覆盖发送方式和回复 @ 设置；相同群号以最后一项为准。'),
  maxBlocks: Schema.natural().min(1).default(250000).description('解析的非空气方块上限。'),
  renderTimeout: Schema.natural().min(1000).default(30000).description('下载与渲染超时（毫秒）。'),
  cacheDirectory: Schema.string().default('data/litematic-renderer-cache').description('持久缓存目录；按插件版本和投影 SHA-256 分区。'),
  cacheMaxSizeGb: Schema.number().min(1).max(1024).step(1).default(20).description('所有版本缓存的总上限（GiB），超出后按最久未使用清理。'),
  gpuRendererCommand: Schema.string().default('').description('可选的可信本地 GPU 渲染器命令。'),
  renderEngine: Schema.union([Schema.const('standalone'), Schema.const('java'), Schema.const('webgl'), Schema.const('cpu')]).default('standalone').description('standalone 为无需客户端的独立 Java 资源包渲染器；java 为 Fabric 客户端桥接。'),
  standaloneJavaCommand: Schema.string().default('C:/Program Files/Java/jdk-21.0.11/bin/java.exe').description('独立渲染器使用的 Java 21 可执行文件。'),
  standaloneRendererJar: Schema.string().default('').description('独立渲染器 JAR；留空使用插件内置版本。'),
  standaloneRenderTimeout: Schema.natural().min(10000).default(180000).description('独立 Java 渲染超时（毫秒）。'),
  minecraftJarPath: Schema.string().default('D:/我的世界/.minecraft/versions/26.2/26.2.jar').description('提供原版 blockstate、模型和纹理的 Minecraft 客户端 JAR。'),
  resourcePackPaths: Schema.array(Schema.string()).default([
    'D:/我的世界/.minecraft/versions/26.2/resourcepacks/XK redstone display 26.1.5.zip',
    'D:/我的世界/.minecraft/versions/26.2/resourcepacks/XKRDA红显附加包0.3for1.19.4~1.21snapshot.zip',
  ]).description('独立渲染材质包路径；越靠后优先级越高。'),
  javaBridgeDirectory: Schema.string().default('D:/我的世界/.minecraft/versions/1.21.1-Fabric/render-bridge').description('Fabric Java 渲染桥任务目录。'),
  javaRenderTimeout: Schema.natural().min(10000).default(180000).description('等待 Minecraft Java 渲染完成的超时（毫秒）。'),
  javaResolution: Schema.natural().min(256).max(4096).default(1024).description('Java 渲染最终输出边长。'),
  javaSupersampling: Schema.natural().min(1).max(4).default(2).description('Fabrishot 式离屏超采样倍数。'),
  webglQuality: Schema.union([Schema.const('standard'), Schema.const('high'), Schema.const('ultra')]).default('high').description('WebGL 渲染质量。'),
  webglWidth: Schema.natural().min(256).max(2048).default(800).description('WebGL 单张图宽度。'),
  webglHeight: Schema.natural().min(256).max(2048).default(600).description('WebGL 单张图高度。'),
  isometricSquare: Schema.boolean().default(true).description('将正二轴测图输出为正方形。'),
  isometricFill: Schema.percent().default(0.78).description('正二轴测主体在画布中的最大占比。'),
  isometricRotation: Schema.number().min(0).max(360).step(1).default(135).description('Isometric Renders 绕竖直轴的基准旋转角。'),
  isometricSlant: Schema.number().min(-90).max(90).step(1).default(36).description('Isometric Renders 俯仰角；36° 为正二轴测预设。'),
})

interface FileElement {
  name?: string
  url?: string
  src?: string
  size?: string | number
  id?: string
  file_id?: string
  busid?: string | number
}
interface Block { x: number, y: number, z: number, name: string }
interface Bounds { minX: number, minY: number, minZ: number, maxX: number, maxY: number, maxZ: number }
interface ImageResult { title: string, path: string }
interface RenderedImage { title: string, png: Buffer }
interface RenderResult { images: ImageResult[], metadata: string }

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

export function apply(ctx: Context, config: Config) {
  const logger = ctx.logger(name)
  const cacheDirectory = resolve(config.cacheDirectory)
  const versionCacheDirectory = join(cacheDirectory, `v${cachePathSegment(PLUGIN_VERSION)}`)
  const cacheMaxBytes = Math.floor(config.cacheMaxSizeGb * 1024 ** 3)
  const inFlight = new Map<string, Promise<void>>()
  void fs.mkdir(versionCacheDirectory, { recursive: true })
    .then(() => enforceCacheLimit(cacheDirectory, cacheMaxBytes))
    .catch(error => logger.warn(`缓存初始化失败：${error instanceof Error ? error.message : String(error)}`))

  const render = async (url: string, filename = 'schematic.litematic'): Promise<RenderResult> => {
    const bytes = await download(ctx, url, config.maxFileSize, config.renderTimeout)
    const metadata = formatLitematicMetadata(parseLitematicMetadata(bytes))
    const fileHash = createHash('sha256').update(bytes).digest('hex')
    const resourceFingerprint = config.renderEngine === 'standalone'
      ? await fingerprintFiles([config.minecraftJarPath, ...config.resourcePackPaths])
      : []
    const renderHash = hashRenderConfiguration({
        version: CACHE_FORMAT_VERSION,
        outputSize: config.outputSize,
        cellSize: config.cellSize,
        isometricCellSize: config.isometricCellSize,
        background: config.background,
        transparentBackground: config.transparentBackground,
        maxBlocks: config.maxBlocks,
        gpuRendererCommand: config.gpuRendererCommand,
        renderEngine: config.renderEngine,
        standaloneRendererJar: config.standaloneRendererJar,
        resourceFingerprint,
        javaResolution: config.javaResolution,
        javaSupersampling: config.javaSupersampling,
        webglQuality: config.webglQuality,
        webglWidth: config.webglWidth,
        webglHeight: config.webglHeight,
        isometricSquare: config.isometricSquare,
        isometricFill: config.isometricFill,
        isometricRotation: config.isometricRotation,
        isometricSlant: config.isometricSlant,
      })
    const output = join(versionCacheDirectory, `${fileHash}-${renderHash}`)
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
    if (!(await Promise.all(expected.map(exists))).every(Boolean)) {
      let task = inFlight.get(output)
      if (!task) {
        task = (async () => {
          if ((await Promise.all(expected.map(exists))).every(Boolean)) return
          const gpuSucceeded = Boolean(config.gpuRendererCommand
            && await renderWithGpu(config.gpuRendererCommand, input, output, config.renderTimeout, logger))
          if (!gpuSucceeded || !(await Promise.all(expected.map(exists))).every(Boolean)) {
            if (config.renderEngine === 'standalone') {
              await renderWithStandalone(input, output, config)
            } else if (config.renderEngine === 'java') {
              await renderWithJavaBridge(input, output, config)
            } else {
              const schematic = parseLitematic(bytes, config.maxBlocks)
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
          if (!(await Promise.all(expected.map(exists))).every(Boolean)) throw new Error('渲染器没有生成两张正二轴测 PNG')
        })().finally(() => inFlight.delete(output))
        inFlight.set(output, task)
      } else {
        logger.debug(`等待相同投影的缓存任务：${fileHash}`)
      }
      await task
    } else {
      logger.debug(`命中投影缓存：${fileHash}`)
    }
    await touchCacheEntry(output)
    await enforceCacheLimit(cacheDirectory, cacheMaxBytes, output)
    return {
      images: expected.map((path, index) => ({ title: ['正二轴测', '反向正二轴测（旋转 180°）'][index], path })),
      metadata,
    }
  }

  ctx.middleware(async (session, next) => {
    if (!session.guildId) return next()
    const file = findLitematicFile(session)
    const url = file && await resolveFileUrl(session, file)
    if (!url || !file || !isUnderLimit(file.size, config.maxFileSize)) return next()
    try {
      const result = await render(url, file.name)
      await sendImages(session, result.images, result.metadata, resolveSendOptions(config, session.guildId))
    } catch (error) {
      logger.warn(error)
      await session.send(`投影渲染失败：${error instanceof Error ? error.message : String(error)}`)
    }
    return next()
  })

  ctx.command('litematic.render <url:string>', '渲染可直接下载的 .litematic 文件')
    .action(async ({ session }, url) => {
      if (!url) return '请提供 .litematic 文件的直链。'
      if (!session) return '此命令只能在消息会话中执行。'
      try {
        const result = await render(url)
        await sendImages(session, result.images, result.metadata, resolveSendOptions(config, session.guildId))
      } catch (error) {
        return `渲染失败：${error instanceof Error ? error.message : String(error)}`
      }
    })
  ctx.command('litematic.cache.clear', '清理投影渲染缓存', { authority: 3 })
    .action(async () => {
      await fs.rm(cacheDirectory, { recursive: true, force: true })
      await fs.mkdir(versionCacheDirectory, { recursive: true })
      return '投影渲染缓存已清理。'
    })
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

function cachePathSegment(value: string) {
  return value.replace(/[^a-zA-Z0-9._-]/g, '_') || 'unknown'
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

function findLitematicFile(session: Session): FileElement | undefined {
  const elements = session.elements ?? h.parse(session.content ?? '')
  return elements.find((element: any) => {
    if (element.type !== 'file') return false
    const attrs = element.attrs as FileElement
    return extname(attrs.name ?? '').toLowerCase() === '.litematic'
  })?.attrs as FileElement | undefined
}

async function resolveFileUrl(session: Session, file: FileElement) {
  if (file.url || file.src) return file.url ?? file.src
  const fileId = file.id ?? file.file_id
  const internal = (session.bot as any).internal
  if (!session.guildId || !fileId || typeof internal?.getGroupFileUrl !== 'function') return
  const result = await internal.getGroupFileUrl(session.guildId, fileId, Number(file.busid ?? 0))
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

async function fingerprintFiles(paths: string[]) {
  return Promise.all(paths.map(async path => {
    const absolute = resolve(path)
    try {
      const stat = await fs.stat(absolute)
      return { path: absolute, size: stat.size, modified: stat.mtimeMs }
    } catch {
      return { path: absolute, missing: true }
    }
  }))
}

async function renderWithStandalone(input: string, output: string, config: Config) {
  const rendererJar = resolve(config.standaloneRendererJar || join(__dirname, '../assets/standalone-renderer/litematic-standalone-renderer-0.1.7.jar'))
  if (!(await exists(rendererJar))) throw new Error(`独立 Java 渲染器不存在：${rendererJar}`)
  if (!(await exists(resolve(config.minecraftJarPath)))) throw new Error(`Minecraft 资源 JAR 不存在：${config.minecraftJarPath}`)
  for (const pack of config.resourcePackPaths) {
    if (!(await exists(resolve(pack)))) throw new Error(`材质包不存在：${pack}`)
  }

  const args = [
    '-Djava.awt.headless=true', '-jar', rendererJar,
    '--input', resolve(input), '--output', resolve(output),
    '--minecraft-jar', resolve(config.minecraftJarPath),
    '--resolution', String(config.javaResolution),
    '--supersampling', String(config.javaSupersampling),
    '--max-blocks', String(config.maxBlocks),
    '--rotation', String(config.isometricRotation),
    '--slant', String(config.isometricSlant),
    '--fill', String(config.isometricFill),
    '--background', config.background,
  ]
  if (config.transparentBackground) args.push('--transparent-background')
  for (const pack of config.resourcePackPaths) args.push('--resource-pack', resolve(pack))

  await new Promise<void>((resolveRun, reject) => {
    const child = spawn(config.standaloneJavaCommand, args, { shell: false, windowsHide: true })
    let stderr = ''
    child.stderr?.on('data', chunk => { stderr = (stderr + String(chunk)).slice(-6000) })
    const timer = setTimeout(() => {
      child.kill()
      reject(new Error(`独立 Java 渲染超过 ${Math.round(config.standaloneRenderTimeout / 1000)} 秒`))
    }, config.standaloneRenderTimeout)
    child.once('error', error => {
      clearTimeout(timer)
      reject(error)
    })
    child.once('close', code => {
      clearTimeout(timer)
      if (code === 0) resolveRun()
      else reject(new Error(`独立 Java 渲染器退出码 ${code}${stderr ? `：${stderr.trim()}` : ''}`))
    })
  })
}

interface JavaBridgeStatus {
  timestamp?: number
  inWorld?: boolean
  resourcePacks?: string[]
}

interface JavaBridgeResult {
  success?: boolean
  error?: string
}

async function renderWithJavaBridge(input: string, output: string, config: Config) {
  const bridge = resolve(config.javaBridgeDirectory)
  const status = await readJson<JavaBridgeStatus>(join(bridge, 'status.json'))
  if (!status?.timestamp || Date.now() - status.timestamp > 5000) {
    throw new Error('Minecraft Java 渲染桥未运行；请启动 1.21.1-Fabric 客户端并进入一个世界')
  }
  if (!status.inWorld) throw new Error('Minecraft Java 渲染桥已启动，但客户端尚未进入世界')

  const packs = status.resourcePacks ?? []
  const required = [
    ['XeKr', '3.6forMC1.20.2~1.21.5.zip'],
    ['XKRDA', '1.19.4~1.21snapshot.zip'],
  ]
  if (!required.every(parts => packs.some(id => parts.every(part => id.includes(part))))) {
    throw new Error('Minecraft 客户端没有同时启用 XeKr 红显基础包和 XKRDA 附加包')
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
    resolution: config.javaResolution,
    supersampling: config.javaSupersampling,
    rotation: config.isometricRotation,
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

async function readJson<T>(path: string): Promise<T | undefined> {
  try { return JSON.parse(await fs.readFile(path, 'utf8')) as T } catch (error: any) {
    if (error?.code === 'ENOENT' || error instanceof SyntaxError) return
    throw error
  }
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
    }, { base64: data.toString('base64'), width: config.webglWidth, height: config.webglHeight, quality: config.webglQuality, isometricSquare: config.isometricSquare, isometricFill: config.isometricFill, isometricRotation: config.isometricRotation, isometricSlant: config.isometricSlant })
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
  sendMode: SendMode
  replyAndMention: boolean
}

type SendConfig = Pick<Config, 'sendAsForward' | 'replyAndMention' | 'groupSendOptions'>

export function resolveSendOptions(config: SendConfig, groupId?: string): ResolvedSendOptions {
  const override = groupId
    ? [...config.groupSendOptions].reverse().find(item => item.groupId.trim() === groupId)
    : undefined
  const replyAndMention = override?.replyAndMention === 'enabled'
    ? true
    : override?.replyAndMention === 'disabled'
      ? false
      : config.replyAndMention
  return {
    sendMode: override?.sendMode ?? (config.sendAsForward ? 'forward' : 'combined'),
    replyAndMention,
  }
}

export async function sendImages(session: Session, images: ImageResult[], metadata: string, options: ResolvedSendOptions) {
  const messages = images.map(({ title, path }) => h('message', { userId: session.selfId, nickname: '投影渲染' }, [h('text', { content: title }), h.image(path)]))
  const metadataMessage = h('message', { userId: session.selfId, nickname: '投影信息' }, [h('text', { content: metadata })])
  const reply = options.replyAndMention ? replyElements(session) : []
  if (options.sendMode === 'forward') {
    if (reply.length) await session.send([...reply, h('text', { content: '投影渲染结果如下。' })])
    await session.send(h('figure', {}, [...messages, metadataMessage]))
    return
  }
  const combined = images.flatMap(({ title, path }, index) => [
    h('text', { content: `${index ? '\n' : ''}${title}\n` }),
    h.image(path),
  ])
  await session.send([...reply, ...combined, h('text', { content: `\n${metadata}` })])
}

function replyElements(session: Session) {
  const result = []
  if (session.messageId) result.push(h('quote', { id: session.messageId }))
  if (session.userId) result.push(h('at', { id: session.userId }))
  if (result.length) result.push(h('text', { content: '\n' }))
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

export function formatLitematicMetadata(metadata: LitematicMetadata) {
  const blocks = metadata.totalBlocks == null ? '未知' : String(metadata.totalBlocks)
  const volume = metadata.totalVolume == null ? '未知' : String(metadata.totalVolume)
  const size = metadata.size?.join(' × ') ?? '未知'
  const litematicVersion = metadata.litematicVersion == null ? '未知' : String(metadata.litematicVersion)
  const gameVersion = metadata.minecraftVersion ?? '未知'
  const dataVersion = metadata.minecraftDataVersion == null ? '' : `（数据版本：${metadata.minecraftDataVersion}）`
  return [
    `保存者游戏 ID：${metadata.author}`,
    `创建时间：${metadata.createdAt}`,
    `方块数/体积：${blocks}/${volume}`,
    `尺寸：${size}`,
    `Litematic 版本：${litematicVersion}`,
    `游戏版本：${gameVersion}${dataVersion}`,
  ].join('\n')
}

export function parseLitematic(data: Buffer, maxBlocks: number) {
  const root = readLitematicRoot(data)
  const regions = root.Regions as Record<string, Record<string, unknown>> | undefined
  if (!regions || typeof regions !== 'object') throw new Error('文件不含 Litematica Regions 数据')
  const blocks: Block[] = []
  for (const region of Object.values(regions)) parseRegion(region, blocks, maxBlocks)
  if (!blocks.length) throw new Error('投影中没有非空气方块')
  return blocks
}

function parseRegion(region: Record<string, unknown>, target: Block[], maxBlocks: number) {
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
    if (target.length >= maxBlocks) throw new Error(`非空气方块超过 ${maxBlocks} 上限`)
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

type View = 'top' | 'front' | 'side'
function renderOrthographic(blocks: Block[], bounds: Bounds, view: View, config: Config) {
  const spanA = view === 'side' ? bounds.maxZ - bounds.minZ + 1 : bounds.maxX - bounds.minX + 1
  const spanB = view === 'top' ? bounds.maxZ - bounds.minZ + 1 : bounds.maxY - bounds.minY + 1
  const scale = Math.max(1, Math.min(config.cellSize, Math.floor(config.outputSize / Math.max(spanA, spanB))))
  const canvas = new Raster(spanA * scale, spanB * scale, config)
  const visible = new Map<string, Block>()
  for (const block of blocks) {
    const a = view === 'side' ? block.z - bounds.minZ : block.x - bounds.minX
    const b = view === 'top' ? bounds.maxZ - block.z : bounds.maxY - block.y
    const depth = view === 'top' ? block.y : view === 'front' ? -block.z : block.x
    const key = `${a},${b}`, old = visible.get(key)
    const oldDepth = old && (view === 'top' ? old.y : view === 'front' ? -old.z : old.x)
    if (!old || depth > oldDepth!) visible.set(key, block)
  }
  for (const [key, block] of visible) {
    const [a, b] = key.split(',').map(Number)
    canvas.rect(a * scale, b * scale, scale, scale, colorFor(block.name))
  }
  return canvas.png()
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
