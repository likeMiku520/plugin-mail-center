<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, errorMessage, type ApiResponse, type ChannelView } from '../api'

const channel = ref<ChannelView | null>(null)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = ref('')

const source = ref('halo')
const notifierDescriptor = ref('default-email-notifier')
const host = ref('')
const port = ref<number | null>(465)
const username = ref('')
const password = ref('')
const sender = ref('')
const displayName = ref('')
const encryption = ref('SSL')
const apiEnabled = ref(false)
const rateLimitPerMinute = ref<number | null>(60)
const testTo = ref('')
const testing = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const view = await api.channel()
    channel.value = view
    source.value = view.source || 'halo'
    notifierDescriptor.value = view.notifierDescriptor || 'default-email-notifier'
    host.value = view.host || ''
    port.value = view.port || 465
    username.value = view.username || ''
    sender.value = view.sender || ''
    displayName.value = view.displayName || ''
    encryption.value = view.encryption || 'SSL'
    apiEnabled.value = view.apiEnabled
    rateLimitPerMinute.value = 60
    password.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    channel.value = await api.saveChannel({
      source: source.value,
      notifierDescriptor: notifierDescriptor.value,
      host: host.value,
      port: port.value,
      username: username.value,
      password: password.value,
      sender: sender.value,
      displayName: displayName.value,
      encryption: encryption.value,
      apiEnabled: apiEnabled.value,
      rateLimitPerMinute: rateLimitPerMinute.value,
    })
    password.value = ''
    message.value = '设置已保存'
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function regenerate() {
  if (!window.confirm('重新生成后，旧的触发密钥会立刻失效，请同步更新你的代码。确定继续吗？')) {
    return
  }
  saving.value = true
  error.value = ''
  try {
    channel.value = await api.saveChannel({
      source: source.value,
      notifierDescriptor: notifierDescriptor.value,
      host: host.value,
      port: port.value,
      username: username.value,
      sender: sender.value,
      displayName: displayName.value,
      encryption: encryption.value,
      apiEnabled: apiEnabled.value,
      rateLimitPerMinute: rateLimitPerMinute.value,
      regenerateApiKey: true,
    })
    message.value = '已生成新的触发密钥'
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = false
  }
}

