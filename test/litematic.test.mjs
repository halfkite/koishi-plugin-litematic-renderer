import assert from 'node:assert/strict'
import { access, mkdir, mkdtemp, readFile, rm, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { gzipSync, inflateSync } from 'node:zlib'
import { Config as RendererConfig, SIX_FACE_LABELS, buildSshProxyArguments, cacheNameSegment, canRenderInSession, effectiveRenderResolution, enforceCacheLimit, findLitematicFile, formatLitematicMetadata, formatRenderError, hashRenderConfiguration, isGroupAllowed, isJavaMemoryFailure, parseLitematic, parseLitematicMetadata, patchOneBotAdapterSource, renderSixFaceOverview, replyElements, resolveOfficialProxyUrl, resolveSendOptions, saveUploadedResourcePack, sendImages, standaloneJvmOptions, supportsStandaloneJavaVersion } from '../lib/index.js'
import { decodeGpuBinary, encodeGpuBinary, gpuAgentAuthSignature, selectGpuAgent } from '../lib/gpu-agent-protocol.js'

const short = (value) => { const data = Buffer.alloc(2); data.writeUInt16BE(value); return data }
const int = (value) => { const data = Buffer.alloc(4); data.writeInt32BE(value); return data }
const double = (value) => { const data = Buffer.alloc(8); data.writeDoubleBE(value); return data }
const long = (value) => { const data = Buffer.alloc(8); data.writeBigInt64BE(BigInt(value)); return data }
const text = (value) => Buffer.concat([short(Buffer.byteLength(value)), Buffer.from(value)])
const named = (type, name, value) => Buffer.concat([Buffer.from([type]), text(name), value])
const compound = (...tags) => Buffer.concat([...tags, Buffer.from([0])])
const position = (x, y, z) => compound(named(3, 'x', int(x)), named(3, 'y', int(y)), named(3, 'z', int(z)))
const paletteEntry = (name) => compound(named(8, 'Name', text(name)))
const list = (type, entries) => Buffer.concat([Buffer.from([type]), int(entries.length), ...entries])

function sampleLitematic() {
  const metadata = compound(
    named(8, 'Author', text('CNJ233')),
    named(4, 'TimeCreated', long(1719131586000)),
    named(3, 'TotalBlocks', int(421)),
    named(3, 'TotalVolume', int(720)),
    named(10, 'EnclosingSize', position(4, 10, 18)),
  )
  const region = compound(
    named(10, 'Position', position(0, 0, 0)),
    named(10, 'Size', position(2, 1, 1)),
    named(9, 'BlockStatePalette', list(10, [paletteEntry('minecraft:air'), paletteEntry('minecraft:diamond_block')])),
    named(12, 'BlockStates', Buffer.concat([int(1), long(4)])),
  )
  const regions = compound(named(10, 'example', region))
  return gzipSync(Buffer.concat([
    Buffer.from([10]), text(''), named(6, 'PreviewTimestamp', double(1.5)),
    named(3, 'Version', int(6)), named(3, 'MinecraftDataVersion', int(3465)),
    named(10, 'Metadata', metadata), named(10, 'Regions', regions), Buffer.from([0]),
  ]))
}

function decodePng(buffer) {
  const width = buffer.readUInt32BE(16)
  const height = buffer.readUInt32BE(20)
  const idat = []
  for (let offset = 8; offset < buffer.length;) {
    const length = buffer.readUInt32BE(offset)
    const type = buffer.toString('ascii', offset + 4, offset + 8)
    if (type === 'IDAT') idat.push(buffer.subarray(offset + 8, offset + 8 + length))
    offset += length + 12
  }
  const rows = inflateSync(Buffer.concat(idat))
  const pixel = (x, y) => {
    assert.equal(rows[y * (width * 4 + 1)], 0)
    const offset = y * (width * 4 + 1) + 1 + x * 4
    return [...rows.subarray(offset, offset + 4)]
  }
  return { width, height, pixel }
}

test('round-trips GPU v2 binary attachments and challenge signatures', () => {
  const payload = Buffer.from('litematic-data')
  const encoded = encodeGpuBinary({ type: 'input', taskId: 'task-1', attachmentId: 'litematic', name: 'test.litematic' }, payload)
  const decoded = decodeGpuBinary(encoded)
  assert.equal(decoded.header.taskId, 'task-1')
  assert.deepEqual(decoded.payload, payload)
  assert.equal(gpuAgentAuthSignature('secret', 'challenge', 'node-a'), gpuAgentAuthSignature('secret', 'challenge', 'node-a'))
  assert.notEqual(gpuAgentAuthSignature('secret', 'challenge', 'node-a'), gpuAgentAuthSignature('secret', 'challenge', 'node-b'))
})

test('selects a ready compatible GPU node by queue then least-recent assignment', () => {
  const now = Date.now()
  const base = { authenticated: true, busy: false, queueLength: 0, lastSeen: now, capabilities: { maxTextureSize: 4096 } }
  const old = { ...base, id: 'old', lastAssigned: 10 }
  const recent = { ...base, id: 'recent', lastAssigned: 20 }
  const undersized = { ...base, id: 'small', lastAssigned: 0, capabilities: { maxTextureSize: 512 } }
  const view = { id: 'main', yaw: 0, pitch: 0, width: 1024, height: 768, background: '#000000', transparentBackground: false, supersampling: 2 }
  assert.equal(selectGpuAgent([recent, undersized, old], [view], now)?.id, 'old')
  assert.equal(selectGpuAgent([{ ...old, busy: true }], [view], now), undefined)
})

test('resolves official outbound proxy and builds a local-only SSH SOCKS tunnel', () => {
  const config = {
    officialProxyMode: 'ssh', officialProxyUrl: '', sshProxyExecutable: 'ssh',
    sshProxyHost: '47.116.38.184', sshProxyPort: 22, sshProxyUser: 'root',
    sshProxyPrivateKey: './aliyun.pem', sshProxyPassword: '', sshProxyLocalPort: 1080,
  }
  assert.equal(resolveOfficialProxyUrl(config), 'socks5h://127.0.0.1:1080')
  const args = buildSshProxyArguments(config)
  assert.deepEqual(args.slice(0, 4), ['-NT', '-D', '127.0.0.1:1080', '-p'])
  assert.ok(args.includes('ExitOnForwardFailure=yes'))
  assert.equal(args.at(-1), 'root@47.116.38.184')
  const passwordArgs = buildSshProxyArguments({ ...config, sshProxyPrivateKey: '', sshProxyPassword: 'secret' })
  assert.equal(passwordArgs.includes('-i'), false)
  assert.equal(resolveOfficialProxyUrl({ ...config, officialProxyMode: 'proxy', officialProxyUrl: 'http://127.0.0.1:7890' }), 'http://127.0.0.1:7890/')
  assert.equal(resolveOfficialProxyUrl({ ...config, officialProxyMode: 'disabled' }), undefined)
  assert.throws(() => resolveOfficialProxyUrl({ ...config, officialProxyMode: 'proxy', officialProxyUrl: 'ftp://127.0.0.1' }), /不支持的代理协议/)
})

test('parses packed Litematica block states', () => {
  const blocks = parseLitematic(sampleLitematic(), 10)
  assert.deepEqual(blocks, [{ x: 1, y: 0, z: 0, name: 'minecraft:diamond_block' }])
  assert.deepEqual(parseLitematic(sampleLitematic(), 0), blocks)
})

test('renders horizontal and vertical six-face PNGs within outputSize', () => {
  const blocks = [{ x: 0, y: 0, z: 0, name: 'minecraft:diamond_block' }]
  const config = { outputSize: 300, background: '#182026', transparentBackground: false, sixFaceLayout: 'horizontal' }
  const horizontal = decodePng(renderSixFaceOverview(blocks, config, 'horizontal'))
  const vertical = decodePng(renderSixFaceOverview(blocks, config, 'vertical'))
  assert.deepEqual([horizontal.width, horizontal.height], [300, 200])
  assert.deepEqual([vertical.width, vertical.height], [200, 300])

  const transparent = decodePng(renderSixFaceOverview(blocks, { ...config, transparentBackground: true }, 'horizontal'))
  assert.deepEqual(transparent.pixel(0, 0), [24, 32, 38, 0])
})

test('selects the visible block and orientation for all six orthographic faces', () => {
  const names = [
    'minecraft:redstone_block', 'minecraft:gold_block', 'minecraft:diamond_block', 'minecraft:iron_block',
    'minecraft:grass_block', 'minecraft:blue_wool', 'minecraft:white_wool', 'minecraft:obsidian',
  ]
  const blocks = []
  for (let z = 0; z < 2; z++) for (let y = 0; y < 2; y++) for (let x = 0; x < 2; x++) {
    blocks.push({ x, y, z, name: names[x + y * 2 + z * 4] })
  }
  const png = decodePng(renderSixFaceOverview(blocks, {
    outputSize: 300, background: '#000000', transparentBackground: false, sixFaceLayout: 'horizontal',
  }))
  const gap = 4, tile = 94, inset = 2, blockCenter = 22
  const facePixel = index => {
    const x = gap + (index % 3) * (tile + gap) + inset + blockCenter
    const y = gap + Math.floor(index / 3) * (tile + gap) + inset + blockCenter
    return png.pixel(x, y)
  }
  assert.deepEqual(facePixel(0), [94, 214, 199, 255])
  assert.deepEqual(facePixel(1), [246, 197, 67, 255])
  assert.deepEqual(facePixel(2), [33, 29, 45, 255])
  assert.deepEqual(facePixel(3), [233, 233, 233, 255])
  assert.deepEqual(facePixel(4), [94, 214, 199, 255])
  assert.deepEqual(facePixel(5), [215, 215, 215, 255])

  for (let index = 0; index < 6; index++) {
    const tileX = gap + (index % 3) * (tile + gap)
    const tileY = gap + Math.floor(index / 3) * (tile + gap)
    let whitePixels = 0
    for (let y = tileY; y < tileY + 20; y++) for (let x = tileX; x < tileX + 20; x++) {
      if (png.pixel(x, y).slice(0, 3).every(channel => channel === 255)) whitePixels++
    }
    assert.ok(whitePixels > 0, `face ${index} should contain a Chinese direction glyph`)
  }
})

test('uses corrected embedded Chinese glyphs for all six face labels', () => {
  assert.deepEqual(Object.keys(SIX_FACE_LABELS), ['up', 'down', 'east', 'south', 'west', 'north'])
  for (const [face, glyph] of Object.entries(SIX_FACE_LABELS)) {
    assert.equal(glyph.length, 16, `${face} glyph should be 16 pixels tall`)
    assert.ok(glyph.every(row => row.length === 16 && /^[01]+$/.test(row)), `${face} glyph should be a 16x16 bitmap`)
  }
  assert.equal(SIX_FACE_LABELS.up[12], '1111111111111000')
  assert.equal(SIX_FACE_LABELS.down[1], '1111111111111000')
  assert.equal(SIX_FACE_LABELS.east[7], '0111111111110000')
  assert.equal(SIX_FACE_LABELS.south[9], '1111111111110000')
  assert.equal(SIX_FACE_LABELS.west[11], '0111111111110000')
  assert.equal(SIX_FACE_LABELS.north[12], '0000100011111000')
})

test('reads the Litematic metadata used by the forwarded text footer', () => {
  const metadata = parseLitematicMetadata(sampleLitematic())
  assert.deepEqual(metadata, {
    author: 'CNJ233',
    createdAt: '2024-06-23 16:33:06',
    totalBlocks: 421,
    totalVolume: 720,
    size: [4, 10, 18],
    litematicVersion: 6,
    minecraftDataVersion: 3465,
    minecraftVersion: '1.20.1',
  })
  assert.equal(formatLitematicMetadata(metadata), [
    '保存者游戏 ID：CNJ233',
    '创建时间：2024-06-23 16:33:06',
    '方块数/体积：421/720',
    '尺寸：4 × 10 × 18',
    'Litematic 版本：6',
    '游戏版本：1.20.1（数据版本：3465）',
  ].join('\n'))
})

test('shows every effective config field and omits obsolete fields', () => {
  assert.equal(RendererConfig.type, 'intersect')
  assert.equal(RendererConfig.list[0].meta.description, '机器人接入')
  assert.equal(Object.keys(RendererConfig.list[0].dict)[0], 'qqBotType')
  const fields = Object.assign({}, ...RendererConfig.list.map(section => section.dict))
  assert.equal(fields.maxBlocks, undefined)
  assert.equal(fields.javaPath.meta.role, 'path')
  assert.equal(fields.resourcePackPaths.meta.role, 'table')
  assert.equal(fields.standaloneJavaCommand, undefined)
  assert.equal(fields.javaSupersampling, undefined)
  assert.equal(fields.remoteAgentUrl, undefined)
  assert.ok(fields.javaBridgeDirectory === undefined)
  assert.ok(fields.gpuClientGameDirectory === undefined)
  assert.ok(fields.gpuRendererCommand === undefined)
  assert.equal(fields.cellSize, undefined)
  assert.equal(fields.diagnosticsExport, undefined)
  for (const index of [3, 4, 5]) {
    assert.equal(RendererConfig.list[index].meta.collapse, true, `${RendererConfig.list[index].meta.description} should be collapsed`)
  }
  for (const [name, schema] of Object.entries(fields)) {
    if (['javaResolution', 'webglWidth', 'webglHeight', 'officialProxyMode', 'officialProxyUrl',
      'sshProxyExecutable', 'sshProxyHost', 'sshProxyPort', 'sshProxyUser', 'sshProxyPrivateKey',
      'sshProxyPassword', 'sshProxyLocalPort'].includes(name)) continue
    assert.notEqual(schema.meta.hidden, true, `${name} should be visible`)
  }
})

test('stores uploaded ZIP resource packs with sanitized content-addressed names', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'litematic-resource-pack-'))
  const zip = Buffer.from([0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4])
  try {
    const output = await saveUploadedResourcePack('../测试:*材质.zip', zip.toString('base64'), directory)
    assert.match(output.replaceAll('\\', '/'), /测试__材质-[0-9a-f]{12}\.zip$/)
    assert.deepEqual(await readFile(output), zip)
    await assert.rejects(saveUploadedResourcePack('材质.txt', zip.toString('base64'), directory), /只允许上传 ZIP/)
    await assert.rejects(saveUploadedResourcePack('伪造.zip', Buffer.from('not zip').toString('base64'), directory), /不是有效的 ZIP/)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('includes the projection name in forwarded metadata', () => {
  const metadata = parseLitematicMetadata(sampleLitematic())
  assert.match(formatLitematicMetadata(metadata, '测试投影.litematic'), /^投影名称：测试投影\n/)
})

test('keeps versioned cache entries until the total limit requires LRU eviction', async () => {
  const root = await mkdtemp(join(tmpdir(), 'litematic-cache-test-'))
  const oldEntry = join(root, 'v0.3.6', 'old-hash')
  const currentEntry = join(root, 'v0.3.7', 'current-hash')
  try {
    await mkdir(oldEntry, { recursive: true })
    await mkdir(currentEntry, { recursive: true })
    await writeFile(join(oldEntry, 'projection.litematic'), Buffer.alloc(80, 1))
    await writeFile(join(currentEntry, 'projection.litematic'), Buffer.alloc(80, 2))
    const oldTime = new Date(Date.now() - 60_000)
    await utimes(oldEntry, oldTime, oldTime)

    const result = await enforceCacheLimit(root, 100, currentEntry)
    assert.equal(result.removedEntries, 1)
    await assert.rejects(access(oldEntry))
    await access(currentEntry)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('uses a different cache folder when image settings change', () => {
  const baseline = { background: '#000000', transparentBackground: false }
  assert.notEqual(hashRenderConfiguration(baseline), hashRenderConfiguration({ ...baseline, background: '#ffffff' }))
  assert.notEqual(hashRenderConfiguration(baseline), hashRenderConfiguration({ ...baseline, transparentBackground: true }))
})

test('accepts Java 21+ and applies bounded-memory JVM options', () => {
  assert.equal(supportsStandaloneJavaVersion(20), false)
  assert.equal(supportsStandaloneJavaVersion(21), true)
  assert.equal(supportsStandaloneJavaVersion(25), true)
  assert.deepEqual(standaloneJvmOptions(2048), [
    '-Xms128m', '-Xmx2048m', '-XX:+UseG1GC', '-XX:+UseStringDeduplication', '-XX:+ExitOnOutOfMemoryError',
  ])
  assert.ok(standaloneJvmOptions(64).includes('-Xmx128m'))
  assert.ok(standaloneJvmOptions(100000).includes('-Xmx32768m'))
  assert.equal(isJavaMemoryFailure('java.lang.OutOfMemoryError: Java heap space', 3), true)
  assert.equal(isJavaMemoryFailure('Native memory allocation (malloc) failed', 1), true)
  assert.equal(isJavaMemoryFailure('', 137), true)
  assert.equal(isJavaMemoryFailure('', 3), true)
  assert.equal(isJavaMemoryFailure('Invalid command line option', 1), false)
})

test('uses the main image clarity setting for every render backend', () => {
  assert.equal(effectiveRenderResolution({ outputSize: 2048 }), 2048)
  assert.equal(effectiveRenderResolution({ outputSize: 99999 }), 4096)
  assert.equal(effectiveRenderResolution({ outputSize: 1 }), 256)
})

test('resolves the last matching per-group send override', () => {
  const config = {
    qqBotType: 'selfHosted',
    sendAsForward: true,
    replyAndMention: false,
    sixFaceOverview: true,
    groupSendOptions: [
      { groupId: '123', sendMode: 'forward', replyAndMention: 'inherit' },
      { groupId: '123', sendMode: 'combined', replyAndMention: 'enabled' },
      { groupId: '456', sendMode: 'combined', replyAndMention: 'disabled' },
    ],
  }
  assert.deepEqual(resolveSendOptions(config, '123'), { qqBotType: 'selfHosted', sendMode: 'combined', replyAndMention: true, showViewTitles: false, sixFaceOverview: false })
  assert.deepEqual(resolveSendOptions(config, '456'), { qqBotType: 'selfHosted', sendMode: 'combined', replyAndMention: false, showViewTitles: false, sixFaceOverview: false })
  assert.deepEqual(resolveSendOptions(config, '789'), { qqBotType: 'selfHosted', sendMode: 'forward', replyAndMention: false, showViewTitles: false, sixFaceOverview: true })
  assert.equal(resolveSendOptions({ ...config, sixFaceOverview: false }, '789').sixFaceOverview, false)
  assert.equal(resolveSendOptions({ ...config, qqBotType: 'official', sendAsForward: false }, '789').sixFaceOverview, true)
})

test('sends two images and metadata as one combined message with optional reply and mention', async () => {
  const sent = []
  const session = {
    selfId: 'bot', userId: 'user', messageId: 'message',
    send: async content => { sent.push(content) },
  }
  await sendImages(session, [
    { title: '正二轴测', path: 'normal.png' },
    { title: '反向正二轴测', path: 'reverse.png' },
  ], '投影信息', { qqBotType: 'selfHosted', sendMode: 'combined', replyAndMention: true, showViewTitles: false, sixFaceOverview: false }, '测试投影')

  assert.equal(sent.length, 1)
  assert.deepEqual(sent[0].map(element => element.type), ['quote', 'at', 'text', 'img', 'img', 'text'])
  assert.equal(sent[0].at(-1).attrs.content, '\n投影信息\n测试投影 已渲染成功')
})

test('keeps Chinese projection names in cache folders and formats size-limit errors', () => {
  assert.equal(cacheNameSegment('城堡 主楼?.litematic'), '城堡 主楼_')
  assert.equal(formatRenderError(new Error('文件超过 1 MB 限制'), { maxFileSize: 2 * 1024 }), '文件大小超过 2.00 MB，不渲染。')
  assert.equal(formatRenderError(new Error('独立 Java 渲染器不存在：C:\\private\\renderer.jar'), { maxFileSize: 1024 }), '独立渲染器不可用，请重装插件或检查 Java 渲染配置。')
  assert.equal(formatRenderError(new Error('Minecraft 26.2 GPU 渲染端未运行；请启动客户端'), { maxFileSize: 1024 }), '请启动 Minecraft 26.2-Fabricjqr GPU 渲染客户端，停在主菜单即可，无需手动进入存档。')
})

test('omits unsupported mentions and blank lines from QQ official replies', () => {
  const official = replyElements({ platform: 'qq', messageId: 'message', userId: 'DBE12CB2B68ABF9BDA1CF31DD662DCDC' }, 'official')
  assert.deepEqual(official.map(element => element.type), ['quote'])
  assert.equal(official.some(element => JSON.stringify(element).includes('DBE12CB2B68ABF9BDA1CF31DD662DCDC')), false)

  const selfHosted = replyElements({ platform: 'onebot', messageId: 'message', userId: '12345' }, 'selfHosted')
  assert.deepEqual(selfHosted.map(element => element.type), ['quote', 'at', 'text'])
  assert.equal(selfHosted[2].attrs.content, '\n')
})

test('sends forward content before one concise result mention', async () => {
  const sent = []
  const session = {
    selfId: 'bot', userId: 'user', messageId: 'message',
    send: async content => { sent.push(content) },
  }
  await sendImages(session, [
    { title: '正二轴测', path: 'normal.png' },
    { title: '反向正二轴测', path: 'reverse.png' },
    { title: '六面正投影', path: 'six-faces.png' },
  ], '投影信息', { qqBotType: 'selfHosted', sendMode: 'forward', replyAndMention: true, showViewTitles: false, sixFaceOverview: true }, '测试投影')

  assert.equal(sent.length, 2)
  assert.equal(sent[0].type, 'figure')
  assert.equal(sent[0].children.length, 4)
  assert.deepEqual(sent[1].map(element => element.type), ['quote', 'at', 'text', 'text'])
  assert.equal(sent[1].at(-1).attrs.content, '测试投影 已渲染成功，结果如上')
})

test('always sends one plain success notification after forward content without reply mention', async () => {
  const sent = []
  const session = { selfId: 'bot', userId: 'user', messageId: 'message', send: async content => { sent.push(content) } }
  await sendImages(session, [
    { title: '正二轴测', path: 'normal.png' },
    { title: '反向正二轴测', path: 'reverse.png' },
  ], '投影信息', { qqBotType: 'selfHosted', sendMode: 'forward', replyAndMention: false, showViewTitles: false, sixFaceOverview: true }, '测试投影')
  assert.equal(sent.length, 2)
  assert.equal(sent[1], '测试投影 已渲染成功，结果如上')
})

test('sends QQ official forward-mode results as one overview image and summary message', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'litematic-qq-overview-'))
  const sent = []
  const session = {
    platform: 'qq', selfId: 'bot', userId: 'user', messageId: 'message',
    send: async content => { sent.push(content) },
  }
  try {
    const png = renderSixFaceOverview([{ x: 0, y: 0, z: 0, name: 'minecraft:diamond_block' }], {
      outputSize: 128, background: '#000000', transparentBackground: true, sixFaceLayout: 'horizontal',
    })
    const images = await Promise.all(['normal.png', 'reverse.png', 'six-faces.png'].map(async (name, index) => {
      const path = join(directory, name)
      await writeFile(path, png)
      return { title: `视图 ${index + 1}`, path }
    }))
    await sendImages(session, images, '投影信息', { qqBotType: 'official', sendMode: 'forward', replyAndMention: false, showViewTitles: false, sixFaceOverview: true }, '测试投影')

    assert.equal(sent.length, 1)
    assert.deepEqual(sent[0].map(element => element.type), ['img', 'text'])
    assert.equal(new URL(sent[0][0].attrs.src).protocol, 'file:')
    assert.equal(sent[0][0].attrs.src.includes('\\'), false)
    assert.equal(sent[0][1].attrs.content, '投影信息')
    const overview = await readFile(join(directory, 'qq-overview.png'))
    assert.deepEqual([overview.readUInt32BE(16), overview.readUInt32BE(20)], [128, 132])
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('keeps the quote but omits unsupported mentions from the QQ official overview message', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'litematic-qq-reply-'))
  const sent = []
  const session = {
    platform: 'qq', selfId: 'bot', userId: 'user', messageId: 'message',
    send: async content => { sent.push(content) },
  }
  try {
    const png = renderSixFaceOverview([{ x: 0, y: 0, z: 0, name: 'minecraft:diamond_block' }], {
      outputSize: 128, background: '#000000', transparentBackground: true, sixFaceLayout: 'horizontal',
    })
    const images = await Promise.all(['normal.png', 'reverse.png'].map(async name => {
      const path = join(directory, name)
      await writeFile(path, png)
      return { title: name, path }
    }))
    await sendImages(session, images, '投影信息', { qqBotType: 'official', sendMode: 'forward', replyAndMention: true, showViewTitles: false, sixFaceOverview: true }, '测试投影')

    assert.equal(sent.length, 1)
    assert.deepEqual(sent[0].map(element => element.type), ['quote', 'img', 'text'])
    assert.equal(sent[0][0].attrs.id, 'message')
    assert.equal(sent[0][2].attrs.content, '投影信息')
    assert.equal(JSON.stringify(sent[0]).includes('<@user>'), false)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})

test('detects litematic files from message content when session elements are unavailable', () => {
  const file = findLitematicFile({
    elements: undefined,
    content: '<file name="跨环境测试.litematic" url="https://example.invalid/test.litematic"/>',
  })
  assert.equal(file?.name, '跨环境测试.litematic')
  assert.equal(file?.url, 'https://example.invalid/test.litematic')
})

test('does not treat unnamed or unrelated files as litematic projections', () => {
  assert.equal(findLitematicFile({ elements: [{ type: 'file', attrs: { url: 'https://example.invalid/image.png' } }], content: '' }), undefined)
  assert.equal(findLitematicFile({ elements: [{ type: 'file', attrs: { name: 'map.zip', url: 'https://example.invalid/map.zip' } }], content: '' }), undefined)
})

test('gates private rendering without affecting group and channel sessions', () => {
  assert.equal(canRenderInSession({ guildId: 'group-123', isDirect: false }, false), true)
  assert.equal(canRenderInSession({ guildId: 'channel-123', isDirect: false }, false), true)
  assert.equal(canRenderInSession({ guildId: undefined, isDirect: true }, false), false)
  assert.equal(canRenderInSession({ guildId: undefined, isDirect: true }, true), true)
  assert.equal(canRenderInSession({ guildId: undefined, isDirect: false }, true), false)
})

test('applies group whitelist and blacklist with blacklist precedence', () => {
  const both = { groupWhitelist: ['g1', ' g2 '], groupBlacklist: ['bad'] }
  assert.equal(isGroupAllowed(both, 'g1'), true)
  assert.equal(isGroupAllowed(both, 'g2'), true, '白名单匹配应忽略首尾空格')
  assert.equal(isGroupAllowed(both, 'g3'), false, '白名单非空时未列出的群不可用')
  assert.equal(isGroupAllowed(both, 'bad'), false, '黑名单优先于白名单')
  const blacklistOnly = { groupWhitelist: [], groupBlacklist: ['bad'] }
  assert.equal(isGroupAllowed(blacklistOnly, 'any-group'), true)
  assert.equal(isGroupAllowed(blacklistOnly, 'bad'), false)
  assert.equal(isGroupAllowed({ groupWhitelist: [], groupBlacklist: [] }, 'any-group'), true)
  assert.equal(isGroupAllowed(both, undefined), true, '私聊不受名单约束')
})

test('recognizes nested and raw OneBot litematic filenames without matching suffixes', () => {
  assert.equal(findLitematicFile({
    elements: [{ type: 'file', attrs: { file: { name: 'nested.litematic', url: 'https://example.invalid/nested' } } }],
    content: '',
  })?.file?.name, 'nested.litematic')
  assert.equal(findLitematicFile({
    elements: [{ type: 'file', attrs: { url: 'https://example.invalid/unknown' } }],
    content: '[文件 raw-name.litematic]',
    event: { _data: { file: { name: 'raw-name.litematic', url: 'https://example.invalid/raw' } } },
  })?.name, 'raw-name.litematic')
  assert.equal(findLitematicFile({
    elements: [],
    content: '',
    event: { data: { message: { upload: { filename: 'alternate.litematic', file_id: 'id-1' } } } },
  })?.filename, 'alternate.litematic')
  assert.equal(findLitematicFile({
    elements: [{ type: 'file', attrs: { name: 'not-a-projection.zip.txt', url: 'https://example.invalid/no' } }],
    content: '',
  }), undefined)
  assert.equal(findLitematicFile({
    elements: [{ type: 'file', attrs: { url: 'https://example.invalid/bracketed' } }],
    content: '[文件 4gt补盒[正经储] 有外接口.litematic]',
  })?.name, '4gt补盒[正经储] 有外接口.litematic')
})

test('restores koishi-plugin-adapter-onebot group upload file sessions', () => {
  const source = `
        session.subtype = (0, import_koishi2.hyphenate)(data.sub_type);
      // https://github.com/koishijs/koishi-plugin-adapter-onebot/issues/33
      // case 'offline_file':
      //   session.elements = [h('file', data.file)]
      //   session.type = 'message'
      //   session.subtype = 'private'
      //   session.isDirect = true
      //   session.subsubtype = 'offline-file-added'
      //   break
      // case 'group_upload':
      //   session.elements = [h('file', data.file)]
      //   session.type = 'message'
      //   session.subtype = 'group'
      //   session.subsubtype = 'guild-file-added'
      //   break
      default:
        return;
`
  const result = patchOneBotAdapterSource(source)
  assert.equal(result.changed, true)
  assert.equal(result.alreadyCompatible, false)
  assert.match(result.source, /case "group_upload":/)
  assert.match(result.source, /\(0, import_koishi2\.h\)\("file", data\.file\)/)
  assert.doesNotMatch(result.source, /\/\/ case 'group_upload':/)

  const second = patchOneBotAdapterSource(result.source)
  assert.equal(second.changed, false)
  assert.equal(second.alreadyCompatible, true)
})
