import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto'
import { EventEmitter } from 'node:events'
import { WebSocket, WebSocketServer } from 'ws'

export const GPU_PROTOCOL_VERSION = 2
export const GPU_BINARY_HEADER_LIMIT = 64 * 1024
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])

export interface RenderView {
  id: string
  name?: string
  yaw: number
  pitch: number
  zoom?: number
  autoFill?: boolean
  width: number
  height: number
  background: string
  transparentBackground: boolean
  supersampling: number
}

export interface GpuRenderRequest {
  version: 2
  id: string
  filename: string
  views: RenderView[]
  resourcePackProfile?: string
  sourceGroup?: string
  sourceUser?: string
}

export interface GpuAgentNodeConfig {
  agentId: string
  sharedSecret: string
  enabled?: boolean
}

export interface GpuAgentCapabilities {
  rendererVersion?: string
  minecraftVersion?: string
  gpu?: string
  maxTextureSize?: number
  resourcePackFingerprint?: string
}

export interface GpuRenderImage {
  id: string
  name: string
  width: number
  height: number
  png: Buffer
}

export interface GpuRenderResult {
  agentId: string
  images: GpuRenderImage[]
  elapsedMillis?: number
  cacheHit?: boolean
  gpu?: string
}

interface BinaryHeader {
  type: 'input' | 'image'
  taskId: string
  attachmentId: string
  name?: string
  width?: number
  height?: number
}

interface PendingTask {
  resolve: (result: GpuRenderResult) => void
  reject: (error: Error) => void
  images: GpuRenderImage[]
  timer: NodeJS.Timeout
}

interface AgentState {
  agentId: string
  socket: WebSocket
  authenticated: boolean
  challenge: string
  queueLength: number
  busy: boolean
  lastAssigned: number
  lastSeen: number
  capabilities: GpuAgentCapabilities
  pending: Map<string, PendingTask>
}

export interface GpuAgentHubOptions {
  host: string
  port: number
  path: string
  nodes: GpuAgentNodeConfig[]
  heartbeatTimeout: number
  logger: { info(message: string): void, warn(message: string): void, debug(message: string): void }
}

export function gpuAgentAuthSignature(secret: string, challenge: string, agentId: string) {
  return createHmac('sha256', secret).update(challenge).update('.').update(agentId).digest('hex')
}

export function encodeGpuBinary(header: BinaryHeader, payload: Buffer) {
  const encoded = Buffer.from(JSON.stringify(header), 'utf8')
  if (encoded.length > GPU_BINARY_HEADER_LIMIT) throw new Error('GPU binary header is too large')
  const output = Buffer.allocUnsafe(4 + encoded.length + payload.length)
  output.writeUInt32BE(encoded.length, 0)
  encoded.copy(output, 4)
  payload.copy(output, 4 + encoded.length)
  return output
}

export function decodeGpuBinary(value: Buffer): { header: BinaryHeader, payload: Buffer } {
  if (value.length < 5) throw new Error('GPU binary frame is truncated')
  const headerLength = value.readUInt32BE(0)
  if (!headerLength || headerLength > GPU_BINARY_HEADER_LIMIT || 4 + headerLength > value.length) {
    throw new Error('GPU binary frame has an invalid header')
  }
  const header = JSON.parse(value.toString('utf8', 4, 4 + headerLength)) as BinaryHeader
  if (!header || !['input', 'image'].includes(header.type) || !header.taskId || !header.attachmentId) {
    throw new Error('GPU binary frame has an invalid envelope')
  }
  return { header, payload: value.subarray(4 + headerLength) }
}

export function selectGpuAgent<T extends {
  authenticated: boolean
  busy: boolean
  queueLength: number
  lastAssigned: number
  lastSeen: number
  capabilities: GpuAgentCapabilities
}>(agents: T[], views: RenderView[], now = Date.now(), heartbeatTimeout = 30_000): T | undefined {
  const requiredTexture = Math.max(...views.map(view => Math.max(view.width, view.height) * Math.max(1, view.supersampling)), 1)
  return agents
    .filter(agent => agent.authenticated && !agent.busy && now - agent.lastSeen <= heartbeatTimeout)
    .filter(agent => !agent.capabilities.maxTextureSize || agent.capabilities.maxTextureSize >= requiredTexture)
    .sort((left, right) => left.queueLength - right.queueLength || left.lastAssigned - right.lastAssigned)[0]
}

