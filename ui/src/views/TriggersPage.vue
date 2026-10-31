<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  api,
  errorMessage,
  EVENT_TYPES,
  RECIPIENT_STRATEGIES,
  type BuiltinGroup,
  type ChannelView,
  type CustomTemplate,
  type Recipient,
  type RecipientDirectory,
  type SendTarget,
  type TriggerView,
} from '../api'

const triggers = ref<TriggerView[]>([])
const groups = ref<BuiltinGroup[]>([])
const custom = ref<CustomTemplate[]>([])
const channel = ref<ChannelView | null>(null)
const loading = ref(true)
const error = ref('')
const copied = ref(false)

interface EditorState {
  name: string
  displayName: string
  enabled: boolean
  eventType: string
  templateType: 'CUSTOM' | 'BUILTIN'
  templateName: string
  recipientStrategy: string
  condition: string
}

const editorOpen = ref(false)
const editorSaving = ref(false)
const editorError = ref('')
const editor = ref<EditorState>(emptyEditor())

const directory = ref<RecipientDirectory>({ users: [], visitors: [], totalUsers: 0, totalVisitors: 0 })
const directoryLoading = ref(false)
const pickerTab = ref<'users' | 'visitors' | 'manual'>('users')
const pickerKeyword = ref('')
const manualInput = ref('')
const recipients = ref<SendTarget[]>([])

/** Hidden templates and disabled custom templates never show up in the pickers. */
const selectableCustom = computed(() => custom.value.filter((item) => !item.hidden && item.enabled))
const selectableGroups = computed(() =>
  groups.value
    .map((group) => ({ ...group, templates: group.templates.filter((item) => !item.hidden) }))
    .filter((group) => group.templates.length > 0)
)

const visibleUsers = computed(() => filterDirectory(directory.value.users))
const visibleVisitors = computed(() => filterDirectory(directory.value.visitors))

function emptyEditor(): EditorState {
  return {
    name: '',
    displayName: '',
    enabled: true,
    eventType: 'COMMENT_CREATED',
    templateType: 'CUSTOM',
    templateName: '',
    recipientStrategy: 'EVENT_ACTOR',
    condition: '',
  }
}

function filterDirectory(list: Recipient[]): Recipient[] {
  const needle = pickerKeyword.value.trim().toLowerCase()
  if (!needle) {
    return list
  }
  return list.filter(
    (item) =>
      item.email.toLowerCase().includes(needle) ||
      (item.displayName || '').toLowerCase().includes(needle)
  )
}

function isPicked(email: string): boolean {
  return recipients.value.some((item) => item.email.toLowerCase() === email.toLowerCase())
}

function toggleRecipient(email: string, name?: string) {
  const index = recipients.value.findIndex((item) => item.email.toLowerCase() === email.toLowerCase())
  if (index >= 0) {
    recipients.value.splice(index, 1)
    return
  }
  recipients.value.push({ email, name: name || '' })
}

function removeRecipient(email: string) {
  recipients.value = recipients.value.filter(
    (item) => item.email.toLowerCase() !== email.toLowerCase()
  )
}

function addManual() {
  const parts = manualInput.value.split(/[\s,;，；]+/).map((item) => item.trim()).filter(Boolean)
  for (const part of parts) {
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(part)) {
      editorError.value = `「${part}」不像一个邮箱地址`
      return
    }
    if (!isPicked(part)) {
      recipients.value.push({ email: part, name: '' })
    }
  }
  manualInput.value = ''
  editorError.value = ''
}

