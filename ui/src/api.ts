import axios from 'axios'

/** Every console call goes through the plugin's own API group. */
export const API_BASE = '/apis/mail.fufu520.cn/v1alpha1'

export interface TemplateVariable {
  name: string
  label: string
  type: string
  sampleValue: string
  required: boolean
}

export interface BuiltinTemplate {
  name: string
  reasonType: string
  language: string
  title: string
  htmlBody: string
  rawBody: string
  createdAt: string
  hidden: boolean
  edited: boolean
}

export interface BuiltinGroup {
  reasonType: string
  displayName: string
  description: string
  hidden: boolean
  variables: TemplateVariable[]
  templates: BuiltinTemplate[]
}

export interface CustomTemplate {
  name: string
  displayName: string
  description: string
  subject: string
  content: string
  rawContent: string
  variables: TemplateVariable[]
  enabled: boolean
  createdAt: string
  hidden: boolean
}

export interface SystemTemplate {
  key: string
  name: string
  displayName: string
  description: string
  subject: string
  htmlBody: string
  rawBody: string
  variables: TemplateVariable[]
  edited: boolean
}

export interface Recipient {
  email: string
  displayName: string
  avatar: string
  kind: string
  verified: boolean
  lastSeenAt: string
}

export interface RecipientDirectory {
  users: Recipient[]
  visitors: Recipient[]
  totalUsers: number
  totalVisitors: number
}

export interface SendTarget {
  email: string
  name?: string
}

export interface SendResult {
  email: string
  name: string
  success: boolean
  error: string
  logName: string
}

export interface SendSummary {
  total: number
  success: number
  failed: number
  results: SendResult[]
}

export interface TemplatePreview {
  subject: string
  html: string
  raw: string
  variables: TemplateVariable[]
}

export interface LogView {
  name: string
  recipient: string
  recipientName: string
  templateType: string
  templateName: string
  templateDisplayName: string
  subject: string
  status: string
  error: string
  source: string
  triggerName: string
  sentAt: string
  durationMs: number
}

export interface LogPage {
  items: LogView[]
  total: number
  page: number
  size: number
}

export interface LogDetail extends LogView {
  dedupKey: string
  htmlBody: string
  rawBody: string
}

export interface BundleImportItem {
  section: string
  name: string
  displayName: string
  status: string
  message: string
}

export interface BundleImportResult {
  dryRun: boolean
  total: number
  updated: number
  unchanged: number
  skipped: number
  failed: number
  items: BundleImportItem[]
}

export interface TriggerView {
  name: string
  displayName: string
  enabled: boolean
  eventType: string
  templateType: string
  templateName: string
  templateDisplayName: string
  subjectPreview: string
  recipientStrategy: string
  extraEmails: string[]
  condition: string
  startedAt: string
  sent: number
  failed: number
  skipped: number
  lastRunAt: string
  lastMessage: string
  extraRecipients: SendTarget[]
}

export interface Overview {
  builtinTemplates: number
  builtinReasonTypes: number
  customTemplates: number
  triggers: number
  enabledTriggers: number
  sentTotal: number
  failedTotal: number
  sentToday: number
  channelSource: string
  channelUsable: boolean
  channelProblem: string
  apiEnabled: boolean
  apiKey: string
  linksPluginDetected: boolean
}

export interface ChannelView {
  source: string
  notifierDescriptor: string
  host: string
  port: number
  username: string
  hasPassword: boolean
  sender: string
  displayName: string
  encryption: string
  apiEnabled: boolean
  apiKey: string
  resolvedSource: string
  usable: boolean
  problem: string
}

export interface ApiResponse {
  ok: boolean
  message: string
}

/** Turns an axios failure into the message the backend wanted to show. */
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiResponse | undefined
    if (data?.message) {
      return data.message
    }
    return error.message
  }
  return error instanceof Error ? error.message : String(error)
}

