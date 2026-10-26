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
const tab = ref<'builtin' | 'custom'>('builtin')
const keyword = ref('')

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

const filteredGroups = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) {
    return groups.value
  }
  return groups.value
    .map((group) => ({
      ...group,
      templates: group.templates.filter(
        (template) =>
          (template.title || '').toLowerCase().includes(needle) ||
          (group.displayName || '').toLowerCase().includes(needle) ||
          (group.reasonType || '').toLowerCase().includes(needle)
      ),
    }))
    .filter(
      (group) =>
        group.templates.length > 0 ||
        (group.displayName || '').toLowerCase().includes(needle) ||
        (group.reasonType || '').toLowerCase().includes(needle)
    )
})

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
    await load()
  } catch (e) {
    editorError.value = errorMessage(e)
  } finally {
    editorSaving.value = false
  }
}

async function checkSyntax() {
  editorError.value = ''
  try {
    const response = await api.validateTemplate({
      subject: editor.value.subject,
      content: editor.value.content,
      rawContent: editor.value.rawContent,
      variables: editor.value.variables.filter((variable) => variable.name.trim().length > 0),
    })
    editorError.value = response.ok ? '' : response.message
    if (response.ok) {
      window.alert('模板语法检查通过')
    }
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

async function remove(template: CustomTemplate) {
  if (!window.confirm(`确定删除模板「${template.displayName}」吗？`)) {
    return
  }
  try {
    await api.deleteTemplate(template.name)
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>邮件中心</h2>
        <p>站内所有能发出去的邮件模板都在这里，可以直接拿来发信，也可以另存一份改成自己的。</p>
      </div>
      <div class="mc-row">
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
    <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>

    <div class="mc-tabs">
      <button
        type="button"
        :class="{ 'is-active': tab === 'builtin' }"
        @click="tab = 'builtin'"
      >
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
                <span class="mc-tag mc-tag--primary">{{ group.templates.length }} 个模板</span>
                <span v-if="group.variables.length" class="mc-tag">
                  {{ group.variables.length }} 个可用变量
                </span>
              </div>
              <div class="mc-item__meta">
                {{ group.reasonType }}
                <template v-if="group.description"> · {{ group.description }}</template>
              </div>
              <div v-if="group.templates.length" style="margin-top: 10px" class="mc-list">
                <div
                  v-for="template in group.templates"
                  :key="template.name"
                  class="mc-item"
                  style="padding: 8px 10px"
                >
                  <div class="mc-item__main">
                    <div class="mc-item__title" style="font-weight: 500">
                      {{ template.title || '（模板标题为空）' }}
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
                      @click="cloneBuiltin(template, group)"
                    >
                      复制为自定义
                    </button>
                  </div>
                </div>
              </div>
              <div v-else class="mc-item__meta" style="margin-top: 8px">
                这一类通知暂时没有模板，Halo 不会给它发邮件。
              </div>
            </div>
          </div>
        </div>
      </template>

      <template v-else>
        <div v-if="custom.length === 0" class="mc-empty">
          还没有自定义模板。点右上角「新建自定义模板」，或者从站内模板复制一份来改。
        </div>
        <div v-else class="mc-list">
          <div v-for="template in custom" :key="template.name" class="mc-item">
            <div class="mc-item__main">
              <div class="mc-item__title">
                <span>{{ template.displayName }}</span>
                <span v-if="!template.enabled" class="mc-tag mc-tag--warn">已停用</span>
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