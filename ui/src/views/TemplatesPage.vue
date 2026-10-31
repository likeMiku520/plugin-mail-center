<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  api,
  errorMessage,
  type BuiltinGroup,
  type BuiltinTemplate,
  type BundleImportResult,
  type CustomTemplate,
  type Overview,
  type SystemTemplate,
  type TemplatePreview,
  type TemplateVariable,
} from '../api'
import PreviewModal from '../components/PreviewModal.vue'

const overview = ref<Overview | null>(null)
const groups = ref<BuiltinGroup[]>([])
const custom = ref<CustomTemplate[]>([])
const system = ref<SystemTemplate[]>([])
const loading = ref(true)
const notice = ref('')
const error = ref('')
const message = ref('')
const tab = ref<'builtin' | 'custom'>('builtin')
const keyword = ref('')
const showHidden = ref(false)

const preview = ref<TemplatePreview | null>(null)
const previewTitle = ref('')
const previewOpen = ref(false)
const previewLoading = ref(false)
const previewError = ref('')

/** Values every template can use, on top of the ones a reason type declares. */
const GLOBAL_VARIABLES: TemplateVariable[] = [
  { name: 'subscriber.displayName', label: '收件人显示名', type: 'string', sampleValue: '', required: true },
  { name: 'subscriber.email', label: '收件人邮箱', type: 'string', sampleValue: '', required: true },
  { name: 'site.title', label: '站点名称', type: 'string', sampleValue: '', required: true },
  { name: 'site.url', label: '站点地址', type: 'string', sampleValue: '', required: true },
  { name: 'site.logo', label: '站点图标', type: 'string', sampleValue: '', required: false },
  { name: 'unsubscribeUrl', label: '通知中心地址', type: 'string', sampleValue: '', required: false },
]

/** Halo ships these attribute descriptions in English; the common ones get a label here. */
const VARIABLE_LABELS: Record<string, string> = {
  postName: '文章标识',
  postTitle: '文章标题',
  postUrl: '文章链接',
  postOwner: '文章作者',
  pageName: '页面标识',
  pageTitle: '页面标题',
  pageUrl: '页面链接',
  pageOwner: '页面作者',
  commenter: '评论人',
  commenterEmail: '评论人邮箱',
  content: '评论内容',
  commentName: '评论标识',
  subjectTitle: '内容标题',
  subjectUrl: '内容链接',
  username: '用户名',
  displayName: '显示名',
  email: '邮箱',
  registeredAt: '注册时间',
  pluginName: '插件名称',
  version: '版本号',
  formName: '表单名称',
  reason: '触发原因',
  actor: '操作人',
  oldDisplayName: '原显示名',
  newDisplayName: '新显示名',
}

function variableLabel(variable: TemplateVariable): string {
  return VARIABLE_LABELS[variable.name] || variable.label || variable.name
}

interface EditorState {
  name: string
  displayName: string
  description: string
  subject: string
  content: string
  rawContent: string
  variables: TemplateVariable[]
  enabled: boolean
}

const editorOpen = ref(false)
const editorSaving = ref(false)
const editorError = ref('')
const editor = ref<EditorState>(emptyEditor())

interface BuiltinEditorState {
  name: string
  displayName: string
  reasonType: string
  description: string
  title: string
  htmlBody: string
  rawBody: string
  edited: boolean
  variables: TemplateVariable[]
}

const builtinOpen = ref(false)
const builtinSaving = ref(false)
const builtinError = ref('')
const builtinEditor = ref<BuiltinEditorState>(emptyBuiltinEditor())

interface SystemEditorState {
  key: string
  displayName: string
  subject: string
  htmlBody: string
  rawBody: string
  edited: boolean
}

const systemOpen = ref(false)
const systemSaving = ref(false)
const systemError = ref('')
const systemEditor = ref<SystemEditorState>(emptySystemEditor())

const htmlRef = ref<HTMLTextAreaElement | null>(null)
const rawRef = ref<HTMLTextAreaElement | null>(null)
const lastField = ref<'html' | 'raw'>('html')

const exportOpen = ref(false)
const exportLoading = ref(false)
const exportText = ref('')
const exportError = ref('')

const importOpen = ref(false)
const importLoading = ref(false)
const importError = ref('')
const importResult = ref<BundleImportResult | null>(null)
const importDocument = ref<Record<string, unknown> | null>(null)
const importFileName = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

function emptyEditor(): EditorState {
  return {
    name: '',
    displayName: '',
    description: '',
    subject: '',
    content: '',
    rawContent: '',
    variables: [],
    enabled: true,
  }
}

function emptyBuiltinEditor(): BuiltinEditorState {
  return {
    name: '',
    displayName: '',
    reasonType: '',
    description: '',
    title: '',
    htmlBody: '',
    rawBody: '',
    edited: false,
    variables: [],
  }
}

function emptySystemEditor(): SystemEditorState {
  return { key: '', displayName: '', subject: '', htmlBody: '', rawBody: '', edited: false }
}

