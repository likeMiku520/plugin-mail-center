<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api, errorMessage, type TemplatePreview, type TemplateVariable } from '../api'

const props = defineProps<{
  title: string
  templateType: string
  templateName: string
  variables?: TemplateVariable[]
  initialValues?: Record<string, string>
}>()

const emit = defineEmits<{ (event: 'close'): void }>()

const preview = ref<TemplatePreview | null>(null)
const loading = ref(true)
const error = ref('')
const showRaw = ref(false)
const recipientName = ref('示例访客')
const recipientEmail = ref('visitor@example.com')
const values = reactive<Record<string, string>>({})

let timer: number | undefined

const declared = computed(() => props.variables || [])

const frame = computed(() => {
  const html = preview.value?.html || ''
  return `<!DOCTYPE html><html><head><meta charset="utf-8" />
    <style>
      body { margin: 0; padding: 24px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; font-size: 14px; line-height: 1.75; color: #111827; }
      a { color: #1f9d94; }
      img { max-width: 100%; height: auto; }
      table { max-width: 100%; }
    </style></head><body>${html}</body></html>`
})

function seed() {
  for (const key of Object.keys(values)) {
    delete values[key]
  }
  for (const variable of declared.value) {
    values[variable.name] =
      props.initialValues?.[variable.name] ?? variable.sampleValue ?? ''
  }
  for (const [key, value] of Object.entries(props.initialValues || {})) {
    if (!(key in values)) {
      values[key] = value
    }
  }
}

async function render() {
  loading.value = true
  error.value = ''
  try {
    preview.value = await api.preview({
      templateType: props.templateType,
      templateName: props.templateName,
      variables: { ...values },
      recipientEmail: recipientEmail.value,
      recipientName: recipientName.value,
    })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function schedule() {
  window.clearTimeout(timer)
  timer = window.setTimeout(render, 400)
}

function resetValues() {
  seed()
  void render()
}

/** Opens the rendered mail in its own tab, for when the panel is still too narrow. */
function openInNewTab() {
  const blob = new Blob([frame.value], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank')
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

watch(values, schedule, { deep: true })
watch([recipientName, recipientEmail], schedule)

onMounted(() => {
  seed()
  void render()
})

onBeforeUnmount(() => window.clearTimeout(timer))
</script>

<template>
  <div class="mc-modal" @click.self="emit('close')">
    <div class="mc-modal__panel mc-modal__panel--tall">
      <div class="mc-modal__head">
        <h3>{{ title }}</h3>
        <div class="mc-toolbar">
          <button class="mc-btn mc-btn--sm" type="button" @click="showRaw = !showRaw">
            {{ showRaw ? '看邮件样式' : '看纯文本' }}
          </button>
          <button class="mc-btn mc-btn--sm" type="button" @click="openInNewTab">在新窗口打开</button>
          <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="emit('close')">
            关闭
          </button>
        </div>
      </div>
      <div class="mc-modal__body" style="display: flex; flex-direction: column; min-height: 0; gap: 12px">
        <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>
        <template v-if="showRaw">
          <div class="mc-preview__subject" style="border: 1px solid var(--mc-border); border-radius: 8px 8px 0 0">
            <span>纯文本版本</span>
          </div>
          <pre class="mc-code" style="flex: 1; min-height: 0; margin: 0; overflow: auto; border-radius: 0 0 8px 8px">{{ preview?.raw }}</pre>
        </template>
        <div v-else class="mc-preview mc-preview--fill">
          <div class="mc-preview__subject">
            <span>{{ preview?.subject || '（无标题）' }}</span>
            <span v-if="loading" class="mc-hint">正在渲染…</span>
          </div>
          <iframe class="mc-preview__frame mc-preview__frame--fill" :srcdoc="frame" sandbox="" />
        </div>

        <div class="mc-vars-panel">
          <div class="mc-vars-panel__head">
            <span>预览用的变量</span>
            <div class="mc-toolbar">
              <button class="mc-btn mc-btn--sm" type="button" @click="resetValues">填回示例值</button>
            </div>
          </div>
          <div class="mc-hint" style="margin-bottom: 8px">
            改这里的内容，上面的预览会跟着重新渲染。这些值只用于预览，不会保存。
          </div>
          <div class="mc-vars-grid">
            <label class="mc-field" style="margin: 0">
              <span>收件人显示名</span>
              <input v-model="recipientName" class="mc-input" placeholder="示例访客" />
            </label>
            <label class="mc-field" style="margin: 0">
              <span>收件人邮箱</span>
              <input v-model="recipientEmail" class="mc-input" placeholder="visitor@example.com" />
            </label>
            <label v-for="variable in declared" :key="variable.name" class="mc-field" style="margin: 0">
              <span>
                {{ variable.name }}
                <span v-if="variable.required" class="mc-tag">必填</span>
              </span>
              <input
                v-model="values[variable.name]"
                class="mc-input"
                :placeholder="variable.sampleValue || '留空则显示为空'"
              />
              <small v-if="variable.label && variable.label !== variable.name">
                {{ variable.label }}
              </small>
            </label>
          </div>
          <div v-if="declared.length === 0" class="mc-hint" style="padding: 4px 0">
            这个模板没有声明变量，只用到收件人和站点信息。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>