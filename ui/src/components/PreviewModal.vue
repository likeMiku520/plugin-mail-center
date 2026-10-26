<script setup lang="ts">
import { computed } from 'vue'
import type { TemplatePreview } from '../api'

const props = defineProps<{
  title: string
  preview: TemplatePreview | null
  loading?: boolean
  error?: string
}>()

const emit = defineEmits<{ (event: 'close'): void }>()

const frame = computed(() => {
  const html = props.preview?.html || ''
  return `<!DOCTYPE html><html><head><meta charset="utf-8" />
    <style>
      body { margin: 0; padding: 20px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; font-size: 14px; line-height: 1.75; color: #0f172a; }
      a { color: #23a79e; }
      img { max-width: 100%; height: auto; }
    </style></head><body>${html}</body></html>`
})
</script>

<template>
  <div class="mc-modal" @click.self="emit('close')">
    <div class="mc-modal__panel">
      <div class="mc-modal__head">
        <h3>{{ title }}</h3>
        <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="emit('close')">
          关闭
        </button>
      </div>
      <div class="mc-modal__body">
        <div v-if="loading" class="mc-empty">正在渲染…</div>
        <div v-else-if="error" class="mc-notice mc-notice--error">{{ error }}</div>
        <template v-else-if="preview">
          <div class="mc-preview">
            <div class="mc-preview__subject">标题：{{ preview.subject || '（空）' }}</div>
            <iframe class="mc-preview__frame" :srcdoc="frame" sandbox="" />
          </div>
          <div v-if="preview.raw" style="margin-top: 14px">
            <div class="mc-card__title"><h3>纯文本版本</h3></div>
            <pre class="mc-code">{{ preview.raw }}</pre>
          </div>
        </template>
        <div v-else class="mc-empty">没有可预览的内容</div>
      </div>
    </div>
  </div>
</template>