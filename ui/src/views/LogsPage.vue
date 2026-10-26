<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage, formatTime, type LogPage } from '../api'

const page = ref(1)
const size = ref(20)
const status = ref('')
const keyword = ref('')
const data = ref<LogPage | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.logs({
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      keyword: keyword.value || undefined,
    })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function go(next: number) {
  const totalPages = data.value ? Math.max(1, Math.ceil(data.value.total / size.value)) : 1
  page.value = Math.min(Math.max(1, next), totalPages)
  load()
}

async function clearAll() {
  if (!window.confirm('确定清空全部发送记录吗？自动触发规则会失去去重依据，可能重复发送同一条邮件。')) {
    return
  }
  try {
    await api.clearLogs()
    page.value = 1
    await load()
  } catch (e) {
    error.value = errorMessage(e)
  }
}

function sourceLabel(value?: string) {
  if (value === 'MANUAL') return '手动发送'
  if (value === 'API') return '触发接口'
  if (value === 'TRIGGER') return '自动规则'
  return value || '—'
}

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>发送记录</h2>
        <p>每一次发信都会留档，成功失败、耗时和错误原因都能查到。</p>
      </div>
      <div class="mc-row">
        <button class="mc-btn" type="button" @click="load">刷新</button>
        <button class="mc-btn mc-btn--danger" type="button" @click="clearAll">清空记录</button>
      </div>
    </div>

    <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>

    <div class="mc-card">
      <div class="mc-row" style="margin-bottom: 14px">
        <label class="mc-field" style="margin: 0; min-width: 160px">
          <span>状态</span>
          <select v-model="status" class="mc-select" @change="search">
            <option value="">全部</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
          </select>
        </label>
        <label class="mc-field" style="margin: 0; flex: 1; min-width: 200px">
          <span>关键字</span>
          <input
            v-model="keyword"
            class="mc-input"
            placeholder="按收件人、标题或模板筛选"
            @keyup.enter="search"
          />
        </label>
        <button class="mc-btn mc-btn--primary" type="button" @click="search">查询</button>
      </div>

      <div v-if="loading" class="mc-empty">正在读取记录…</div>
      <div v-else-if="!data || data.items.length === 0" class="mc-empty">还没有发送记录</div>
      <template v-else>
        <div class="mc-list">
          <div v-for="item in data.items" :key="item.name" class="mc-item">
            <div class="mc-item__main">
              <div class="mc-item__title">
                <span>{{ item.subject || '（无标题）' }}</span>
                <span :class="['mc-tag', item.status === 'SUCCESS' ? 'mc-tag--primary' : 'mc-tag--danger']">
                  {{ item.status === 'SUCCESS' ? '已发送' : '失败' }}
                </span>
                <span class="mc-tag">{{ sourceLabel(item.source) }}</span>
              </div>
              <div class="mc-item__meta">
                收件人：{{ item.recipientName || item.recipient }} · 模板：{{
                  item.templateDisplayName || item.templateName
                }}
              </div>
              <div class="mc-item__meta">
                {{ formatTime(item.sentAt) }}
                <template v-if="item.durationMs"> · 耗时 {{ item.durationMs }} ms</template>
                <template v-if="item.triggerName"> · 规则 {{ item.triggerName }}</template>
              </div>
              <div v-if="item.error" class="mc-item__meta" style="color: var(--mc-danger)">
                {{ item.error }}
              </div>
            </div>
          </div>
        </div>

        <div class="mc-row" style="justify-content: space-between; margin-top: 14px">
          <span style="color: var(--mc-text-faint); font-size: 12px">
            共 {{ data.total }} 条，第 {{ data.page }} / {{ Math.max(1, Math.ceil(data.total / size)) }} 页
          </span>
          <span class="mc-row">
            <button class="mc-btn mc-btn--sm" type="button" @click="go(page - 1)">上一页</button>
            <button class="mc-btn mc-btn--sm" type="button" @click="go(page + 1)">下一页</button>
          </span>
        </div>
      </template>
    </div>
  </div>
</template>