function secureHexEqual(left: unknown, right: string) {
  if (typeof left !== 'string' || !/^[0-9a-f]+$/i.test(left)) return false
  const first = Buffer.from(left, 'hex')
  const second = Buffer.from(right, 'hex')
  return first.length === second.length && first.length > 0 && timingSafeEqual(first, second)
}

export class GpuAgentHub extends EventEmitter {
  private readonly states = new Map<WebSocket, AgentState>()
  private readonly nodeConfigs: Map<string, GpuAgentNodeConfig>
  private server?: WebSocketServer

  constructor(private readonly options: GpuAgentHubOptions) {
    super()
    this.nodeConfigs = new Map(options.nodes.filter(node => node.enabled !== false).map(node => [node.agentId, node]))
  }

  start() {
    if (this.server) return
    this.server = new WebSocketServer({ host: this.options.host, port: this.options.port, path: this.options.path })
    this.server.on('connection', socket => this.accept(socket))
    this.server.on('listening', () => this.options.logger.info(
      `GPU Agent WebSocket listening on ws://${this.options.host}:${this.options.port}${this.options.path}`))
    this.server.on('error', error => this.options.logger.warn(`GPU Agent WebSocket error: ${error.message}`))
  }

  async close() {
    for (const state of this.states.values()) this.disconnect(state, new Error('GPU Agent hub stopped'))
    const server = this.server
    this.server = undefined
    if (server) await new Promise<void>(resolve => server.close(() => resolve()))
  }

  connectedAgentIds() {
    return [...this.states.values()].filter(state => state.authenticated).map(state => state.agentId)
  }

  capabilityFingerprint() {
    return [...this.states.values()]
      .filter(state => state.authenticated)
      .map(state => [state.agentId, state.capabilities.rendererVersion ?? '',
        state.capabilities.minecraftVersion ?? '', state.capabilities.resourcePackFingerprint ?? ''].join(':'))
      .sort().join('|') || 'offline'
  }

  async render(request: GpuRenderRequest, schematic: Buffer, timeout: number): Promise<GpuRenderResult> {
    const state = selectGpuAgent([...this.states.values()], request.views, Date.now(), this.options.heartbeatTimeout)
    if (!state) throw new Error('没有可用且能力匹配的 GPU Agent')
    const taskId = request.id || randomUUID()
    state.busy = true
    state.queueLength++
    state.lastAssigned = Date.now()
    try {
      const promise = new Promise<GpuRenderResult>((resolve, reject) => {
        const timer = setTimeout(() => {
          state.pending.delete(taskId)
          reject(new Error(`GPU Agent ${state.agentId} 渲染超时`))
        }, timeout)
        state.pending.set(taskId, { resolve, reject, images: [], timer })
      })
      state.socket.send(JSON.stringify({ type: 'render', version: 2, task: { ...request, id: taskId } }))
      state.socket.send(encodeGpuBinary({ type: 'input', taskId, attachmentId: 'litematic', name: request.filename }, schematic))
      return await promise
    } finally {
      state.busy = false
      state.queueLength = Math.max(0, state.queueLength - 1)
    }
  }

  private accept(socket: WebSocket) {
    const state: AgentState = {
      agentId: '', socket, authenticated: false, challenge: randomUUID(), queueLength: 0,
      busy: false, lastAssigned: 0, lastSeen: Date.now(), capabilities: {}, pending: new Map(),
    }
    this.states.set(socket, state)
    socket.on('message', (data, isBinary) => {
      try { this.onMessage(state, Buffer.from(data as any), isBinary) } catch (error) {
        this.options.logger.warn(`GPU Agent protocol error: ${error instanceof Error ? error.message : String(error)}`)
        socket.close(1008, 'protocol error')
      }
    })
    socket.on('close', () => this.disconnect(state, new Error(`GPU Agent ${state.agentId || 'unknown'} disconnected`)))
    socket.on('error', error => this.options.logger.debug(`GPU Agent socket error: ${error.message}`))
  }

