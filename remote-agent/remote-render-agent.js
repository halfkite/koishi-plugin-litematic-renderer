#!/usr/bin/env node
'use strict'

// Remote GPU Agent for koishi-plugin-litematic-renderer 0.6.x.
// Keep this listener private: bind it to 127.0.0.1 and expose it only through
// an authenticated FRP STCP tunnel or another private transport.

const { createHmac, randomUUID, timingSafeEqual } = require('node:crypto')
const http = require('node:http')
const { promises: fs } = require('node:fs')
const { join, resolve } = require('node:path')

const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])
let renderQueue = Promise.resolve()
const usedNonces = new Map()

function readArgument(name) {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] : undefined
}

async function readConfig() {
  const file = readArgument('--config')
  if (!file) throw new Error('Usage: node remote-render-agent.js --config <agent.config.json>')
  const config = JSON.parse(await fs.readFile(resolve(file), 'utf8'))
  if (!config || typeof config !== 'object') throw new Error('Agent config must be a JSON object')
  if (typeof config.sharedSecret !== 'string' || config.sharedSecret.trim().length < 32) {
    throw new Error('sharedSecret must contain at least 32 characters')
  }
  if (typeof config.gameDirectory !== 'string' || !config.gameDirectory.trim()) {
    throw new Error('gameDirectory is required')
  }
  return {
    listenHost: typeof config.listenHost === 'string' && config.listenHost ? config.listenHost : '127.0.0.1',
    listenPort: integer(config.listenPort, 39080, 1, 65535),
    sharedSecret: config.sharedSecret.trim(),
    gameDirectory: resolve(config.gameDirectory),
    outputDirectory: resolve(config.outputDirectory || join(config.gameDirectory, 'quickcraft-render-agent')),
    renderTimeout: integer(config.renderTimeout, 240000, 10000, 900000),
    maxRequestBytes: integer(config.maxRequestBytes, 24 * 1024 * 1024, 1024 * 1024, 128 * 1024 * 1024),
    maxClockSkewSeconds: integer(config.maxClockSkewSeconds, 90, 10, 600),
  }
}

function integer(value, fallback, minimum, maximum) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(minimum, Math.min(maximum, Math.floor(parsed))) : fallback
}

function signature(secret, timestamp, nonce, body) {
  return createHmac('sha256', secret).update(timestamp).update('.').update(nonce).update('.').update(body).digest('hex')
}

function secureEqual(left, right) {
  if (typeof left !== 'string' || typeof right !== 'string') return false
  const a = Buffer.from(left, 'hex')
  const b = Buffer.from(right, 'hex')
  return a.length === b.length && a.length > 0 && timingSafeEqual(a, b)
}

function reject(response, status, message) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
  response.end(JSON.stringify({ version: 1, error: message }))
}

async function requestBody(request, maximum) {
  const chunks = []
  let length = 0
  for await (const chunk of request) {
    length += chunk.length
    if (length > maximum) throw new Error('request body exceeds configured limit')
    chunks.push(chunk)
  }
  return Buffer.concat(chunks)
}

function consumeNonce(nonce, lifetimeMs) {
  const now = Date.now()
  for (const [key, expiry] of usedNonces) if (expiry <= now) usedNonces.delete(key)
  if (usedNonces.has(nonce)) return false
  usedNonces.set(nonce, now + lifetimeMs)
  return true
}

async function readJson(file) {
  try { return JSON.parse(await fs.readFile(file, 'utf8')) } catch (error) {
    if (error && error.code === 'ENOENT') return undefined
    throw error
  }
}

async function waitForRender(bridge, job, timeout) {
  const status = await readJson(join(bridge, 'status.json'))
  if (!status || !status.timestamp || Date.now() - status.timestamp > 5000) {
    throw new Error('Minecraft GPU client is not running or its render bridge is stale')
  }
  const jobs = join(bridge, 'jobs')
  const result = join(bridge, 'results', `${job.id}.result.json`)
  const temporary = join(jobs, `${job.id}.job.json.tmp`)
  const final = join(jobs, `${job.id}.job.json`)
  await fs.mkdir(jobs, { recursive: true })
  await fs.rm(result, { force: true })
  await fs.writeFile(temporary, JSON.stringify(job))
  await fs.rename(temporary, final)
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const response = await readJson(result)
    if (response) {
      await fs.rm(result, { force: true })
      if (!response.success) throw new Error(`Minecraft GPU render failed: ${response.error || 'unknown error'}`)
      return
    }
    await new Promise(resolveDelay => setTimeout(resolveDelay, 200))
  }
  throw new Error(`Minecraft GPU render timed out after ${Math.ceil(timeout / 1000)} seconds`)
}

