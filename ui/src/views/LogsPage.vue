<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, errorMessage, formatTime, type LogDetail, type LogPage } from '../api'

const sizes = [10, 20, 50]
const page = ref(1)
const size = ref(20)
const status = ref('')
const source = ref('')
const keyword = ref('')
const data = ref<LogPage | null>(null)
const loading = ref(true)
const error = ref('')

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<LogDetail | null>(null)

const totalPages = computed(() =>
  data.value ? Math.max(1, Math.ceil(data.value.total / size.value)) : 1
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.logs({
      page: page.value,
      size: size.value,
      status: status.value || undefined,
      source: source.value || undefined,
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

function changeSize() {
  page.value = 1
  load()
}

function go(next: number) {
  page.value = Math.min(Math.max(1, next), totalPages.value)
  load()
}

async function clearAll() {
  if (
    !window.confirm(
      '确定清空全部发送记录吗？自动规则靠这些记录去重，清空之后回看窗口内的事件可能被重发一次。'
    )
  ) {
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

async function openDetail(name: string) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    detail.value = await api.logDetail(name)
  } catch (e) {
    detailError.value = errorMessage(e)
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailOpen.value = false
  detail.value = null
  detailError.value = ''
}

function sourceLabel(value?: string) {
  if (value === 'MANUAL') return '手动发送'
  if (value === 'API') return '触发接口'
  if (value === 'TRIGGER') return '自动规则'
  if (value === 'SITE') return '站点通知'
  return value || '—'
}

function statusLabel(value?: string) {
  if (value === 'SUCCESS') return '已发送'
  if (value === 'SENT') return '站点已发出'
  if (value === 'SKIPPED') return '未发送'
  return '失败'
}

function statusClass(value?: string) {
  if (value === 'SUCCESS') return 'mc-tag mc-tag--primary'
  if (value === 'SENT') return 'mc-tag'
  if (value === 'SKIPPED') return 'mc-tag mc-tag--warn'
  return 'mc-tag mc-tag--danger'
}

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>发送记录</h2>
        <p>插件自己发出的邮件都会留档，点任意一条可以看到当时发出去的样式；站点自己发出的站内通知也一并收录。</p>
      </div>
      <div class="mc-toolbar">
        <button class="mc-btn" type="button" @click="load">刷新</button>
        <button class="mc-btn mc-btn--danger" type="button" @click="clearAll">清空记录</button>
      </div>
    </div>

    <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>

    <div class="mc-card">
      <div class="mc-row" style="margin-bottom: 12px">
        <label class="mc-field" style="margin: 0; min-width: 140px">
          <span>来源</span>
          <select v-model="source" class="mc-select" @change="search">
            <option value="">全部</option>
            <option value="MANUAL">手动发送</option>
            <option value="TRIGGER">自动规则</option>
            <option value="API">触发接口</option>
            <option value="SITE">站点通知</option>
          </select>
        </label>
        <label class="mc-field" style="margin: 0; min-width: 130px">
          <span>状态</span>
          <select v-model="status" class="mc-select" @change="search">
            <option value="">全部</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILED">失败</option>
            <option value="SKIPPED">未发送</option>
            <option value="SENT">站点已发出</option>
          </select>
        </label>
        <label class="mc-field" style="margin: 0; flex: 1; min-width: 180px">
          <span>关键字</span>
          <input
            v-model="keyword"
            class="mc-input"
            placeholder="按收件人、标题或模板筛选"
            @keyup.enter="search"
          />
        </label>
        <label class="mc-field" style="margin: 0; min-width: 96px">
          <span>每页</span>
          <select v-model.number="size" class="mc-select" @change="changeSize">
            <option v-for="option in sizes" :key="option" :value="option">{{ option }} 条</option>
          </select>
        </label>
        <button class="mc-btn mc-btn--primary" type="button" @click="search">查询</button>
      </div>

      <div v-if="loading" class="mc-empty">正在读取记录…</div>
      <div v-else-if="!data || data.items.length === 0" class="mc-empty">没有符合条件的记录</div>
      <template v-else>
        <div class="mc-list mc-list--tight">
          <div
            v-for="item in data.items"
            :key="item.name"
            class="mc-item mc-item--clickable"
            @click="openDetail(item.name)"
          >
            <div class="mc-item__main">
              <div class="mc-item__title">
                <span>{{ item.subject || '（无标题）' }}</span>
                <span :class="statusClass(item.status)">{{ statusLabel(item.status) }}</span>
                <span class="mc-tag">{{ sourceLabel(item.source) }}</span>
              </div>
              <div class="mc-item__meta">
                收件人：{{ item.recipientName || item.recipient }} · 模板：{{
                  item.templateDisplayName || item.templateName || '—'
                }}
              </div>
              <div class="mc-item__meta">
                {{ formatTime(item.sentAt) }}
                <template v-if="item.durationMs"> · 耗时 {{ item.durationMs }} ms</template>
                <template v-if="item.triggerName"> · 规则 {{ item.triggerName }}</template>
              </div>
              <div
                v-if="item.error"
                class="mc-item__meta"
                :style="{ color: item.status === 'SKIPPED' ? 'var(--mc-text-faint)' : 'var(--mc-danger)' }"
              >
                {{ item.error }}
              </div>
            </div>
            <div class="mc-item__actions">
              <span class="mc-hint">查看 ›</span>
            </div>
          </div>
        </div>

        <div class="mc-row" style="justify-content: space-between; margin-top: 14px">
          <span class="mc-hint">共 {{ data.total }} 条，第 {{ data.page }} / {{ totalPages }} 页</span>
          <span class="mc-row">
            <button class="mc-btn mc-btn--sm" type="button" :disabled="page <= 1" @click="go(page - 1)">
              上一页
            </button>
            <button
              class="mc-btn mc-btn--sm"
              type="button"
              :disabled="page >= totalPages"
              @click="go(page + 1)"
            >
              下一页
            </button>
          </span>
        </div>
      </template>
    </div>

    <div v-if="detailOpen" class="mc-modal" @click.self="closeDetail">
      <div class="mc-modal__panel mc-modal__panel--side">
        <div class="mc-modal__head">
          <h3>发送详情</h3>
          <button class="mc-btn mc-btn--sm mc-btn--ghost" type="button" @click="closeDetail">
            关闭
          </button>
        </div>
        <div class="mc-modal__body">
          <div v-if="detailError" class="mc-notice mc-notice--error">{{ detailError }}</div>
          <div v-if="detailLoading" class="mc-empty">正在读取…</div>
          <template v-else-if="detail">
            <div class="mc-item__title" style="margin-bottom: 10px">
              <span>{{ detail.subject || '（无标题）' }}</span>
              <span :class="statusClass(detail.status)">{{ statusLabel(detail.status) }}</span>
              <span class="mc-tag">{{ sourceLabel(detail.source) }}</span>
            </div>

            <dl class="mc-detail">
              <dt>收件人</dt>
              <dd>
                {{ detail.recipientName ? `${detail.recipientName} · ` : '' }}{{ detail.recipient }}
              </dd>
              <dt>发送时间</dt>
              <dd>{{ formatTime(detail.sentAt) }}</dd>
              <dt>模板</dt>
              <dd>{{ detail.templateDisplayName || detail.templateName || '—' }}</dd>
              <dt>来源</dt>
              <dd>{{ sourceLabel(detail.source) }}</dd>
              <template v-if="detail.triggerName">
                <dt>触发规则</dt>
                <dd>{{ detail.triggerName }}</dd>
              </template>
              <template v-if="detail.durationMs">
                <dt>耗时</dt>
                <dd>{{ detail.durationMs }} ms</dd>
              </template>
              <template v-if="detail.dedupKey">
                <dt>去重键</dt>
                <dd><code>{{ detail.dedupKey }}</code></dd>
              </template>
              <template v-if="detail.error">
                <dt>说明</dt>
                <dd :style="{ color: detail.status === 'SKIPPED' ? 'var(--mc-text-soft)' : 'var(--mc-danger)' }">
                  {{ detail.error }}
                </dd>
              </template>
            </dl>

            <div class="mc-section-title">发送出去的样式</div>
            <div v-if="detail.htmlBody" class="mc-preview">
              <div class="mc-preview__subject">{{ detail.subject || '（无标题）' }}</div>
              <iframe
                class="mc-preview__frame"
                sandbox=""
                :srcdoc="detail.htmlBody"
                style="height: 460px"
              />
            </div>
            <div v-else class="mc-empty mc-empty--sm">
              这条记录没有保存邮件正文。正文是从 1.2.0 之后才开始留存的，更早的记录只能看到上面的信息。
            </div>

            <div v-if="detail.rawBody" class="mc-section-title">纯文本正文</div>
            <pre v-if="detail.rawBody" class="mc-code" style="max-height: 220px; overflow: auto">{{
              detail.rawBody
            }}</pre>
          </template>
        </div>
        <div class="mc-modal__foot">
          <button class="mc-btn" type="button" @click="closeDetail">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>