const hiddenCount = computed(() => {
  const builtinHidden = groups.value.reduce(
    (total, group) => total + group.templates.filter((item) => item.hidden).length,
    0
  )
  return builtinHidden + custom.value.filter((item) => item.hidden).length
})

function matchesKeyword(...values: (string | undefined)[]): boolean {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) {
    return true
  }
  return values.some((value) => (value || '').toLowerCase().includes(needle))
}

function visibleTemplates(group: BuiltinGroup): BuiltinTemplate[] {
  return group.templates.filter(
    (template) =>
      (showHidden.value || !template.hidden) &&
      matchesKeyword(template.title, template.name, group.displayName, group.reasonType)
  )
}

const filteredGroups = computed(() =>
  groups.value.filter((group) => visibleTemplates(group).length > 0)
)

const filteredCustom = computed(() =>
  custom.value.filter(
    (template) =>
      (showHidden.value || !template.hidden) &&
      matchesKeyword(template.displayName, template.subject, template.description, template.name)
  )
)

const filteredSystem = computed(() =>
  system.value.filter((item) =>
    matchesKeyword(item.displayName, item.subject, item.description, item.name)
  )
)

const importSummary = computed(() => {
  const result = importResult.value
  if (!result) {
    return ''
  }
  return `共 ${result.total} 条：更新 ${result.updated}，无变化 ${result.unchanged}，跳过 ${result.skipped}，失败 ${result.failed}`
})