async function render(config, payload) {
  if (!payload || payload.version !== 1 || typeof payload.id !== 'string' || typeof payload.filename !== 'string' || typeof payload.litematicBase64 !== 'string') {
    throw new Error('invalid render payload')
  }
  const input = Buffer.from(payload.litematicBase64, 'base64')
  if (!input.length || input.length > config.maxRequestBytes) throw new Error('invalid or oversized litematic payload')
  const options = payload.options && typeof payload.options === 'object' ? payload.options : {}
  const id = randomUUID()
  const bridge = join(config.gameDirectory, 'quickcraft-render-bridge')
  const directory = join(config.outputDirectory, id)
  const inputPath = join(directory, 'projection.litematic')
  const timeout = integer(options.timeout, config.renderTimeout, 10000, config.renderTimeout)
  try {
    await fs.mkdir(directory, { recursive: true })
    await fs.writeFile(inputPath, input)
    await waitForRender(bridge, {
      id,
      input: inputPath,
      outputDirectory: directory,
      resolution: integer(options.outputSize, 1024, 256, 4096),
      supersampling: integer(options.supersampling, 1, 1, 4),
      rotation: Number.isFinite(Number(options.rotation)) ? Number(options.rotation) : 135,
      pitch: Number.isFinite(Number(options.slant)) ? Number(options.slant) : 36,
      slant: Number.isFinite(Number(options.slant)) ? Number(options.slant) : 36,
      fill: Number.isFinite(Number(options.fill)) ? Number(options.fill) : 0.78,
      background: typeof options.background === 'string' ? options.background : '#000000',
      transparentBackground: Boolean(options.transparentBackground),
    }, timeout)
    const images = []
    for (const title of ['isometric.png', 'isometric-reverse.png']) {
      const png = await fs.readFile(join(directory, title))
      if (png.length < PNG_SIGNATURE.length || !png.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE)) {
        throw new Error(`GPU client returned invalid ${title}`)
      }
      images.push({ title, base64: png.toString('base64') })
    }
    return { version: 1, id: payload.id, images }
  } finally {
    await fs.rm(directory, { recursive: true, force: true }).catch(() => undefined)
  }
}

async function main() {
  const config = await readConfig()
  const server = http.createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/v1/render') return reject(response, 404, 'not found')
    try {
      const body = await requestBody(request, config.maxRequestBytes)
      const timestamp = request.headers['x-litematic-agent-timestamp']
      const nonce = request.headers['x-litematic-agent-nonce']
      const supplied = request.headers['x-litematic-agent-signature']
      if (typeof timestamp !== 'string' || typeof nonce !== 'string' || typeof supplied !== 'string') return reject(response, 401, 'missing request signature')
      const time = Number(timestamp)
      if (!Number.isFinite(time) || Math.abs(Date.now() - time) > config.maxClockSkewSeconds * 1000) return reject(response, 401, 'request timestamp is outside allowed clock skew')
      if (!secureEqual(supplied, signature(config.sharedSecret, timestamp, nonce, body))) return reject(response, 401, 'invalid request signature')
      if (!consumeNonce(nonce, config.maxClockSkewSeconds * 2000)) return reject(response, 409, 'replayed request nonce')
      const payload = JSON.parse(body.toString('utf8'))
      const previous = renderQueue
      let release
      renderQueue = new Promise(resolveRelease => { release = resolveRelease })
      await previous.catch(() => undefined)
      let result
      try { result = await render(config, payload) } finally { release() }
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
      response.end(JSON.stringify(result))
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      console.error(new Date().toISOString(), message)
      reject(response, 400, message)
    }
  })
  server.requestTimeout = config.renderTimeout + 30000
  server.headersTimeout = 30000
  server.listen(config.listenPort, config.listenHost, () => {
    console.log(`Remote GPU Agent listening on http://${config.listenHost}:${config.listenPort}`)
  })
}

main().catch(error => {
  console.error(error instanceof Error ? error.stack || error.message : error)
  process.exitCode = 1
})
