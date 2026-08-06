import assert from 'node:assert/strict'
import { access, mkdir, mkdtemp, rm, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { gzipSync } from 'node:zlib'
import { cacheNameSegment, enforceCacheLimit, findLitematicFile, formatLitematicMetadata, formatRenderError, hashRenderConfiguration, isJavaMemoryFailure, isLitematicData, parseLitematic, parseLitematicMetadata, renderSchematic, resolveSendOptions, sendImages, standaloneJvmOptions, supportsStandaloneJavaVersion } from '../lib/index.js'

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

test('parses packed Litematica block states and renders two opposite isometric PNGs', () => {
  const blocks = parseLitematic(sampleLitematic(), 10)
  assert.deepEqual(blocks, [{ x: 1, y: 0, z: 0, name: 'minecraft:diamond_block' }])
  assert.deepEqual(parseLitematic(sampleLitematic(), 0), blocks)
  const images = renderSchematic(blocks, {
    maxFileSize: 1024 * 1024, outputSize: 256, cellSize: 8, isometricCellSize: 7,
    background: '#182026', transparentBackground: false, sendAsForward: true,
    renderTimeout: 1000, cacheDirectory: '.', gpuRendererCommand: '',
    renderEngine: 'cpu', javaBridgeDirectory: '.', javaRenderTimeout: 10000,
    javaResolution: 256, javaSupersampling: 1, webglQuality: 'standard',
    webglWidth: 256, webglHeight: 256, isometricSquare: true, isometricFill: 0.78,
    isometricRotation: 135, isometricSlant: 36,
  })
  assert.deepEqual(images.map(image => image.title), ['isometric.png', 'isometric-reverse.png'])
  for (const image of images) assert.deepEqual(image.png.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
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
  const baseline = { javaResolution: 1024, background: '#000000', transparentBackground: false }
  assert.notEqual(hashRenderConfiguration(baseline), hashRenderConfiguration({ ...baseline, javaResolution: 2048 }))
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
  assert.equal(isJavaMemoryFailure('Invalid command line option', 1), false)
})

test('resolves the last matching per-group send override', () => {
  const config = {
    sendAsForward: true,
    replyAndMention: false,
    groupSendOptions: [
      { groupId: '123', sendMode: 'forward', replyAndMention: 'inherit' },
      { groupId: '123', sendMode: 'combined', replyAndMention: 'enabled' },
      { groupId: '456', sendMode: 'combined', replyAndMention: 'disabled' },
    ],
  }
  assert.deepEqual(resolveSendOptions(config, '123'), { sendMode: 'combined', replyAndMention: true, showViewTitles: false })
  assert.deepEqual(resolveSendOptions(config, '456'), { sendMode: 'combined', replyAndMention: false, showViewTitles: false })
  assert.deepEqual(resolveSendOptions(config, '789'), { sendMode: 'forward', replyAndMention: false, showViewTitles: false })
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
  ], '投影信息', { sendMode: 'combined', replyAndMention: true })

  assert.equal(sent.length, 1)
  assert.deepEqual(sent[0].map(element => element.type), ['quote', 'at', 'text', 'img', 'img', 'text'])
  assert.equal(sent[0].at(-1).attrs.content, '\n投影信息')
})

test('keeps Chinese projection names in cache folders and formats size-limit errors', () => {
  assert.equal(cacheNameSegment('城堡 主楼?.litematic'), '城堡 主楼_')
  assert.equal(formatRenderError(new Error('文件超过 1 MB 限制'), { maxFileSize: 2 * 1024 }), '文件大小超过 2.00 MB，不渲染。')
  assert.equal(formatRenderError(new Error('独立 Java 渲染器不存在：C:\\private\\renderer.jar'), { maxFileSize: 1024 }), '独立渲染器不可用，请重装插件或检查 Java 渲染配置。')
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
  ], '投影信息', { sendMode: 'forward', replyAndMention: true })

  assert.equal(sent.length, 2)
  assert.equal(sent[0].type, 'figure')
  assert.deepEqual(sent[1].map(element => element.type), ['at', 'text'])
  assert.equal(sent[1][1].attrs.content, '渲染结果如上')
})

test('detects litematic files from message content when session elements are empty', () => {
  const file = findLitematicFile({
    elements: [],
    content: '<file name="跨环境测试.litematic" url="https://example.invalid/test.litematic"/>',
  })
  assert.equal(file?.name, '跨环境测试.litematic')
  assert.equal(file?.url, 'https://example.invalid/test.litematic')
})

test('does not treat unnamed or unrelated files as litematic projections', () => {
  assert.equal(findLitematicFile({ elements: [{ type: 'file', attrs: { url: 'https://example.invalid/image.png' } }], content: '' }), undefined)
  assert.equal(findLitematicFile({ elements: [{ type: 'file', attrs: { name: 'map.zip', url: 'https://example.invalid/map.zip' } }], content: '' }), undefined)
})

test('filters files by Litematica content instead of filename only', () => {
  const data = sampleLitematic()
  assert.equal(isLitematicData(data), true)
  assert.equal(isLitematicData(Buffer.from('not a litematic file')), false)
})
