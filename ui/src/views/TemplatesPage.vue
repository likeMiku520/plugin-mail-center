<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  api,
  errorMessage,
  type BuiltinGroup,
  type BuiltinTemplate,
  type CustomTemplate,
  type Overview,
  type TemplatePreview,
  type TemplateVariable,
} from '../api'
import PreviewModal from '../components/PreviewModal.vue'

const overview = ref<Overview | null>(null)
const groups = ref<BuiltinGroup[]>([])
const custom = ref<CustomTemplate[]>([])
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
  title: string
  htmlBody: string
  rawBody: string
  edited: boolean
}

const builtinOpen = ref(false)
const builtinSaving = ref(false)
const builtinError = ref('')
const builtinEditor = ref<BuiltinEditorState>(emptyBuiltinEditor())

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
    title: '',
    htmlBody: '',
    rawBody: '',
    edited: false,
  }
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [data, stats] = await Promise.all([api.templates(), api.overview()])
    groups.value = data.groups
    custom.value = data.custom
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
    title: template.title || '',
    htmlBody: template.htmlBody || '',
    rawBody: template.rawBody || '',
    edited: template.edited,
  }
  builtinError.value = ''
  builtinOpen.value = true
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

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>邮件中心</h2>
        <p>站内所有能发出去的邮件模板都在这里，可以直接拿来发信，也可以改成自己的样子。</p>
      </div>
      <div class="mc-row">
        <label class="mc-check">
          <input v-model="showHidden" type="checkbox" />
          显示已隐藏{{ hiddenCount ? `（${hiddenCount}）` : '' }}
        </label>
        <button class="mc-btn" type="button" @click="load">刷新</button>
        <button class="mc-btn mc-btn--primary" type="button" @click="openCreate">
          新建自定义模板
        </button>
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
      检测到「链接管理」插件：友链审核通过或拒绝后会自动给申请人回信，两个模板在自定义模板里。
    </div>
    <div v-if="message" class="mc-notice">{{ message }}</div>
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
        <div v-if="filteredGroups.length === 0" class="mc-empty">没有匹配的模板</div>
        <div v-else class="mc-list">
          <div v-for="group in filteredGroups" :key="group.reasonType" class="mc-item">
            <div class="mc-item__main">
              <div class="mc-item__title">
                <span>{{ group.displayName }}</span>
                <span v-if="group.hidden" class="mc-tag">系统隐藏</span>
                <span class="mc-tag mc-tag--primary">{{ visibleTemplates(group).length }} 个模板</span>
                <span v-if="group.variables.length" class="mc-tag">
                  {{ group.variables.length }} 个可用变量
                </span>
              </div>
              <div class="mc-item__meta">
                {{ group.reasonType }}
                <template v-if="group.description"> · {{ group.description }}</template>
              </div>
              <div style="margin-top: 10px" class="mc-list">
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
                <div
                  v-if="visibleTemplates(group).length === 0"
                  class="mc-item__meta"
                  style="margin-top: 8px"
                >
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
      <div class="mc-modal__panel">
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
          <div class="mc-notice" style="margin-bottom: 14px">
            这是 Halo 自带的通知模板。保存后会标记为「已自定义」，Halo 升级时不再覆盖它；点重置可以拿回自带的内容。
          </div>
          <label class="mc-field">
            <span>邮件标题</span>
            <input v-model="builtinEditor.title" class="mc-input" />
          </label>
          <label class="mc-field">
            <span>HTML 正文</span>
            <textarea
              v-model="builtinEditor.htmlBody"
              class="mc-textarea"
              style="min-height: 180px"
            />
          </label>
          <label class="mc-field">
            <span>纯文本正文（可选）</span>
            <textarea
              v-model="builtinEditor.rawBody"
              class="mc-textarea"
              style="min-height: 100px"
            />
          </label>
          <small style="color: var(--mc-text-faint)">
            可用变量由通知类型决定，例如 [(${commenter})]、[(${postTitle})]；收件人和站点信息用
            [(${subscriber.displayName})]、[(${site.title})]。
          </small>
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

    <div v-if="editorOpen" class="mc-modal" @click.self="editorOpen = false">
      <div class="mc-modal__panel">
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
          <small style="display: block; margin-bottom: 10px; color: var(--mc-text-faint)">
            变量可以直接写在模板里，例如 [(${code})]。示例值只用于预览和语法检查。
          </small>
          <div v-if="editor.variables.length === 0" class="mc-empty">还没有变量</div>
          <div v-else class="mc-list">
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
  </div>
</template>