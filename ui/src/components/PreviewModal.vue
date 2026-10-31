<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TemplatePreview } from '../api'

const props = defineProps<{
  title: string
  preview: TemplatePreview | null
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{ (event: 'close'): void }>()

const showRaw = ref(false)

const frame = computed(() => {
  const html = props.preview?.html || ''
  return `<!DOCTYPE html><html><head><meta charset="utf-8" />
    <style>
      body { margin: 0; padding: 24px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; font-size: 14px; line-height: 1.75; color: #111827; }
      a { color: #1f9d94; }
      img { max-width: 100%; height: auto; }
      table { max-width: 100%; }
    </style></head><body>${html}</body></html>`
})

/** Opens the rendered mail in its own tab, for when the panel is still too narrow. */
function openInNewTab() {
  const blob = new Blob([frame.value], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  window.open(url, '_blank')
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
}
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
      <div class="mc-modal__body" style="display: flex; flex-direction: column; min-height: 0">
        <div v-if="loading" class="mc-empty">正在渲染…</div>
        <div v-else-if="error" class="mc-notice mc-notice--error">{{ error }}</div>
        <template v-else-if="preview">
          <template v-if="showRaw">
            <div class="mc-preview__subject" style="border: 1px solid var(--mc-border); border-radius: 8px 8px 0 0">
              <span>纯文本版本</span>
            </div>
            <pre class="mc-code" style="flex: 1; min-height: 0; margin: 0; overflow: auto; border-radius: 0 0 8px 8px">{{ preview.raw }}</pre>
          </template>
          <div v-else class="mc-preview mc-preview--fill">
            <div class="mc-preview__subject">
              <span>{{ preview.subject || '（无标题）' }}</span>
              <span class="mc-hint">这就是收件人看到的邮件</span>
            </div>
            <iframe class="mc-preview__frame mc-preview__frame--fill" :srcdoc="frame" sandbox="" />
          </div>
        </template>
        <div v-else class="mc-empty">没有可预览的内容</div>
      </div>
    </div>
  </div>
</template>