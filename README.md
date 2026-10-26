# 邮件中心

把博客里所有能发出去的邮件模板集中到一处，手动挑一个发给某个人，或者写一条规则让博客自己在合适的时候发出去。

Halo 自带的通知邮件模板散在「通知」里，只能改内容，不能主动拿去发给谁；这个插件补上的是「用模板发信」这一段：模板看得见、收件人挑得到、发出去有记录、代码也接得上。

## 功能

**站内模板一览。** 扫描站点上全部 `NotificationTemplate`，按触发它们的通知类型分组展示，每条模板都能直接预览效果。邮件通知器是否开启、某一类通知有没有模板，页面上都有明确标注，不用再去别处翻。

**自定义模板。** 可以在插件里新建模板，写标题、HTML 正文和纯文本正文，声明模板里用到的变量并给一个示例值。变量只用于预览和语法检查，实际发信时按当前收件人和填写的值渲染。站内模板可以一键复制成自定义模板再改，不用从空白开始。

**手动发信。** 选模板、选收件人、填变量就能发。收件人支持三种来源：站内用户（读取账号邮箱）、访客（从历史评论里提取邮箱地址）、手动输入任意地址（一行一个，逗号分号都认）。发送前可以预览，发送后逐条显示成功或失败和具体原因。

**触发规则。** 三种事件可选：文章或页面收到新评论、有新用户注册、有新文章发布。收件人可以是事件当事人、被评论文章的作者，或者固定填写的邮箱（固定邮箱会追加在策略结果之后）。规则可以再挂一个 SpEL 条件，例如只在评论内容包含某个词时才发。规则只对启用之后产生的内容生效，不会把历史数据补发一遍。

**给代码留的接口。** 打开开关后会生成一串触发密钥，外部脚本带上 `X-Mail-Key` 请求头就能调用 `POST /apis/mail.fufu520.cn/v1alpha1/trigger` 发信，模板名写 `builtin:模板名` 时使用 Halo 自带的通知模板。接口按来源 IP 限流，密钥可以在「发信设置」里重新生成，重新生成后旧密钥立即失效。

**发送记录。** 每次发信都留档：收件人、模板、标题、状态、耗时和失败原因。记录按设置的保留天数和条数上限自动清理。

## 发信通道

插件自己构造 SMTP 客户端，账号有两种取法，在「发信设置」里切换：

- **复用 Halo 的邮件通知配置**（默认）。直接读「设置 → 通知 → 邮件通知」里已经填好的 SMTP，站点原来能收到通知邮件，插件装上就能用。
- **自定义 SMTP**。单独填写服务器、端口、账号、密码和发信地址。密码保存在 Halo 的 Secret 里，不写进 ConfigMap，也不会在接口返回里出现。

两种方式都有「发送测试」按钮，失败时会指出具体原因（未启用、缺少服务器地址、连接被拒等），而不是只报一个失败。

## 环境要求

- Halo 2.26.0 及以上
- 站点已在「设置 → 通知 → 邮件通知」中启用邮件通知，或在插件里配置自定义 SMTP

## 安装

在 Halo 后台「插件 → 安装」里上传 `plugin-mail-center-1.0.0.jar`，启用后左侧「工具」分组下会出现邮件中心的入口。

## 从源码构建

```bash
# 前端
cd ui
pnpm install
pnpm build

# 后端
./gradlew build -x :ui:pnpmSetup -x :ui:pnpmInstall -x :ui:pnpmCheck -x :ui:assemble
```

产物在 `build/libs/plugin-mail-center-<version>.jar`。前端必须先构建，`ui/build/dist` 不存在时 `processUiResources` 不会报错，只会打出一个没有 `console/` 目录的空壳 JAR。

## 触发接口

```bash
curl -X POST 'https://your-domain/apis/mail.fufu520.cn/v1alpha1/trigger' \
  -H 'Content-Type: application/json' \
  -H 'X-Mail-Key: mc_xxxxxxxx' \
  -d '{
    "template": "mail-template-welcome-mail-a1b2c3",
    "to": ["someone@example.com"],
    "variables": { "code": "123456" }
  }'
```

响应里逐条给出每个收件人的结果：

```json
{
  "total": 1,
  "success": 1,
  "failed": 0,
  "results": [
    { "email": "someone@example.com", "success": true, "error": null, "logName": "mail-log-xxxx" }
  ]
}
```

可选的 `subject` 字段会临时替换模板标题。模板名以 `builtin:` 开头时（例如 `builtin:template-new-comment-on-post`）使用 Halo 自带的通知模板，其余按自定义模板名解析。

## 模板语法

模板用 Thymeleaf 渲染，和 Halo 自带通知模板完全一致：

```html
<p>你好 [(${subscriber.displayName})]，</p>
<p>你在 [(${site.title})] 的评论已经通过了。</p>
```

每个模板都自动带上这些值：

| 变量 | 说明 |
| --- | --- |
| `subscriber.displayName` | 收件人显示名，未填写时是邮箱地址 |
| `subscriber.email` | 收件人邮箱 |
| `site.title` / `site.subtitle` / `site.logo` / `site.url` | 站点基本信息 |
| `unsubscribeUrl` | 通知中心地址 |

站内模板的变量来自它所属通知类型的属性定义，自定义模板的变量来自模板自己的声明。触发规则额外提供事件相关变量：评论事件有 `commenter`、`commenterEmail`、`content`、`postTitle`、`postUrl`（自定义页面则是 `pageTitle`、`pageUrl`），注册事件有 `username`、`displayName`、`email`、`registeredAt`，发布事件有 `postTitle`、`postUrl`、`postAuthor`、`postSummary`。

## 数据模型

插件注册三个自定义模型，都在 `mail.fufu520.cn/v1alpha1` 组下：

- `MailTemplate` — 自定义邮件模板
- `MailSendLog` — 发送记录，同时充当自动触发的去重台账
- `MailTrigger` — 触发规则

自动触发的去重靠记录名：规则名、事件类型、事件对象和收件人拼成一个键，取哈希作为记录名。同一条事件重复扫描时会先撞上已有的记录，因此重启、重新部署或扫描变慢都不会重复发送。

## 常见问题

**页面提示「读不到 Halo 的邮件通知配置」。** 说明「设置 → 通知 → 邮件通知」还没有启用或没有保存过。在那里填好并保存，或者到「发信设置」切换成自定义 SMTP。

**发送失败提示连接被拒或超时。** 多数是端口和加密方式不匹配：465 配 SSL、587 配 TLS，其余情况选不加密。部分邮箱要求使用授权码而不是登录密码。

**触发规则没有生效。** 检查规则是否处于启用状态，另外规则只处理启用之后产生的内容；评论事件默认只对审核通过的评论发信，可以在插件设置里关掉这个限制。

**发送记录被清空会影响什么。** 自动规则靠记录去重。清空记录后，回看窗口内的事件有可能被重新发送一次。

## 许可

GPL-3.0