import { definePlugin } from '@halo-dev/console-shared'
import { markRaw } from 'vue'
import RiMailSendLine from '~icons/ri/mail-send-line'
import RiSendPlaneLine from '~icons/ri/send-plane-line'
import RiFlashlightLine from '~icons/ri/flashlight-line'
import RiHistoryLine from '~icons/ri/history-line'
import RiSettings3Line from '~icons/ri/settings-3-line'
import TemplatesPage from './views/TemplatesPage.vue'
import ComposePage from './views/ComposePage.vue'
import TriggersPage from './views/TriggersPage.vue'
import LogsPage from './views/LogsPage.vue'
import SettingsPage from './views/SettingsPage.vue'
import './styles/theme.scss'

// Halo's console matches this against its own group ids, so the entries land in the
// built-in "工具" section instead of creating a second group with a lookalike label.
const GROUP = 'tool'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/mail-center',
        name: 'MailCenterTemplates',
        component: TemplatesPage,
        meta: {
          title: '邮件中心',
          searchable: true,
          menu: {
            name: '邮件中心',
            group: GROUP,
            icon: markRaw(RiMailSendLine),
            priority: 0,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/mail-center/send',
        name: 'MailCenterCompose',
        component: ComposePage,
        meta: {
          title: '写邮件',
          searchable: true,
          menu: {
            name: '写邮件',
            group: GROUP,
            icon: markRaw(RiSendPlaneLine),
            priority: 1,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/mail-center/triggers',
        name: 'MailCenterTriggers',
        component: TriggersPage,
        meta: {
          title: '触发规则',
          searchable: true,
          menu: {
            name: '触发规则',
            group: GROUP,
            icon: markRaw(RiFlashlightLine),
            priority: 2,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/mail-center/logs',
        name: 'MailCenterLogs',
        component: LogsPage,
        meta: {
          title: '发送记录',
          searchable: true,
          menu: {
            name: '发送记录',
            group: GROUP,
            icon: markRaw(RiHistoryLine),
            priority: 3,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/mail-center/settings',
        name: 'MailCenterSettings',
        component: SettingsPage,
        meta: {
          title: '发信设置',
          searchable: true,
          menu: {
            name: '发信设置',
            group: GROUP,
            icon: markRaw(RiSettings3Line),
            priority: 4,
          },
        },
      },
    },
  ],
})