const apiExample = computed(() => {
  const key = channel.value?.apiKey || '<在发信设置里生成>'
  const template = custom.value[0]?.name || '<自定义模板名>'
  return `curl -X POST 'https://你的域名/apis/mail.fufu520.cn/v1alpha1/trigger' \\\\
  -H 'Content-Type: application/json' \\\\
  -H 'X-Mail-Key: ${key}' \\\\
  -d '{
    "template": "${template}",
    "to": ["someone@example.com"],
    "variables": { "code": "123456" }
  }'`
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [ruleList, data, channelView] = await Promise.all([
      api.triggers(),
      api.templates(),
      api.channel(),
    ])
    triggers.value = ruleList
    groups.value = data.groups
    custom.value = data.custom
    channel.value = channelView
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function loadDirectory() {
  directoryLoading.value = true
  try {
    directory.value = await api.recipients('')
  } catch (e) {
    editorError.value = errorMessage(e)
  } finally {
    directoryLoading.value = false
  }
}

function openCreate() {
  editor.value = emptyEditor()
  if (selectableCustom.value.length) {
    editor.value.templateName = selectableCustom.value[0].name
  }
  recipients.value = []
  pickerTab.value = 'users'
  pickerKeyword.value = ''
  manualInput.value = ''
  editorError.value = ''
  editorOpen.value = true
  if (!directory.value.users.length && !directory.value.visitors.length) {
    void loadDirectory()
  }
}

function openEdit(trigger: TriggerView) {
  editor.value = {
    name: trigger.name,
    displayName: trigger.displayName,
    enabled: trigger.enabled,
    eventType: trigger.eventType || 'COMMENT_CREATED',
    templateType: (trigger.templateType as 'CUSTOM' | 'BUILTIN') || 'CUSTOM',
    templateName: trigger.templateName,
    recipientStrategy: trigger.recipientStrategy || 'EVENT_ACTOR',
    condition: trigger.condition || '',
  }
  const merged: SendTarget[] = []
  const seen = new Set<string>()
  for (const item of trigger.extraRecipients || []) {
    if (item && item.email && !seen.has(item.email.toLowerCase())) {
      seen.add(item.email.toLowerCase())
      merged.push({ email: item.email, name: item.name || '' })
    }
  }
  for (const email of trigger.extraEmails || []) {
    if (email && !seen.has(email.toLowerCase())) {
      seen.add(email.toLowerCase())
      merged.push({ email, name: '' })
    }
  }
  recipients.value = merged
  pickerTab.value = 'users'
  pickerKeyword.value = ''
  manualInput.value = ''
  editorError.value = ''
  editorOpen.value = true
  if (!directory.value.users.length && !directory.value.visitors.length) {
    void loadDirectory()
  }
}

async function saveEditor() {
  editorSaving.value = true
  editorError.value = ''
  const payload = {
    displayName: editor.value.displayName,
    enabled: editor.value.enabled,
    eventType: editor.value.eventType,
    templateType: editor.value.templateType,
    templateName: editor.value.templateName,
    recipientStrategy: editor.value.recipientStrategy,
    extraEmails: [],
    extraRecipients: recipients.value,
    condition: editor.value.condition,
  }
  try {
    if (editor.value.name) {
      await api.updateTrigger(editor.value.name, payload)
    } else {
      await api.createTrigger(payload)
    }
    editorOpen.value = false
    await load()
  } catch (e) {
    editorError.value = errorMessage(e)
  } finally {
    editorSaving.value = false
  }
}

async function toggleEnabled(trigger: TriggerView) {
  try {
    await api.updateTrigger(trigger.name, {
      displayName: trigger.displayName,
      enabled: !trigger.enabled,
      eventType: trigger.eventType,
      templateType: trigger.templateType,
      templateName: trigger.templateName,
      recipientStrategy: trigger.recipientStrategy,
      extraEmails: trigger.extraEmails,
      extraRecipients: trigger.extraRecipients,
      condition: trigger.condition,
    })
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function remove(trigger: TriggerView) {
  if (!window.confirm(`确定删除规则「${trigger.displayName}」吗？`)) {
    return
  }
  try {
    await api.deleteTrigger(trigger.name)
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function toggleApi() {
  if (!channel.value) {
    return
  }
  try {
    channel.value = await api.saveChannel({
      source: channel.value.source,
      notifierDescriptor: channel.value.notifierDescriptor,
      host: channel.value.host,
      port: channel.value.port,
      username: channel.value.username,
      sender: channel.value.sender,
      displayName: channel.value.displayName,
      encryption: channel.value.encryption,
      apiEnabled: !channel.value.apiEnabled,
    })
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function copyExample() {
  try {
    await navigator.clipboard.writeText(apiExample.value)
    copied.value = true
    window.setTimeout(() => (copied.value = false), 1600)
  } catch {
    copied.value = false
  }
}

function eventLabel(value?: string) {
  return EVENT_TYPES.find((item) => item.value === value)?.label || value || '—'
}

function strategyLabel(value?: string) {
  return RECIPIENT_STRATEGIES.find((item) => item.value === value)?.label || value || '—'
}

function extraSummary(trigger: TriggerView): string {
  const list = (trigger.extraRecipients || []).map((item) => item.email)
  const legacy = (trigger.extraEmails || []).filter((email) => !list.includes(email))
  return [...list, ...legacy].join('、')
}

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>触发规则</h2>
        <p>让站点自己发信：评论来了、有人注册、文章发布了，就按规则把邮件发出去。</p>
      </div>
      <div class="mc-toolbar">
        <button class="mc-btn" type="button" @click="load">刷新</button>
        <button class="mc-btn mc-btn--primary" type="button" @click="openCreate">新建规则</button>
      </div>
    </div>

    <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>

    <div class="mc-card">
      <div class="mc-card__title">
        <h3>给代码留的触发接口</h3>
        <button class="mc-btn mc-btn--sm" type="button" @click="toggleApi">
          {{ channel?.apiEnabled ? '停用接口' : '启用接口' }}
        </button>
      </div>
      <div :class="['mc-notice', channel?.apiEnabled ? '' : 'mc-notice--warn']" style="margin-bottom: 12px">
        {{
          channel?.apiEnabled
            ? '接口已启用。任何拿到下面这串密钥的代码，都可以调用它发信。'
            : '接口当前关闭，外部代码调用会被拒绝。'
        }}
      </div>
      <div class="mc-field">
        <span>触发密钥</span>
        <div class="mc-row">
          <input class="mc-input" style="flex: 1" readonly :value="channel?.apiKey || ''" />
          <button class="mc-btn" type="button" @click="copyExample">
            {{ copied ? '已复制示例' : '复制调用示例' }}
          </button>
        </div>
        <small>密钥在「发信设置」里可以重新生成，重新生成后旧密钥立即失效。</small>
      </div>
      <pre class="mc-code">{{ apiExample }}</pre>
      <small class="mc-hint" style="display: block; margin-top: 8px">
        想直接调用 Halo 自带的通知模板，把 template 写成 builtin:模板名 即可，例如
        builtin:template-new-comment-on-post。
      </small>
    </div>

    <div class="mc-card">
      <div class="mc-card__title">
        <h3>规则列表</h3>
        <span>共 {{ triggers.length }} 条</span>
      </div>
      <div v-if="loading" class="mc-empty">正在读取规则…</div>
      <div v-else-if="triggers.length === 0" class="mc-empty">
        还没有触发规则。新建一条，比如「收到新评论就通知作者」。
      </div>
      <div v-else class="mc-list mc-list--tight">
        <div v-for="trigger in triggers" :key="trigger.name" class="mc-item">
          <div class="mc-item__main">
            <div class="mc-item__title">
              <span>{{ trigger.displayName }}</span>
              <span :class="['mc-tag', trigger.enabled ? 'mc-tag--primary' : 'mc-tag--warn']">
                {{ trigger.enabled ? '运行中' : '已停用' }}
              </span>
            </div>
            <div class="mc-item__meta">
              事件：{{ eventLabel(trigger.eventType) }} · 模板：{{
                trigger.templateDisplayName || trigger.templateName
              }}
            </div>
            <div class="mc-item__meta">
              收件人：{{ strategyLabel(trigger.recipientStrategy) }}
              <template v-if="extraSummary(trigger)"> · 另发 {{ extraSummary(trigger) }}</template>
            </div>
            <div class="mc-item__meta">
              已发 {{ trigger.sent || 0 }} · 失败 {{ trigger.failed || 0 }}
              <template v-if="trigger.lastMessage"> · {{ trigger.lastMessage }}</template>
            </div>
          </div>
          <div class="mc-item__actions">
            <button class="mc-btn mc-btn--sm" type="button" @click="toggleEnabled(trigger)">
              {{ trigger.enabled ? '停用' : '启用' }}
            </button>
            <button class="mc-btn mc-btn--sm" type="button" @click="openEdit(trigger)">编辑</button>
            <button class="mc-btn mc-btn--sm mc-btn--danger" type="button" @click="remove(trigger)">
              删除
            </button>
          </div>
        </div>
      </div>
    </div>

    <div v-if="editorOpen" class="mc-modal" @click.self="editorOpen = false">
      <div class="mc-modal__panel mc-modal__panel--wide">
        <div class="mc-modal__head">
          <h3>{{ editor.name ? '编辑触发规则' : '新建触发规则' }}</h3>
          <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="editorOpen = false">
            关闭
          </button>
        </div>
        <div class="mc-modal__body">
          <div v-if="editorError" class="mc-notice mc-notice--error">{{ editorError }}</div>

          <div class="mc-section-title" style="margin-top: 0">1. 规则与模板</div>
          <label class="mc-field">
            <span>规则名称</span>
            <input v-model="editor.displayName" class="mc-input" placeholder="例如：评论通过后通知作者" />
          </label>
          <div class="mc-grid mc-grid--two">
            <label class="mc-field">
              <span>触发事件</span>
              <select v-model="editor.eventType" class="mc-select">
                <option v-for="item in EVENT_TYPES" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </label>
            <label class="mc-field">
              <span>模板来源</span>
              <select v-model="editor.templateType" class="mc-select">
                <option value="CUSTOM">自定义模板</option>
                <option value="BUILTIN">站内模板（Halo 通知）</option>
              </select>
            </label>
          </div>
          <label class="mc-field">
            <span>模板</span>
            <select v-model="editor.templateName" class="mc-select">
              <template v-if="editor.templateType === 'CUSTOM'">
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
                  <option v-for="item in group.templates" :key="item.name" :value="item.name">
                    {{ item.title || item.name }}
                  </option>
                </optgroup>
              </template>
            </select>
          </label>

          <div class="mc-section-title">2. 收件人</div>
          <label class="mc-field">
            <span>主要收件人</span>
            <select v-model="editor.recipientStrategy" class="mc-select">
              <option v-for="item in RECIPIENT_STRATEGIES" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
            <small>选「只发给下面填写的固定邮箱」时，事件当事人不会收到。</small>
          </label>

          <div class="mc-hint" style="margin-bottom: 6px">
            还可以再固定加几个人，事件发生时他们都会收到一封。
          </div>
          <div class="mc-tabs">
            <button
              type="button"
              :class="{ 'is-active': pickerTab === 'users' }"
              @click="pickerTab = 'users'"
            >
              站内用户（{{ directory.totalUsers }}）
            </button>
            <button
              type="button"
              :class="{ 'is-active': pickerTab === 'visitors' }"
              @click="pickerTab = 'visitors'"
            >
              留言过的访客（{{ directory.totalVisitors }}）
            </button>
            <button
              type="button"
              :class="{ 'is-active': pickerTab === 'manual' }"
              @click="pickerTab = 'manual'"
            >
              手动输入
            </button>
          </div>

          <template v-if="pickerTab !== 'manual'">
            <input
              v-model="pickerKeyword"
              class="mc-input"
              style="margin-bottom: 8px"
              placeholder="按邮箱或名字筛选"
            />
            <div v-if="directoryLoading" class="mc-empty mc-empty--sm">正在读取名单…</div>
            <div v-else class="mc-list mc-list--tight mc-scroll" style="max-height: 220px">
              <div
                v-for="item in pickerTab === 'users' ? visibleUsers : visibleVisitors"
                :key="`${pickerTab}-${item.email}`"
                class="mc-item mc-item--clickable"
                :class="{ 'mc-item--active': isPicked(item.email) }"
                style="padding: 7px 10px"
                @click="toggleRecipient(item.email, item.displayName)"
              >
                <div class="mc-item__main">
                  <div class="mc-item__title" style="font-weight: 500">
                    <input
                      type="checkbox"
                      :checked="isPicked(item.email)"
                      @click.stop="toggleRecipient(item.email, item.displayName)"
                    />
                    {{ item.displayName || item.email }}
                  </div>
                  <div class="mc-item__meta">{{ item.email }}</div>
                </div>
              </div>
              <div
                v-if="(pickerTab === 'users' ? visibleUsers : visibleVisitors).length === 0"
                class="mc-empty mc-empty--sm"
              >
                没有匹配的人
              </div>
            </div>
          </template>

          <template v-else>
            <div class="mc-row">
              <input
                v-model="manualInput"
                class="mc-input"
                style="flex: 1"
                placeholder="例如 someone@example.com，多个用逗号或空格隔开"
                @keyup.enter="addManual"
              />
              <button class="mc-btn" type="button" @click="addManual">添加</button>
            </div>
          </template>

          <div v-if="recipients.length" class="mc-chips">
            <span v-for="item in recipients" :key="item.email" class="mc-chip">
              {{ item.name ? `${item.name} · ${item.email}` : item.email }}
              <button type="button" title="移除" @click="removeRecipient(item.email)">×</button>
            </span>
          </div>
          <div v-else class="mc-hint" style="margin-top: 8px">还没有额外收件人</div>

          <div class="mc-section-title">3. 其他</div>
          <label class="mc-field">
            <span>触发条件（可选，SpEL 表达式）</span>
            <input
              v-model="editor.condition"
              class="mc-input"
              placeholder="例如：#content.contains('价格') 或 #commenter == '某某'"
            />
            <small>
              留空表示事件一到就发。可用变量名就是模板变量名，写的时候前面加 #，例如
              #commenter、#content、#postTitle。条件算不出来时会当作不匹配，跳过这一次。
            </small>
          </label>
          <label class="mc-check">
            <input v-model="editor.enabled" type="checkbox" />
            启用这条规则
          </label>
          <small class="mc-hint" style="display: block; margin-top: 8px">
            规则只对启用之后产生的内容生效，不会把历史评论、历史用户补发一遍。
          </small>
        </div>
        <div class="mc-modal__foot">
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