async function test() {
  if (!testTo.value) {
    error.value = '请先填写一个用来收测试邮件的地址'
    return
  }
  testing.value = true
  error.value = ''
  message.value = ''
  try {
    const response: ApiResponse = await api.testChannel(testTo.value)
    if (response.ok) {
      message.value = response.message
    } else {
      error.value = response.message
    }
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="mc-root">
    <div class="mc-head">
      <div>
        <h2>发信设置</h2>
        <p>决定邮件从哪个邮箱发出去，以及给外部代码留的触发接口怎么用。</p>
      </div>
      <div class="mc-row">
        <button class="mc-btn" type="button" @click="load">重新读取</button>
        <button class="mc-btn mc-btn--primary" type="button" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存设置' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="mc-empty">正在读取设置…</div>

    <template v-else>
      <div
        v-if="channel && !channel.usable"
        class="mc-notice mc-notice--warn"
      >
        当前通道不可用：{{ channel.problem }}
      </div>
      <div v-if="channel && channel.usable" class="mc-notice">
        发信通道已就绪，凭据来自{{
          channel.resolvedSource === 'halo' ? ' Halo 的邮件通知配置' : ' 本页填写的自定义 SMTP'
        }}。
      </div>
      <div v-if="message" class="mc-notice">{{ message }}</div>
      <div v-if="error" class="mc-notice mc-notice--error">{{ error }}</div>

      <div class="mc-card">
        <div class="mc-card__title"><h3>发信通道</h3></div>
        <div class="mc-channels">
          <label :class="['mc-item', { 'mc-item--active': source === 'halo' }]" style="cursor: pointer">
            <div class="mc-item__main">
              <div class="mc-item__title">
                <input v-model="source" type="radio" value="halo" />
                <span>复用 Halo 的邮件通知配置</span>
              </div>
              <div class="mc-item__meta">
                直接使用「设置 → 通知 → 邮件通知」里已经填好的 SMTP，不用重复配置。推荐。
              </div>
            </div>
          </label>
          <label
            :class="['mc-item', { 'mc-item--active': source === 'custom' }]"
            style="cursor: pointer"
          >
            <div class="mc-item__main">
              <div class="mc-item__title">
                <input v-model="source" type="radio" value="custom" />
                <span>使用下面的自定义 SMTP</span>
              </div>
              <div class="mc-item__meta">
                适合想把插件发信和 Halo 通知分开的情况。密码只保存在 Secret 里。
              </div>
            </div>
          </label>
        </div>

        <label v-if="source === 'halo'" class="mc-field" style="margin-top: 14px; margin-bottom: 0">
          <span>Halo 邮件通知器标识</span>
          <input v-model="notifierDescriptor" class="mc-input" placeholder="default-email-notifier" />
          <small>除非你装过替换邮件通知器的插件，否则保持默认即可。</small>
        </label>
      </div>

      <div v-if="source === 'custom'" class="mc-card">
        <div class="mc-card__title"><h3>自定义 SMTP</h3></div>
        <div class="mc-grid mc-grid--two">
          <label class="mc-field">
            <span>SMTP 服务器</span>
            <input v-model="host" class="mc-input" placeholder="smtp.example.com" />
          </label>
          <label class="mc-field">
            <span>端口</span>
            <input v-model.number="port" class="mc-input" type="number" placeholder="465" />
          </label>
          <label class="mc-field">
            <span>用户名</span>
            <input v-model="username" class="mc-input" placeholder="通常是完整邮箱地址" />
          </label>
          <label class="mc-field">
            <span>密码 / 授权码</span>
            <input
              v-model="password"
              class="mc-input"
              type="password"
              :placeholder="channel?.hasPassword ? '已保存，留空表示不修改' : '请输入密码或授权码'"
            />
          </label>
          <label class="mc-field">
            <span>发信地址（可选）</span>
            <input v-model="sender" class="mc-input" placeholder="留空则使用用户名" />
          </label>
          <label class="mc-field">
            <span>显示名称（可选）</span>
            <input v-model="displayName" class="mc-input" placeholder="例如：fufu 的博客" />
          </label>
          <label class="mc-field">
            <span>加密方式</span>
            <select v-model="encryption" class="mc-select">
              <option value="SSL">SSL（一般 465 端口）</option>
              <option value="TLS">TLS（一般 587 端口）</option>
              <option value="NONE">不加密</option>
            </select>
          </label>
        </div>
      </div>

      <div class="mc-card">
        <div class="mc-card__title"><h3>触发接口</h3></div>
        <label class="mc-check" style="margin-bottom: 12px">
          <input v-model="apiEnabled" type="checkbox" />
          启用给外部代码调用的触发接口
        </label>
        <div class="mc-grid mc-grid--two">
          <label class="mc-field">
            <span>触发密钥</span>
            <div class="mc-row">
              <input class="mc-input" style="flex: 1" readonly :value="channel?.apiKey || ''" />
              <button class="mc-btn" type="button" @click="regenerate">重新生成</button>
            </div>
            <small>调用时放在请求头 X-Mail-Key 里。</small>
          </label>
          <label class="mc-field">
            <span>每分钟限流（按来源 IP）</span>
            <input v-model.number="rateLimitPerMinute" class="mc-input" type="number" min="1" />
          </label>
        </div>
      </div>

      <div class="mc-card">
        <div class="mc-card__title"><h3>发送测试邮件</h3></div>
        <div class="mc-row">
          <input
            v-model="testTo"
            class="mc-input"
            style="flex: 1"
            placeholder="收测试邮件的邮箱地址"
          />
          <button class="mc-btn mc-btn--primary" type="button" :disabled="testing" @click="test">
            {{ testing ? '发送中…' : '发送测试' }}
          </button>
        </div>
        <small style="display: block; margin-top: 8px; color: var(--mc-text-faint)">
          测试会按当前生效的通道立刻发一封信，失败时会给出具体原因。
        </small>
      </div>
    </template>
  </div>
</template>

<style scoped>
.mc-channels {
  display: grid;
  gap: 10px;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
}
</style>