const canConfirmImport = computed(() => {
  const result = importResult.value
  return Boolean(result && result.dryRun && result.updated > 0 && !importLoading.value)
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [data, stats] = await Promise.all([api.templates(), api.overview()])
    groups.value = data.groups
    custom.value = data.custom
    system.value = data.system || []
    overview.value = stats
    notice.value = stats.channelUsable ? '' : stats.channelProblem || ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function flash(action: () => Promise<unknown>, done: string) {
  error.value = ''
  message.value = ''
  try {
    await action()
    message.value = done
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function openPreview(type: string, name: string, title: string) {
  previewOpen.value = true
  previewLoading.value = true
  previewError.value = ''
  preview.value = null
  previewTitle.value = title
  try {
    preview.value = await api.preview({ templateType: type, templateName: name, variables: {} })
  } catch (e) {
    previewError.value = errorMessage(e)
  } finally {
    previewLoading.value = false
  }
}

function openCreate() {
  editor.value = emptyEditor()
  editorError.value = ''
  editorOpen.value = true
}

function openEdit(template: CustomTemplate) {
  editor.value = {
    name: template.name,
    displayName: template.displayName,
    description: template.description || '',
    subject: template.subject,
    content: template.content || '',
    rawContent: template.rawContent || '',
    variables: (template.variables || []).map((variable) => ({ ...variable })),
    enabled: template.enabled,
  }
  editorError.value = ''
  editorOpen.value = true
}

function openBuiltinEditor(template: BuiltinTemplate, group: BuiltinGroup) {
  builtinEditor.value = {
    name: template.name,
    displayName: group.displayName,
    reasonType: group.reasonType,
    description: group.description || '',
    title: template.title || '',
    htmlBody: template.htmlBody || '',
    rawBody: template.rawBody || '',
    edited: template.edited,
    variables: (group.variables || []).map((variable) => ({ ...variable })),
  }
  builtinError.value = ''
  lastField.value = 'html'
  builtinOpen.value = true
}

function openSystemEditor(template: SystemTemplate) {
  systemEditor.value = {
    key: template.key,
    displayName: template.displayName,
    subject: template.subject,
    htmlBody: template.htmlBody || '',
    rawBody: template.rawBody || '',
    edited: template.edited,
  }
  systemError.value = ''
  systemOpen.value = true
}

function cloneBuiltin(template: BuiltinTemplate, group: BuiltinGroup) {
  editor.value = {
    name: '',
    displayName: `${group.displayName} · 副本`,
    description: `复制自站内模板 ${template.name}`,
    subject: template.title || '',
    content: template.htmlBody || '',
    rawContent: template.rawBody || '',
    variables: (group.variables || []).map((variable) => ({ ...variable })),
    enabled: true,
  }
  tab.value = 'custom'
  editorError.value = ''
  editorOpen.value = true
}

function addVariable() {
  editor.value.variables.push({
    name: '',
    label: '',
    type: 'string',
    sampleValue: '',
    required: false,
  })
}

function removeVariable(index: number) {
  editor.value.variables.splice(index, 1)
}

/** Drops a placeholder where the caret was, so variables do not have to be typed. */
async function insertVariable(name: string) {
  const token = `[(\${${name}})]`
  const usingRaw = lastField.value === 'raw'
  const element = usingRaw ? rawRef.value : htmlRef.value
  const field = usingRaw ? 'rawBody' : 'htmlBody'
  const current = builtinEditor.value[field] || ''
  if (!element) {
    builtinEditor.value[field] = current + token
    return
  }
  const start = element.selectionStart ?? current.length
  const end = element.selectionEnd ?? start
  builtinEditor.value[field] = current.slice(0, start) + token + current.slice(end)
  await nextTick()
  element.focus()
  element.setSelectionRange(start + token.length, start + token.length)
}

/** Appends a placeholder to the test mail body, where there is no caret handling. */
function appendVariable(name: string) {
  systemEditor.value.htmlBody = (systemEditor.value.htmlBody || '') + `[(\${${name}})]`
}

function previewSystem() {
  void openPreview(
    'CUSTOM',
    `mail-template-system-${systemEditor.value.key}`,
    systemEditor.value.displayName
  )
}

async function saveEditor() {
  editorSaving.value = true
  editorError.value = ''
  const payload = {
    displayName: editor.value.displayName,
    description: editor.value.description,
    subject: editor.value.subject,
    content: editor.value.content,
    rawContent: editor.value.rawContent,
    variables: editor.value.variables.filter((variable) => variable.name.trim().length > 0),
    enabled: editor.value.enabled,
  }
  try {
    if (editor.value.name) {
      await api.updateTemplate(editor.value.name, payload)
    } else {
      await api.createTemplate(payload)
    }
    editorOpen.value = false
    message.value = '自定义模板已保存'
    await load()
  } catch (e) {
    editorError.value = errorMessage(e)
  } finally {
    editorSaving.value = false
  }
}

async function saveBuiltin() {
  builtinSaving.value = true
  builtinError.value = ''
  try {
    await api.updateBuiltinTemplate(builtinEditor.value.name, {
      title: builtinEditor.value.title,
      htmlBody: builtinEditor.value.htmlBody,
      rawBody: builtinEditor.value.rawBody,
    })
    builtinOpen.value = false
    message.value = '站内模板已保存'
    await load()
  } catch (e) {
    builtinError.value = errorMessage(e)
  } finally {
    builtinSaving.value = false
  }
}

async function saveSystem() {
  systemSaving.value = true
  systemError.value = ''
  try {
    await api.updateSystemTemplate(systemEditor.value.key, {
      subject: systemEditor.value.subject,
      htmlBody: systemEditor.value.htmlBody,
      rawBody: systemEditor.value.rawBody,
    })
    systemOpen.value = false
    message.value = '测试邮件模板已保存'
    await load()
  } catch (e) {
    systemError.value = errorMessage(e)
  } finally {
    systemSaving.value = false
  }
}

async function checkBuiltinSyntax() {
  builtinError.value = ''
  try {
    await api.validateTemplate({
      subject: builtinEditor.value.title,
      content: builtinEditor.value.htmlBody,
      rawContent: builtinEditor.value.rawBody,
      variables: [],
    })
    message.value = '模板语法检查通过'
  } catch (e) {
    builtinError.value = errorMessage(e)
  }
}

async function resetBuiltin(template: BuiltinTemplate) {
  if (
    !window.confirm(
      `确定把「${template.title || template.name}」恢复成 Halo 自带的内容吗？当前的改动会丢失。`
    )
  ) {
    return
  }
  await flash(
    () => api.resetBuiltinTemplate(template.name),
    '已恢复为自带内容，之后 Halo 升级会重新接管这个模板'
  )
}

async function resetSystem(template: SystemTemplate) {
  if (!window.confirm(`确定把「${template.displayName}」恢复成插件自带的内容吗？`)) {
    return
  }
  await flash(() => api.resetSystemTemplate(template.key), '已恢复为插件自带的内容')
}

async function deleteBuiltin(template: BuiltinTemplate) {
  if (
    !window.confirm(
      `确定删除「${template.title || template.name}」吗？\n\n站内模板由 Halo 自带，Halo 下次启动时可能重新创建它。想让它长期不出现，请改用隐藏。`
    )
  ) {
    return
  }
  await flash(() => api.deleteBuiltinTemplate(template.name), '模板已删除')
}

async function toggleHidden(type: 'BUILTIN' | 'CUSTOM', name: string, hidden: boolean) {
  await flash(
    () => api.hideTemplate(type, name, hidden),
    hidden ? '已隐藏，勾选「显示已隐藏」后还能看到' : '已取消隐藏'
  )
}

async function remove(template: CustomTemplate) {
  if (!window.confirm(`确定删除模板「${template.displayName}」吗？`)) {
    return
  }
  await flash(() => api.deleteTemplate(template.name), '模板已删除')
}

async function checkSyntax() {
  editorError.value = ''
  try {
    await api.validateTemplate({
      subject: editor.value.subject,
      content: editor.value.content,
      rawContent: editor.value.rawContent,
      variables: editor.value.variables.filter((variable) => variable.name.trim().length > 0),
    })
    message.value = '模板语法检查通过'
  } catch (e) {
    editorError.value = errorMessage(e)
  }
}

async function previewDraft() {
  editorError.value = ''
  try {
    preview.value = await api.preview({
      templateType: 'CUSTOM',
      templateName: editor.value.name,
      variables: {},
    })
    previewTitle.value = editor.value.displayName || '自定义模板预览'
    previewOpen.value = true
  } catch (e) {
    editorError.value = errorMessage(e)
  }
}

function stamp(): string {
  const now = new Date()
  const pad = (input: number) => String(input).padStart(2, '0')
  return (
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-` +
    `${pad(now.getHours())}${pad(now.getMinutes())}`
  )
}

async function openExport() {
  exportOpen.value = true
  exportLoading.value = true
  exportError.value = ''
  exportText.value = ''
  try {
    const bundle = await api.exportBundle()
    const json = JSON.stringify(bundle, null, 2)
    exportText.value = json
    const blob = new Blob([json], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `mail-center-templates-${stamp()}.json`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    setTimeout(() => URL.revokeObjectURL(url), 2000)
    message.value = '模板文件已导出，可以拿去改样式，改完再导入回来'
  } catch (e) {
    exportError.value = errorMessage(e)
  } finally {
    exportLoading.value = false
  }
}

async function copyExport() {
  try {
    await navigator.clipboard.writeText(exportText.value)
    message.value = '已复制到剪贴板'
  } catch (e) {
    exportError.value = '浏览器不允许自动复制，请手动全选复制'
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function onFileChosen(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files && input.files[0]
  input.value = ''
  if (!file) {
    return
  }
  importOpen.value = true
  importLoading.value = true
  importError.value = ''
  importResult.value = null
  importDocument.value = null
  importFileName.value = file.name
  try {
    const text = await file.text()
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch (e) {
      throw new Error('这个文件不是合法的 JSON，可能是复制或保存时被截断了')
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('文件内容不是一个模板包对象')
    }
    const document_ = parsed as Record<string, unknown>
    const format = typeof document_.format === 'string' ? document_.format : ''
    if (format && !format.startsWith('mail-center-templates')) {
      throw new Error('这个文件不是「邮件中心」导出的模板文件')
    }
    const result = await api.importBundle(document_, true)
    importDocument.value = document_
    importResult.value = result
  } catch (e) {
    importError.value = errorMessage(e)
  } finally {
    importLoading.value = false
  }
}

async function confirmImport() {
  if (!importDocument.value) {
    return
  }
  importLoading.value = true
  importError.value = ''
  try {
    importResult.value = await api.importBundle(importDocument.value, false)
    await load()
    message.value = importSummary.value
  } catch (e) {
    importError.value = errorMessage(e)
  } finally {
    importLoading.value = false
  }
}

function closeImport() {
  importOpen.value = false
  importDocument.value = null
  importResult.value = null
  importError.value = ''
}

function importStatusLabel(status: string): string {
  if (status === 'updated') return '已更新'
  if (status === 'unchanged') return '无变化'
  if (status === 'skipped') return '跳过'
  return '失败'
}

function importStatusClass(status: string): string {
  if (status === 'updated') return 'mc-tag mc-tag--primary'
  if (status === 'skipped') return 'mc-tag mc-tag--warn'
  if (status === 'failed') return 'mc-tag mc-tag--danger'
  return 'mc-tag'
}

function importSectionLabel(section: string): string {
  if (section === 'builtin') return '站内模板'
  if (section === 'custom') return '自定义模板'
  if (section === 'system') return '插件自带'
  return section
}

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>邮件中心</h2>
        <p>站点上所有能发出去的邮件模板都在这里，既可以直接拿来发信，也能改成自己的样子。</p>
      </div>
      <div class="mc-toolbar">
        <label class="mc-check">
          <input v-model="showHidden" type="checkbox" />
          显示已隐藏{{ hiddenCount ? `（${hiddenCount}）` : '' }}
        </label>
        <button class="mc-btn" type="button" @click="load">刷新</button>
        <button class="mc-btn" type="button" @click="openExport">导出模板文件</button>
        <button class="mc-btn" type="button" @click="pickFile">导入模板文件</button>
        <button class="mc-btn mc-btn--primary" type="button" @click="openCreate">
          新建自定义模板
        </button>
        <input ref="fileInput" type="file" accept=".json,application/json" hidden @change="onFileChosen" />
      </div>
    </div>

    <div v-if="overview" class="mc-grid mc-grid--stats">
      <div class="mc-stat">
        <strong>{{ overview.builtinTemplates }}</strong>
        <span>站内可发送模板（{{ overview.builtinReasonTypes }} 类通知）</span>
      </div>
      <div class="mc-stat">
        <strong>{{ overview.customTemplates }}</strong>
        <span>自定义模板</span>
      </div>
      <div class="mc-stat">
        <strong>{{ overview.sentToday }}</strong>
        <span>今日发送成功</span>
      </div>
      <div class="mc-stat">
        <strong>{{ overview.enabledTriggers }}</strong>
        <span>启用中的触发规则</span>
      </div>
    </div>

    <div v-if="notice" class="mc-notice mc-notice--warn">
      发信通道还没就绪：{{ notice }}
      <router-link to="/mail-center/settings" style="color: inherit; text-decoration: underline">
        去发信设置
      </router-link>
    </div>
    <div v-if="overview?.linksPluginDetected" class="mc-notice">
      检测到「链接管理」插件：友链审核通过或被拒绝时会给申请人回信，两个模板在自定义模板里。
    </div>
    <div v-if="message" class="mc-notice mc-notice--ok">{{ message }}</div>
    <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>

    <div class="mc-tabs">
      <button type="button" :class="{ 'is-active': tab === 'builtin' }" @click="tab = 'builtin'">
        站内模板（Halo 通知）
      </button>
      <button type="button" :class="{ 'is-active': tab === 'custom' }" @click="tab = 'custom'">
        自定义模板（{{ custom.length }}）
      </button>
    </div>

    <div class="mc-card">
      <div class="mc-card__title">
        <h3>{{ tab === 'builtin' ? '站内可发送邮件模板' : '自定义邮件模板' }}</h3>
        <input
          v-model="keyword"
          class="mc-input"
          style="max-width: 240px"
          placeholder="搜索模板名称 / 通知类型"
        />
      </div>

      <div v-if="loading" class="mc-empty">正在读取模板…</div>

      <template v-else-if="tab === 'builtin'">
        <div v-if="filteredSystem.length" class="mc-list" style="margin-bottom: 14px">
          <div v-for="item in filteredSystem" :key="item.key" class="mc-item">
            <div class="mc-item__main">
              <div class="mc-item__title">
                <span>{{ item.displayName }}</span>
                <span class="mc-tag mc-tag--primary">插件自带</span>
                <span v-if="item.edited" class="mc-tag">已自定义</span>
              </div>
              <div class="mc-item__meta">{{ item.description }}</div>
              <div class="mc-item__meta">{{ item.subject }}</div>
            </div>
            <div class="mc-item__actions">
              <button
                class="mc-btn mc-btn--sm"
                type="button"
                @click="openPreview('CUSTOM', item.name, item.displayName)"
              >
                预览
              </button>
              <button class="mc-btn mc-btn--sm" type="button" @click="openSystemEditor(item)">
                编辑
              </button>
              <button
                class="mc-btn mc-btn--sm"
                type="button"
                :disabled="!item.edited"
                :title="item.edited ? '恢复成插件自带的内容' : '还没有改动过'"
                @click="resetSystem(item)"
              >
                重置
              </button>
            </div>
          </div>
        </div>

        <div v-if="filteredGroups.length === 0" class="mc-empty">没有匹配的模板</div>
        <div v-else class="mc-list">
          <div v-for="group in filteredGroups" :key="group.reasonType" class="mc-item">
            <div class="mc-item__main">
              <div class="mc-item__title">
                <span>{{ group.displayName }}</span>
                <span v-if="group.hidden" class="mc-tag">系统隐藏</span>
                <span class="mc-tag">{{ visibleTemplates(group).length }} 个模板</span>
                <span v-if="group.variables.length" class="mc-tag">
                  {{ group.variables.length }} 个可用变量
                </span>
              </div>
              <div class="mc-item__meta">
                {{ group.reasonType }}
                <template v-if="group.description"> · {{ group.description }}</template>
              </div>
              <div style="margin-top: 10px" class="mc-list mc-list--tight">
                <div
                  v-for="template in visibleTemplates(group)"
                  :key="template.name"
                  class="mc-item"
                  style="padding: 8px 10px"
                >
                  <div class="mc-item__main">
                    <div class="mc-item__title" style="font-weight: 500">
                      {{ template.title || '（模板标题为空）' }}
                      <span v-if="template.hidden" class="mc-tag">已隐藏</span>
                      <span v-if="template.edited" class="mc-tag mc-tag--primary">已自定义</span>
                    </div>
                    <div class="mc-item__meta">
                      {{ template.name }} · 语言 {{ template.language }}
                    </div>
                  </div>
                  <div class="mc-item__actions">
                    <button
                      class="mc-btn mc-btn--sm"
                      type="button"
                      @click="openPreview('BUILTIN', template.name, group.displayName)"
                    >
                      预览
                    </button>
                    <button
                      class="mc-btn mc-btn--sm"
                      type="button"
                      @click="openBuiltinEditor(template, group)"
                    >
                      编辑
                    </button>
                    <button
                      class="mc-btn mc-btn--sm"
                      type="button"
                      @click="cloneBuiltin(template, group)"
                    >
                      复制
                    </button>
                    <button
                      class="mc-btn mc-btn--sm"
                      type="button"
                      :disabled="!template.edited"
                      :title="template.edited ? '恢复成 Halo 自带的内容' : '还没有改动过，不需要重置'"
                      @click="resetBuiltin(template)"
                    >
                      重置
                    </button>
                    <button
                      class="mc-btn mc-btn--sm"
                      type="button"
                      @click="toggleHidden('BUILTIN', template.name, !template.hidden)"
                    >
                      {{ template.hidden ? '取消隐藏' : '隐藏' }}
                    </button>
                    <button
                      class="mc-btn mc-btn--sm mc-btn--danger"
                      type="button"
                      @click="deleteBuiltin(template)"
                    >
                      删除
                    </button>
                  </div>
                </div>
                <div v-if="visibleTemplates(group).length === 0" class="mc-item__meta">
                  这一类通知的模板都被隐藏或删掉了。
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div v-if="filteredCustom.length === 0" class="mc-empty">
          还没有自定义模板。点右上角「新建自定义模板」，或者从站内模板复制一份来改。
        </div>
        <div v-else class="mc-list">
          <div v-for="template in filteredCustom" :key="template.name" class="mc-item">
            <div class="mc-item__main">
              <div class="mc-item__title">
                <span>{{ template.displayName }}</span>
                <span v-if="!template.enabled" class="mc-tag mc-tag--warn">已停用</span>
                <span v-if="template.hidden" class="mc-tag">已隐藏</span>
                <span v-if="template.variables.length" class="mc-tag">
                  {{ template.variables.length }} 个变量
                </span>
              </div>
              <div class="mc-item__meta">{{ template.subject }}</div>
              <div v-if="template.description" class="mc-item__meta">
                {{ template.description }}
              </div>
            </div>
            <div class="mc-item__actions">
              <button
                class="mc-btn mc-btn--sm"
                type="button"
                @click="openPreview('CUSTOM', template.name, template.displayName)"
              >
                预览
              </button>
              <button class="mc-btn mc-btn--sm" type="button" @click="openEdit(template)">
                编辑
              </button>
              <button
                class="mc-btn mc-btn--sm"
                type="button"
                @click="toggleHidden('CUSTOM', template.name, !template.hidden)"
              >
                {{ template.hidden ? '取消隐藏' : '隐藏' }}
              </button>
              <button
                class="mc-btn mc-btn--sm mc-btn--danger"
                type="button"
                @click="remove(template)"
              >
                删除
              </button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <PreviewModal
      v-if="previewOpen"
      :title="previewTitle"
      :preview="preview"
      :loading="previewLoading"
      :error="previewError"
      @close="previewOpen = false"
    />

    <div v-if="builtinOpen" class="mc-modal" @click.self="builtinOpen = false">
      <div class="mc-modal__panel mc-modal__panel--wide">
        <div class="mc-modal__head">
          <h3>编辑站内模板 · {{ builtinEditor.displayName }}</h3>
          <button
            class="mc-btn mc-btn--sm mc-btn--ghost"
            type="button"
            @click="builtinOpen = false"
          >
            关闭
          </button>
        </div>
        <div class="mc-modal__body">
          <div v-if="builtinError" class="mc-notice mc-notice--error">{{ builtinError }}</div>
          <div class="mc-notice">
            这是 Halo 自带的通知模板。保存后会标记为「已自定义」，Halo 升级时不再覆盖它；点重置可以拿回自带的内容。
          </div>

          <div class="mc-section-title">这个模板能用的变量</div>
          <div class="mc-hint" style="margin-bottom: 8px">
            点一下变量名，会插到光标所在位置。{{ builtinEditor.description }}
          </div>
          <div v-if="builtinEditor.variables.length" class="mc-list mc-list--tight" style="margin-bottom: 10px">
            <div
              v-for="variable in builtinEditor.variables"
              :key="variable.name"
              class="mc-item"
              style="padding: 7px 10px"
            >
              <div class="mc-item__main">
                <div class="mc-item__title" style="font-weight: 500">
                  <code>{{ variable.name }}</code>
                  <span style="color: var(--mc-text-soft); font-weight: 400">
                    {{ variableLabel(variable) }}
                  </span>
                  <span v-if="variable.required" class="mc-tag">必填</span>
                </div>
              </div>
              <div class="mc-item__actions">
                <button class="mc-btn mc-btn--sm" type="button" @click="insertVariable(variable.name)">
                  插入
                </button>
              </div>
            </div>
          </div>
          <div v-else class="mc-empty mc-empty--sm" style="margin-bottom: 10px">
            这一类通知没有额外变量，用下面的通用变量就够了。
          </div>

          <div class="mc-hint" style="margin-bottom: 6px">所有模板都能用的变量</div>
          <div class="mc-vars" style="margin-bottom: 14px">
            <button
              v-for="variable in GLOBAL_VARIABLES"
              :key="variable.name"
              class="mc-var"
              type="button"
              :title="variable.label"
              @click="insertVariable(variable.name)"
            >
              {{ variable.name }}
            </button>
          </div>

          <label class="mc-field">
            <span>邮件标题</span>
            <input v-model="builtinEditor.title" class="mc-input" />
          </label>
          <label class="mc-field">
            <span>HTML 正文</span>
            <textarea
              ref="htmlRef"
              v-model="builtinEditor.htmlBody"
              class="mc-textarea"
              style="min-height: 200px"
              @focus="lastField = 'html'"
            />
          </label>
          <label class="mc-field">
            <span>纯文本正文（可选）</span>
            <textarea
              ref="rawRef"
              v-model="builtinEditor.rawBody"
              class="mc-textarea"
              style="min-height: 90px"
              @focus="lastField = 'raw'"
            />
          </label>
        </div>
        <div class="mc-modal__foot">
          <button class="mc-btn" type="button" @click="checkBuiltinSyntax">语法检查</button>
          <button
            class="mc-btn mc-btn--primary"
            type="button"
            :disabled="builtinSaving"
            @click="saveBuiltin"
          >
            {{ builtinSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="systemOpen" class="mc-modal" @click.self="systemOpen = false">
      <div class="mc-modal__panel">
        <div class="mc-modal__head">
          <h3>编辑 {{ systemEditor.displayName }}</h3>
          <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="systemOpen = false">
            关闭
          </button>
        </div>
        <div class="mc-modal__body">
          <div v-if="systemError" class="mc-notice mc-notice--error">{{ systemError }}</div>
          <div class="mc-notice">
            点「发送测试」时用的就是这份内容。改完保存，下一次测试就按新的发。
          </div>
          <div class="mc-hint" style="margin-bottom: 6px">所有模板都能用的变量</div>
          <div class="mc-vars" style="margin-bottom: 14px">
            <button
              v-for="variable in GLOBAL_VARIABLES"
              :key="variable.name"
              class="mc-var"
              type="button"
              :title="variable.label"
              @click="appendVariable(variable.name)"
            >
              {{ variable.name }}
            </button>
          </div>
          <label class="mc-field">
            <span>邮件标题</span>
            <input v-model="systemEditor.subject" class="mc-input" />
          </label>
          <label class="mc-field">
            <span>HTML 正文</span>
            <textarea
              v-model="systemEditor.htmlBody"
              class="mc-textarea"
              style="min-height: 200px"
            />
          </label>
          <label class="mc-field">
            <span>纯文本正文（可选）</span>
            <textarea
              v-model="systemEditor.rawBody"
              class="mc-textarea"
              style="min-height: 90px"
            />
          </label>
        </div>
        <div class="mc-modal__foot">
          <button class="mc-btn" type="button" @click="previewSystem">预览已保存版本</button>
          <button
            class="mc-btn mc-btn--primary"
            type="button"
            :disabled="systemSaving"
            @click="saveSystem"
          >
            {{ systemSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="editorOpen" class="mc-modal" @click.self="editorOpen = false">
      <div class="mc-modal__panel mc-modal__panel--wide">
        <div class="mc-modal__head">
          <h3>{{ editor.name ? '编辑自定义模板' : '新建自定义模板' }}</h3>
          <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="editorOpen = false">
            关闭
          </button>
        </div>
        <div class="mc-modal__body">
          <div v-if="editorError" class="mc-notice mc-notice--error">{{ editorError }}</div>
          <div class="mc-grid mc-grid--two">
            <label class="mc-field">
              <span>模板名称</span>
              <input v-model="editor.displayName" class="mc-input" placeholder="例如：评论通过通知" />
            </label>
            <label class="mc-field">
              <span>备注（可选）</span>
              <input v-model="editor.description" class="mc-input" placeholder="给自己看的说明" />
            </label>
          </div>
          <label class="mc-field">
            <span>邮件标题</span>
            <input
              v-model="editor.subject"
              class="mc-input"
              placeholder="例如：你在 [(${site.title})] 的评论通过了"
            />
            <small>支持 Thymeleaf 语法，例如 [(${subscriber.displayName})]、[(${site.title})]。</small>
          </label>
          <label class="mc-field">
            <span>HTML 正文</span>
            <textarea
              v-model="editor.content"
              class="mc-textarea"
              placeholder="<p>你好 [(${subscriber.displayName})]</p>"
            />
          </label>
          <label class="mc-field">
            <span>纯文本正文（可选）</span>
            <textarea
              v-model="editor.rawContent"
              class="mc-textarea"
              style="min-height: 80px"
              placeholder="留空时会自动从 HTML 正文提取"
            />
          </label>

          <div class="mc-card__title">
            <h3>可用变量</h3>
            <button class="mc-btn mc-btn--sm" type="button" @click="addVariable">添加变量</button>
          </div>
          <small class="mc-hint" style="display: block; margin-bottom: 10px">
            变量可以直接写在模板里，例如 [(${code})]。示例值只用于预览和语法检查。
          </small>
          <div v-if="editor.variables.length === 0" class="mc-empty mc-empty--sm">还没有变量</div>
          <div v-else class="mc-list mc-list--tight">
            <div
              v-for="(variable, index) in editor.variables"
              :key="index"
              class="mc-row"
              style="align-items: center"
            >
              <input
                v-model="variable.name"
                class="mc-input"
                style="flex: 1; min-width: 110px"
                placeholder="变量名"
              />
              <input
                v-model="variable.label"
                class="mc-input"
                style="flex: 1; min-width: 110px"
                placeholder="显示名称"
              />
              <input
                v-model="variable.sampleValue"
                class="mc-input"
                style="flex: 1.2; min-width: 120px"
                placeholder="示例值"
              />
              <label class="mc-check">
                <input v-model="variable.required" type="checkbox" />
                必填
              </label>
              <button
                class="mc-btn mc-btn--sm mc-btn--danger"
                type="button"
                @click="removeVariable(index)"
              >
                移除
              </button>
            </div>
          </div>

          <label class="mc-check" style="margin-top: 14px">
            <input v-model="editor.enabled" type="checkbox" />
            启用这个模板
          </label>
        </div>
        <div class="mc-modal__foot">
          <button class="mc-btn" type="button" @click="checkSyntax">语法检查</button>
          <button
            class="mc-btn"
            type="button"
            :disabled="!editor.name"
            :title="editor.name ? '' : '保存后才能预览'"
            @click="previewDraft"
          >
            预览已保存版本
          </button>
          <button
            class="mc-btn mc-btn--primary"
            type="button"
            :disabled="editorSaving"
            @click="saveEditor"
          >
            {{ editorSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="exportOpen" class="mc-modal" @click.self="exportOpen = false">
      <div class="mc-modal__panel">
        <div class="mc-modal__head">
          <h3>导出模板文件</h3>
          <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="exportOpen = false">
            关闭
          </button>
        </div>
        <div class="mc-modal__body">
          <div v-if="exportError" class="mc-notice mc-notice--error">{{ exportError }}</div>
          <div class="mc-notice">
            文件已经下载好了。里面分三段：<code>builtin</code> 是 Halo 自带的通知模板，
            <code>custom</code> 是本插件里的模板，<code>system</code> 是插件自带的模板。
            每段都带着自己可用的变量，可以直接整份交给编辑工具改样式，改完再从「导入模板文件」读回来。
          </div>
          <div v-if="exportLoading" class="mc-empty">正在生成…</div>
          <template v-else>
            <div class="mc-toolbar" style="margin-bottom: 8px">
              <button class="mc-btn mc-btn--sm" type="button" @click="copyExport">复制全部内容</button>
              <span class="mc-hint">只改 title / subject / htmlBody / rawBody，name 不要动。</span>
            </div>
            <pre class="mc-code" style="max-height: 320px; overflow: auto">{{ exportText }}</pre>
          </template>
        </div>
        <div class="mc-modal__foot">
          <button class="mc-btn" type="button" @click="exportOpen = false">完成</button>
        </div>
      </div>
    </div>

    <div v-if="importOpen" class="mc-modal" @click.self="closeImport">
      <div class="mc-modal__panel">
        <div class="mc-modal__head">
          <h3>{{ importResult?.dryRun ? '导入预览' : '导入结果' }}</h3>
          <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="closeImport">
            关闭
          </button>
        </div>
        <div class="mc-modal__body">
          <div v-if="importError" class="mc-notice mc-notice--error">{{ importError }}</div>
          <div v-if="importLoading" class="mc-empty">正在核对文件…</div>
          <template v-else-if="importResult">
            <div class="mc-notice" :class="importResult.dryRun ? '' : 'mc-notice--ok'">
              {{ importSummary }}
              <template v-if="importResult.dryRun">
                <br />
                现在还没有写入。确认无误再点「确认导入」，写入后可以逐条回退不了的，建议先备份一次。
              </template>
            </div>
            <div class="mc-hint" style="margin-bottom: 8px">文件名：{{ importFileName }}</div>
            <div class="mc-list mc-list--tight mc-scroll">
              <div v-for="(item, index) in importResult.items" :key="index" class="mc-item" style="padding: 8px 10px">
                <div class="mc-item__main">
                  <div class="mc-item__title" style="font-weight: 500">
                    <span class="mc-tag">{{ importSectionLabel(item.section) }}</span>
                    {{ item.displayName || item.name || '（没有名字）' }}
                    <span :class="importStatusClass(item.status)">{{ importStatusLabel(item.status) }}</span>
                  </div>
                  <div class="mc-item__meta">{{ item.message }}</div>
                </div>
              </div>
              <div v-if="importResult.items.length === 0" class="mc-empty mc-empty--sm">
                文件里没有可识别的模板
              </div>
            </div>
          </template>
          <div v-else class="mc-empty">还没有读到内容</div>
        </div>
        <div class="mc-modal__foot">
          <button class="mc-btn" type="button" @click="closeImport">取消</button>
          <button
            v-if="importResult?.dryRun"
            class="mc-btn mc-btn--primary"
            type="button"
            :disabled="!canConfirmImport"
            @click="confirmImport"
          >
            确认导入
          </button>
        </div>
      </div>
    </div>
  </div>
</template>