<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  api,
  errorMessage,
  type BuiltinGroup,
  type CustomTemplate,
  type Recipient,
  type SendSummary,
  type TemplatePreview,
  type TemplateVariable,
} from '../api'
import PreviewModal from '../components/PreviewModal.vue'

const groups = ref<BuiltinGroup[]>([])
const custom = ref<CustomTemplate[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')

const templateType = ref<'CUSTOM' | 'BUILTIN' | ''>('')
const templateName = ref('')

watch(templateType, () => {
  templateName.value = ''
})
const subjectOverride = ref('')
const values = ref<Record<string, string>>({})

const tab = ref<'users' | 'visitors' | 'manual'>('users')
const keyword = ref('')
const users = ref<Recipient[]>([])
const visitors = ref<Recipient[]>([])
const selected = ref<Recipient[]>([])
const manualInput = ref('')

const sending = ref(false)
const summary = ref<SendSummary | null>(null)

const preview = ref<TemplatePreview | null>(null)
const previewOpen = ref(false)
const previewLoading = ref(false)
const previewError = ref('')
const previewTitle = ref('')

const variables = computed<TemplateVariable[]>(() => {
  if (templateType.value === 'CUSTOM') {
    return custom.value.find((item) => item.name === templateName.value)?.variables || []
  }
  for (const group of groups.value) {
    const found = group.templates.find((item) => item.name === templateName.value)
    if (found) {
      return group.variables || []
    }
  }
  return []
})

const visibleUsers = computed(() => filterList(users.value))
const visibleVisitors = computed(() => filterList(visitors.value))

/** Hidden templates and disabled custom templates never show up in the pickers. */
const selectableCustom = computed(() => custom.value.filter((item) => !item.hidden && item.enabled))
const selectableGroups = computed(() =>
  groups.value
    .map((group) => ({ ...group, templates: group.templates.filter((item) => !item.hidden) }))
    .filter((group) => group.templates.length > 0)
)

function filterList(list: Recipient[]): Recipient[] {
  const needle = keyword.value.trim().toLowerCase()
  if (!needle) {
    return list
  }
  return list.filter(
    (item) =>
      item.email.toLowerCase().includes(needle) ||
      (item.displayName || '').toLowerCase().includes(needle)
  )
}

function isSelected(email: string): boolean {
  return selected.value.some((item) => item.email === email)
}

function toggle(recipient: Recipient) {
  if (isSelected(recipient.email)) {
    selected.value = selected.value.filter((item) => item.email !== recipient.email)
  } else {
    selected.value = [...selected.value, recipient]
  }
}

function removeSelected(email: string) {
  selected.value = selected.value.filter((item) => item.email !== email)
}

function addManual() {
  const parts = manualInput.value
    .split(/[\s,;，；]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  const added: Recipient[] = []
  for (const email of parts) {
    if (!email.includes('@') || isSelected(email)) {
      continue
    }
    added.push({ email, displayName: '', avatar: '', kind: 'manual', verified: false, lastSeenAt: '' })
  }
  if (added.length) {
    selected.value = [...selected.value, ...added]
  }
  manualInput.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [data, stats, directory] = await Promise.all([
      api.templates(),
      api.overview(),
      api.recipients(''),
    ])
    groups.value = data.groups
    custom.value = data.custom
    users.value = directory.users
    visitors.value = directory.visitors
    notice.value = stats.channelUsable ? '' : stats.channelProblem || ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

watch([templateType, templateName], () => {
  const next: Record<string, string> = {}
  for (const variable of variables.value) {
    next[variable.name] = values.value[variable.name] ?? ''
  }
  values.value = next
  summary.value = null
})

async function reloadRecipients() {
  try {
    const directory = await api.recipients(keyword.value)
    users.value = directory.users
    visitors.value = directory.visitors
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function openPreview() {
  previewOpen.value = true
  previewLoading.value = true
  previewError.value = ''
  preview.value = null
  previewTitle.value = '邮件预览'
  try {
    preview.value = await api.preview({
      templateType: templateType.value,
      templateName: templateName.value,
      variables: values.value,
    })
  } catch (e) {
    previewError.value = errorMessage(e)
  } finally {
    previewLoading.value = false
  }
}

async function send() {
  if (!templateType.value) {
    error.value = '请先选择模板来源'
    return
  }
  if (!templateName.value) {
    error.value = '请先选择邮件模板'
    return
  }
  if (selected.value.length === 0) {
    error.value = '请至少选择一个收件人'
    return
  }
  sending.value = true
  error.value = ''
  summary.value = null
  try {
    summary.value = await api.send({
      templateType: templateType.value,
      templateName: templateName.value,
      targets: selected.value.map((item) => ({ email: item.email, name: item.displayName || '' })),
      variables: values.value,
      subjectOverride: subjectOverride.value,
    })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    sending.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>写邮件</h2>
        <p>挑一个模板，选好收件人，补上变量就能发出去。收件人可以直接手打邮箱地址。</p>
      </div>
      <div class="mc-row">
        <button class="mc-btn" type="button" :disabled="!templateName" @click="openPreview">
          预览
        </button>
        <button
          class="mc-btn mc-btn--primary"
          type="button"
          :disabled="sending || selected.length === 0"
          @click="send"
        >
          {{ sending ? '发送中…' : `发送给 ${selected.length} 人` }}
        </button>
      </div>
    </div>

    <div v-if="notice" class="mc-notice mc-notice--warn">发信通道还没就绪：{{ notice }}</div>
    <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>

    <div v-if="summary" class="mc-card">
      <div class="mc-card__title">
        <h3>发送结果</h3>
        <span>成功 {{ summary.success }} / 共 {{ summary.total }}</span>
      </div>
      <div class="mc-list">
        <div v-for="result in summary.results" :key="result.email" class="mc-item">
          <div class="mc-item__main">
            <div class="mc-item__title">
              <span>{{ result.email }}</span>
              <span :class="['mc-tag', result.success ? 'mc-tag--primary' : 'mc-tag--danger']">
                {{ result.success ? '已发送' : '失败' }}
              </span>
            </div>
            <div v-if="result.error" class="mc-item__meta">{{ result.error }}</div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="loading" class="mc-empty">正在读取模板…</div>

    <template v-else>
      <div class="mc-card">
        <div class="mc-card__title"><h3>1. 选择邮件模板</h3></div>
        <div class="mc-grid mc-grid--two">
          <label class="mc-field">
            <span>模板来源</span>
            <select v-model="templateType" class="mc-select">
              <option value="">请选择</option>
              <option value="CUSTOM">自定义模板（{{ selectableCustom.length }}）</option>
              <option value="BUILTIN">站内模板（Halo 通知）</option>
            </select>
          </label>
          <label class="mc-field">
            <span>模板</span>
            <select v-model="templateName" class="mc-select">
              <option v-if="!templateType" value="">请先选择模板来源</option>
              <template v-else-if="templateType === 'CUSTOM'">
                <option v-if="selectableCustom.length === 0" value="">还没有可用的自定义模板</option>
                <option v-for="item in selectableCustom" :key="item.name" :value="item.name">
                  {{ item.displayName }}
                </option>
              </template>
              <template v-else>
                <optgroup
                  v-for="group in selectableGroups"
                  :key="group.reasonType"
                  :label="group.displayName"
                >
                  <option
                    v-for="item in group.templates"
                    :key="item.name"
                    :value="item.name"
                  >
                    {{ item.title || item.name }}
                  </option>
                </optgroup>
              </template>
            </select>
          </label>
        </div>
        <label class="mc-field" style="margin-bottom: 0">
          <span>临时改写邮件标题（可选）</span>
          <input
            v-model="subjectOverride"
            class="mc-input"
            placeholder="留空就用模板自己的标题"
          />
        </label>
      </div>

      <div class="mc-card">
        <div class="mc-card__title">
          <h3>2. 选择收件人</h3>
          <span>已选 {{ selected.length }} 人</span>
        </div>

        <div v-if="selected.length" class="mc-row" style="margin-bottom: 12px">
          <span v-for="item in selected" :key="item.email" class="mc-tag mc-tag--primary">
            {{ item.displayName || item.email }}
            <button
              type="button"
              style="
                margin-left: 6px;
                color: inherit;
                background: none;
                border: 0;
                cursor: pointer;
              "
              @click="removeSelected(item.email)"
            >
              ×
            </button>
          </span>
        </div>

        <div class="mc-tabs">
          <button type="button" :class="{ 'is-active': tab === 'users' }" @click="tab = 'users'">
            站内用户（{{ users.length }}）
          </button>
          <button
            type="button"
            :class="{ 'is-active': tab === 'visitors' }"
            @click="tab = 'visitors'"
          >
            访客（{{ visitors.length }}）
          </button>
          <button type="button" :class="{ 'is-active': tab === 'manual' }" @click="tab = 'manual'">
            手动输入邮箱
          </button>
        </div>

        <template v-if="tab === 'manual'">
          <label class="mc-field">
            <span>邮箱地址</span>
            <textarea
              v-model="manualInput"
              class="mc-textarea"
              style="min-height: 80px"
              placeholder="每行一个，也可以用逗号分隔，例如：&#10;someone@example.com&#10;other@example.com"
            />
            <small>支持一次填多个地址，回车、逗号、分号都能分隔。</small>
          </label>
          <button class="mc-btn mc-btn--primary" type="button" @click="addManual">
            加入收件人
          </button>
        </template>

        <template v-else>
          <div class="mc-row" style="margin-bottom: 10px">
            <input
              v-model="keyword"
              class="mc-input"
              style="flex: 1"
              placeholder="按邮箱或昵称筛选"
              @keyup.enter="reloadRecipients"
            />
            <button class="mc-btn" type="button" @click="reloadRecipients">搜索</button>
          </div>
          <div class="mc-scroll mc-list">
            <div
              v-for="item in tab === 'users' ? visibleUsers : visibleVisitors"
              :key="item.email"
              :class="['mc-item', { 'mc-item--active': isSelected(item.email) }]"
              style="cursor: pointer"
              @click="toggle(item)"
            >
              <div class="mc-item__main">
                <div class="mc-item__title">
                  <span>{{ item.displayName || item.email }}</span>
                  <span v-if="item.verified" class="mc-tag mc-tag--primary">已验证</span>
                </div>
                <div class="mc-item__meta">{{ item.email }}</div>
              </div>
              <div class="mc-item__actions">
                <span class="mc-tag">{{ isSelected(item.email) ? '已选' : '选择' }}</span>
              </div>
            </div>
            <div
              v-if="(tab === 'users' ? visibleUsers : visibleVisitors).length === 0"
              class="mc-empty"
            >
              没有找到邮箱地址
            </div>
          </div>
        </template>
      </div>

      <div class="mc-card">
        <div class="mc-card__title">
          <h3>3. 填写模板变量</h3>
          <span>{{ variables.length }} 个变量</span>
        </div>
        <div v-if="variables.length === 0" class="mc-empty">
          这个模板没有额外变量，收件人昵称和站点信息会自动带上。
        </div>
        <div v-else class="mc-grid mc-grid--two">
          <label v-for="variable in variables" :key="variable.name" class="mc-field">
            <span>
              {{ variable.label || variable.name }}
              <span v-if="variable.required" style="color: var(--mc-danger)">*</span>
            </span>
            <input
              v-model="values[variable.name]"
              class="mc-input"
              :placeholder="variable.sampleValue || ''"
            />
          </label>
        </div>
      </div>
    </template>

    <PreviewModal
      v-if="previewOpen"
      :title="previewTitle"
      :preview="preview"
      :loading="previewLoading"
      :error="previewError"
      @close="previewOpen = false"
    />
  </div>
</template>