export const api = {
  overview: () => axios.get<Overview>(`${API_BASE}/overview`).then((r) => r.data),

  templates: () =>
    axios
      .get<{ groups: BuiltinGroup[]; custom: CustomTemplate[]; system: SystemTemplate[] }>(
        `${API_BASE}/templates`
      )
      .then((r) => r.data),

  createTemplate: (payload: Partial<CustomTemplate>) =>
    axios.post<CustomTemplate>(`${API_BASE}/templates`, payload).then((r) => r.data),

  updateTemplate: (name: string, payload: Partial<CustomTemplate>) =>
    axios.put<CustomTemplate>(`${API_BASE}/templates/custom/${name}`, payload).then((r) => r.data),

  deleteTemplate: (name: string) =>
    axios.delete<ApiResponse>(`${API_BASE}/templates/custom/${name}`).then((r) => r.data),

  updateBuiltinTemplate: (name: string, payload: { title: string; htmlBody: string; rawBody: string }) =>
    axios.put<BuiltinTemplate>(`${API_BASE}/templates/builtin/${name}`, payload).then((r) => r.data),

  deleteBuiltinTemplate: (name: string) =>
    axios.delete<ApiResponse>(`${API_BASE}/templates/builtin/${name}`).then((r) => r.data),

  resetBuiltinTemplate: (name: string) =>
    axios.post<BuiltinTemplate>(`${API_BASE}/templates/builtin/${name}/reset`).then((r) => r.data),

  hideTemplate: (type: 'BUILTIN' | 'CUSTOM', name: string, hidden: boolean) => {
    const base = type === 'CUSTOM' ? 'custom' : 'builtin'
    return axios
      .post<ApiResponse>(`${API_BASE}/templates/${base}/${name}/hidden`, { hidden })
      .then((r) => r.data)
  },

  validateTemplate: (payload: Record<string, unknown>) =>
    axios.post<ApiResponse>(`${API_BASE}/templates/validate`, payload).then((r) => r.data),

  updateSystemTemplate: (key: string, payload: Record<string, unknown>) =>
    axios.put<SystemTemplate>(`${API_BASE}/templates/system/${key}`, payload).then((r) => r.data),

  resetSystemTemplate: (key: string) =>
    axios.post<SystemTemplate>(`${API_BASE}/templates/system/${key}/reset`).then((r) => r.data),

  exportBundle: () => axios.get<Record<string, unknown>>(`${API_BASE}/bundle`).then((r) => r.data),

  importBundle: (document_: Record<string, unknown>, dryRun: boolean) =>
    axios
      .post<BundleImportResult>(`${API_BASE}/bundle/import`, document_, { params: { dryRun } })
      .then((r) => r.data),

  preview: (payload: Record<string, unknown>) =>
    axios.post<TemplatePreview>(`${API_BASE}/preview`, payload).then((r) => r.data),

  recipients: (keyword: string) =>
    axios
      .get<RecipientDirectory>(`${API_BASE}/recipients`, { params: { keyword, limit: 300 } })
      .then((r) => r.data),

  send: (payload: Record<string, unknown>) =>
    axios.post<SendSummary>(`${API_BASE}/send`, payload).then((r) => r.data),

  logs: (params: {
    page: number
    size: number
    status?: string
    keyword?: string
    source?: string
  }) => axios.get<LogPage>(`${API_BASE}/logs`, { params }).then((r) => r.data),

  logDetail: (name: string) =>
    axios.get<LogDetail>(`${API_BASE}/logs/${name}`).then((r) => r.data),

  clearLogs: () => axios.delete<ApiResponse>(`${API_BASE}/logs`).then((r) => r.data),

  triggers: () => axios.get<TriggerView[]>(`${API_BASE}/triggers`).then((r) => r.data),

  createTrigger: (payload: Record<string, unknown>) =>
    axios.post<TriggerView>(`${API_BASE}/triggers`, payload).then((r) => r.data),

  updateTrigger: (name: string, payload: Record<string, unknown>) =>
    axios.put<TriggerView>(`${API_BASE}/triggers/${name}`, payload).then((r) => r.data),

  deleteTrigger: (name: string) =>
    axios.delete<ApiResponse>(`${API_BASE}/triggers/${name}`).then((r) => r.data),

  channel: () => axios.get<ChannelView>(`${API_BASE}/channel`).then((r) => r.data),

  saveChannel: (payload: Record<string, unknown>) =>
    axios.put<ChannelView>(`${API_BASE}/channel`, payload).then((r) => r.data),

  testChannel: (to: string) =>
    axios.post<ApiResponse>(`${API_BASE}/channel/test`, { to }).then((r) => r.data),
}

export const EVENT_TYPES = [
  { value: 'COMMENT_CREATED', label: '文章或页面收到新评论' },
  { value: 'USER_REGISTERED', label: '有新用户注册' },
  { value: 'POST_PUBLISHED', label: '有新文章发布' },
  { value: 'LINK_APPROVED', label: '友链申请审核通过（需装链接管理插件）' },
  { value: 'LINK_REJECTED', label: '友链申请被拒绝（需装链接管理插件）' },
]

export const RECIPIENT_STRATEGIES = [
  { value: 'EVENT_ACTOR', label: '事件当事人（评论者 / 新用户 / 文章作者 / 友链申请人）' },
  { value: 'POST_AUTHOR', label: '被评论文章的作者' },
  { value: 'FIXED', label: '只发给下面填写的固定邮箱' },
]

export function formatTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (input: number) => String(input).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}