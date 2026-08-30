import { message, send } from '@koishijs/client'
import './style.css'

function nextFrame() {
  return new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
}

function findResourcePackItems() {
  return [...document.querySelectorAll<HTMLElement>('.k-schema-item')].filter((item) => {
    return item.querySelector('h3')?.textContent?.trim() === 'resourcePackPaths'
  })
}

function setupResourcePackControl(item: HTMLElement) {
  if (item.dataset.litematicResourcePacks === 'ready') return
  const controls = item.querySelector<HTMLElement>('.k-schema-right')
  if (!controls) return
  item.dataset.litematicResourcePacks = 'ready'

  const toolbar = document.createElement('div')
  toolbar.className = 'litematic-resource-pack-toolbar'
  const uploadButton = createButton('上传材质包', true)
  const upButton = createButton('上移')
  const downButton = createButton('下移')
  const fileInput = document.createElement('input')
  fileInput.type = 'file'
  fileInput.accept = '.zip,application/zip'
  fileInput.hidden = true
  toolbar.append(uploadButton, upButton, downButton, fileInput)
  controls.prepend(toolbar)

  let selected = -1
  const rows = () => [...item.querySelectorAll<HTMLTableRowElement>('.k-schema-table tr')]
    .filter(row => !!row.querySelector('td'))
  const refresh = () => {
    const currentRows = rows()
    if (selected >= currentRows.length) selected = currentRows.length - 1
    currentRows.forEach((row, index) => row.classList.toggle('litematic-selected', index === selected))
    upButton.disabled = selected <= 0
    downButton.disabled = selected < 0 || selected >= currentRows.length - 1
  }

  item.addEventListener('click', (event) => {
    const row = (event.target as Element).closest<HTMLTableRowElement>('.k-schema-table tr')
    if (!row || !row.querySelector('td')) return
    selected = rows().indexOf(row)
    refresh()
  })

  const move = async (offset: -1 | 1) => {
    const currentRows = rows()
    if (selected < 0 || selected + offset < 0 || selected + offset >= currentRows.length) return
    const actions = currentRows[selected].querySelectorAll<HTMLElement>('td.k-schema-table-button')
    actions[offset < 0 ? 0 : 1]?.click()
    selected += offset
    await nextFrame()
    refresh()
  }
  upButton.addEventListener('click', () => void move(-1))
  downButton.addEventListener('click', () => void move(1))
  uploadButton.addEventListener('click', () => fileInput.click())

  fileInput.addEventListener('change', async () => {
    const file = fileInput.files?.[0]
    fileInput.value = ''
    if (!file) return
    if (!/\.zip$/i.test(file.name)) return message.error('只允许上传 ZIP 材质包')
    if (file.size > 256 * 1024 * 1024) return message.error('材质包超过 256 MB 上传上限')

    uploadButton.disabled = true
    uploadButton.textContent = '上传中...'
    try {
      const path = await send('litematic/resource-pack-upload', file.name, await readBase64(file)) as string
      const addButton = [...controls.querySelectorAll<HTMLButtonElement>('button')]
        .find(button => /添加(行|项目)/.test(button.textContent ?? ''))
      if (!addButton) throw new Error('无法找到资源包列表的添加按钮')
      addButton.click()
      await nextFrame()
      const inputs = item.querySelectorAll<HTMLInputElement>('.k-schema-table input')
      const target = inputs[inputs.length - 1]
      if (!target) throw new Error('无法写入资源包路径')
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
      setter?.call(target, path)
      target.dispatchEvent(new Event('input', { bubbles: true }))
      target.dispatchEvent(new Event('change', { bubbles: true }))
      selected = rows().length - 1
      refresh()
      message.success('材质包已上传并设为最高优先级，请保存配置')
    } catch (error) {
      message.error(error instanceof Error ? error.message : String(error))
    } finally {
      uploadButton.disabled = false
      uploadButton.textContent = '上传材质包'
    }
  })

  new MutationObserver(refresh).observe(item, { childList: true, subtree: true })
  refresh()
}

function createButton(label: string, primary = false) {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = `el-button${primary ? ' el-button--primary' : ''}`
  button.textContent = label
  return button
}

function readBase64(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error ?? new Error('读取文件失败'))
    reader.onload = () => resolve(String(reader.result).split(',', 2)[1] ?? '')
    reader.readAsDataURL(file)
  })
}

function scan() {
  findResourcePackItems().forEach(setupResourcePackControl)
}

new MutationObserver(scan).observe(document.body, { childList: true, subtree: true })
scan()

export default () => {}