  private onMessage(state: AgentState, data: Buffer, isBinary: boolean) {
    state.lastSeen = Date.now()
    if (isBinary) return this.onBinary(state, data)
    const message = JSON.parse(data.toString('utf8')) as any
    if (message.type === 'hello' && message.version === 2 && typeof message.agentId === 'string') {
      if (!this.nodeConfigs.has(message.agentId)) throw new Error(`unconfigured agentId: ${message.agentId}`)
      state.agentId = message.agentId
      state.challenge = randomUUID()
      state.socket.send(JSON.stringify({ type: 'challenge', version: 2, challenge: state.challenge }))
      return
    }
    if (message.type === 'auth' && state.agentId) {
      const node = this.nodeConfigs.get(state.agentId)!
      const expected = gpuAgentAuthSignature(node.sharedSecret, state.challenge, state.agentId)
      if (!secureHexEqual(message.signature, expected)) throw new Error('invalid agent signature')
      state.authenticated = true
      state.capabilities = message.capabilities ?? {}
      state.socket.send(JSON.stringify({ type: 'authenticated', version: 2 }))
      this.options.logger.info(`GPU Agent connected: ${state.agentId}${state.capabilities.gpu ? ` (${state.capabilities.gpu})` : ''}`)
      return
    }
    if (!state.authenticated) throw new Error('agent is not authenticated')
    if (message.type === 'heartbeat') {
      state.busy = Boolean(message.busy)
      state.queueLength = Math.max(0, Number(message.queueLength) || 0)
      if (message.capabilities) state.capabilities = { ...state.capabilities, ...message.capabilities }
      return
    }
    if (message.type === 'progress') {
      this.emit('progress', { agentId: state.agentId, taskId: message.taskId, progress: message.progress, stage: message.stage })
      return
    }
    if (message.type === 'result' || message.type === 'error') {
      const pending = state.pending.get(message.taskId)
      if (!pending) { this.options.logger.warn(`GPU Agent ${state.agentId} 回传了未知任务 ${message.taskId} 的结果（可能已超时）`); return }
      clearTimeout(pending.timer)
      state.pending.delete(message.taskId)
      if (message.type === 'error') pending.reject(new Error(`${message.code ?? 'GPU_RENDER_FAILED'}: ${message.message ?? 'GPU render failed'}`))
      else {
        this.options.logger.info(`GPU Agent ${state.agentId} 回传结果：任务 ${message.taskId}，${pending.images.length} 张图`)
        pending.resolve({ agentId: state.agentId, images: pending.images, elapsedMillis: message.elapsedMillis,
          cacheHit: message.cacheHit, gpu: state.capabilities.gpu })
      }
    }
  }

  private onBinary(state: AgentState, data: Buffer) {
    if (!state.authenticated) throw new Error('agent is not authenticated')
    const { header, payload } = decodeGpuBinary(data)
    if (header.type !== 'image') throw new Error('server only accepts image binary frames')
    if (payload.length < PNG_SIGNATURE.length || !payload.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE)) {
      throw new Error('agent returned an invalid PNG attachment')
    }
    const pending = state.pending.get(header.taskId)
    if (!pending) return
    pending.images.push({ id: header.attachmentId, name: header.name ?? `${header.attachmentId}.png`,
      width: Number(header.width) || 0, height: Number(header.height) || 0, png: Buffer.from(payload) })
  }

  private disconnect(state: AgentState, error: Error) {
    if (!this.states.delete(state.socket)) return
    for (const pending of state.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    state.pending.clear()
    // 必须真正关闭 socket：只删注册表会让连接变成僵尸，Agent 侧会一直认为仍在线而不再重连
    try { state.socket.close(1001, error.message.slice(0, 120)) } catch (ignored) {}
    if (state.authenticated) this.options.logger.warn(error.message)
  }
}
