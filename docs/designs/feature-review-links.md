# Feature 详情中的 PR/MR 链接

状态：已实现，待发布。

用户打开 Feature 详情后，插件根据该 Feature 已记录的工作分支，从 GitHub 或 GitLab 查询对应 PR/MR。每个仓库显示一个评审链接，点击后在浏览器打开。网页手动创建与 Agent 创建的评审采用同一查询方式。

本次只修改 `agent-workbench-intellij`。依据用户明确要求，设计也保存在插件仓；不修改相邻 Kit 仓、Inspect 协议、qbit 扩展或 Feature 文档。

## 1. 范围与使用流程

首版提供 GitHub.com、GitLab.com 和自建 GitLab 的 Token 查询，覆盖同一远程仓库内的功能分支 PR/MR，包括未合并、已关闭和已合并记录。

1. 在 `Settings | Tools | Agent Workbench` 的“代码托管服务”中填写平台、服务地址和 Token。
2. 打开一个 Feature 的“概览”。插件只查询该 Feature 关联的仓库。
3. 打开 Feature 的“变更”，在默认“代码评审”子页查看各仓库的 PR/MR，点击链接在系统浏览器打开。
4. 刚创建 PR、评审状态有变化或查询失败时，点击该区域的“刷新”。

概览、首页、业务仓库列表和 Feature 列表不展示、不查询 PR/MR。首版不创建、审批或合并评审，不接入 Jira、飞书、CLI、OAuth、后台轮询或扩展执行框架。

## 2. 界面设计

### Feature 变更页

“变更”包含“代码评审”“需求分支已提交”“当前工作目录”三个子页，默认显示“代码评审”。评审表格每仓一行，列顺序为仓库、PR/MR、状态；右上角提供刷新。标题长时在链接旁省略显示，悬浮显示完整标题与来源分支、目标分支、最后查询时间。原概览保持现有布局，不被评审区域挤压。

“需求分支已提交”保留原有文件、提交、Fetch 和 Diff 能力，但压缩仓库与分支摘要，文件和提交列表占据主体高度；切换 Feature 或仓库时显式加载，不依赖下拉框事件触发。“当前工作目录”继续使用宿主 Git 视图。

以下为界面示例，不代表实际评审记录：

| 仓库 | PR/MR | 状态 |
| --- | --- | --- |
| service-a | PR #128 · 钱包接口调整 | 待合并 |
| service-b | MR !56 · 配套接口调整 | 已合并 |
| service-c | 未找到 PR/MR | — |

点击链接只打开平台返回且通过地址校验的 `html_url` / `web_url`。保留标题中的文字，不把远程标题解释为 HTML。

| 当前情况 | 行为 |
| --- | --- |
| 尚未配置平台或 Token | 显示“配置代码托管服务”入口；不发请求 |
| 正在查询 | 本仓显示加载状态，其他仓正常使用 |
| 唯一匹配 | 显示链接；已合并和已关闭记录照常可打开 |
| 多个匹配 | 显示“发现多个候选”，点击后列出编号、标题、来源与目标分支、状态，用户选择本次查看对象 |
| 完整查询后无匹配 | 显示“未找到 PR/MR” |
| 未登记工作分支、本地仓不可用或 remote 无法确定 | 显示具体原因；不根据当前检出分支猜测 |
| 查询失败 | 显示本仓错误和重试入口；不显示为“未找到” |

多候选选择只影响插件当前查看结果，不形成持久关联。刷新后若所选评审仍在候选中则保留选择；切换 Feature 后重新按当前数据查询。不会按“最新”或“仍开放”直接覆盖其他候选。

### 设置页

沿用现有 Kit/Python 配置，在同一页增加当前 Kit 工作区的服务列表，允许新增、编辑、移除。

| 字段 | 约定 |
| --- | --- |
| 平台 | GitHub 或 GitLab |
| 服务地址 | GitHub 固定为 `https://github.com`；GitLab 填写 HTTPS 网站 origin，可包含端口 |
| Token | 密码输入框；仅用于该服务的读取请求 |
| SSH 主机别名 | 可选，例如将 `github-personal` 映射到已配置的 `github.com` |

按规范化 Kit 根目录隔离配置，同一工作区同一服务地址只配置一个 Token。qbit、crm 可以为同一平台使用不同 Token，不会互相覆盖。首版不支持同一工作区同一服务的多账号路由，也不支持部署在 URL 子路径下的平台。

已有 Token 显示“已保存”。空输入表示保留原 Token；替换和移除分别是明确操作，不把遮罩字符作为新 Token 保存。保存后使当前工作区的评审结果失效；取消设置不修改凭据。

## 3. 查询依据与匹配规则

### 复用已有数据

| 数据 | 现有来源 |
| --- | --- |
| Feature 标识 | `inspect feature` 的 `summary.slug` |
| 仓库与工作分支 | `summary.repositoryBindings[].repository/workBranch` |
| 基线分支 | `summary.repositoryBindings[].baseBranch` |
| 本地仓路径 | `inspect workspace` 的仓库登记与 `absolutePath` |
| 平台及远程项目路径 | 该本地仓的 Git remote URL |

`baseBranch` 是开发基线，不一定是 PR/MR 目标。首版不以它硬性过滤评审；在多候选弹窗中展示实际目标分支供用户区分。

直接使用 Feature 中的工作分支字符串，不要求当前 checkout 匹配，也不要求该分支仍在本地或远程存在。因此合并后删除分支，只要平台仍保留来源信息，就仍可找到历史评审。

### 确定 remote

按 Feature 工作分支读取 Git 配置，依次使用：`branch.<workBranch>.pushRemote`、`remote.pushDefault`、`branch.<workBranch>.remote`、`origin`、唯一剩余 remote。配置为 `.` 不视为远程平台；显式配置指向不存在的 remote 时提示配置错误，不悄悄选择其他 remote。

选中 remote 后优先使用其 push URL，没有时使用 fetch URL。多个 URL 规范化为同一项目时去重；对应不同项目或存在多个无法唯一选择的 remote 时，提供本次查询的 remote 选择，不遍历访问所有地址。

支持 HTTPS、`ssh://git@host:port/group/repo.git` 和 `git@host:group/repo.git`，保留 GitLab 多级 group。SSH 别名通过设置中的显式映射解释，不解析或执行用户 SSH 配置。remote 只用来识别项目，请求目的地始终来自用户配置的 HTTPS 服务。

HTTP(S) URL 中含用户名/密码、非法路径或控制字符时拒绝使用并给出不含原始地址的提示；SSH 用户名 `git` 正常解析。remote 错误日志不得打印可能含凭据的原始 URL。所有 Git 查询使用参数列表，不拼接 shell 命令。

### 匹配评审

查询指定远程项目的所有状态评审，服务端按来源分支过滤，客户端再次核对来源分支和来源仓库身份。

- GitHub：核对 `head.ref` 与 `head.repo.full_name`。
- GitLab：核对 `source_branch`，且 `source_project_id == target_project_id`，与首版同仓范围保持一致。
- 缺失核对字段时显示“评审来源信息不足”；不能据此自动绑定，也不能把未知记录当作无结果。
- 不按标题、Jira Key、作者或当前检出分支匹配。

关联依赖“Feature 工作分支不被其他 Feature 复用”的已有约定。数据出现多候选时按第 2 节处理。跨 fork 评审需要同时确定源项目与目标项目，首版不做跨项目搜索，界面说明当前查询范围。

## 4. 平台 API 与凭据

使用两个平台的官方 REST API，仅发送 GET。API 根地址由平台类型和服务 origin 计算，不由 Feature 文档或 remote 任意指定。

| 平台 | API 根地址与查询 |
| --- | --- |
| GitHub.com | `https://api.github.com/repos/{owner}/{repo}/pulls`，参数 `head={owner}:{workBranch}`、`state=all` |
| GitLab | `{serviceOrigin}/api/v4/projects/{encodedProjectPath}/merge_requests`，参数 `source_branch={workBranch}`、`state=all`、`scope=all` |

路径片段与查询参数分别编码，覆盖带 `/`、`+`、`#` 和中文的分支名。项目路径从 Git URL 去掉一次末尾 `.git`；不根据仓目录名猜远程项目。

GitHub 使用 `Authorization: Bearer`，建议 fine-grained PAT 限定目标仓并授予 `Pull requests: Read`；发送 `Accept: application/vnd.github+json` 和 `X-GitHub-Api-Version: 2022-11-28`。GitLab 使用 `PRIVATE-TOKEN`，Token 需具备 `read_api` 及目标项目访问权限。接口依据见 [GitHub PR API](https://docs.github.com/en/rest/pulls/pulls#list-pull-requests)、[GitLab MR API](https://docs.gitlab.com/api/merge_requests/#list-project-merge-requests) 和 [GitLab Token scopes](https://docs.gitlab.com/security/tokens/access_token_scopes/)。

每页最多 100 条，最多读取 5 页；分页完成前不宣称结果唯一或不存在。达到上限、缺失身份字段、分页失败等情况下显示“查询不完整”并保留可查看候选。页内和跨页按评审 ID 去重。分页只在已确定 API origin 和 endpoint 下推进，不向外部分页 URL 转发凭据。

Token 仅通过 IntelliJ `PasswordSafe` 保存和读取，普通 `agent-workbench.xml` 保存服务配置及凭据标识，不保存 Token。凭据标识包含工作区身份与规范服务地址；实际存储遵循 IDE 密码设置。读取、替换、删除均放在后台线程；保存失败显示错误，不让新配置与旧凭据错配。[PasswordSafe 官方说明](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html)

网络使用宿主公共 `HttpRequests`，沿用宿主代理配置和证书校验；使用现有 Gson 解析 JSON，不引入平台 SDK 或 HTTP 依赖。Token 不进入 URL、Inspect 参数、查询结果模型、普通日志或测试夹具；不跟随重定向，防止凭据转发到其他地址。评审跳转链接必须属于配置的网站 origin，GitHub.com 的 API origin 与网站 origin 使用固定对应关系。

## 5. 请求生命周期与错误处理

只在项目受信任、Feature 概览可见、服务配置完整时开始查询。设置保存只使结果失效，实际重新查询仍遵循可见条件。

当前 Feature 各仓最多两个并发请求，单请求连接超时 5 秒、读取超时 10 秒，单仓含分页总预算 20 秒、单页响应上限 2 MiB。连续点刷新会取消被替代请求；返回列表、切换 Feature/Kit/Python、修改服务配置或关闭项目时取消当前请求，并断开正在使用的连接。阻塞 I/O 不仅依靠 `Job.cancel()`；实现必须验证取消确实释放连接与并发名额。

只保存当前 Feature 的查询结果、查询时间与本次候选选择。重复渲染、Git 扫描回调和现有定时刷新不重新访问平台；用户点“代码评审”的刷新按钮才强制重查。离开后再次进入概览可重新查询，不建立磁盘缓存或轮询任务。

请求身份包含规范 Kit 根目录、Python 绑定、Feature slug、仓路径、remote 项目、工作分支和服务配置代数。结果更新前及 EDT 回调内再次检查身份与请求代数，旧成功和旧失败都不能覆盖新选择。Token 本身不属于请求 key。

| 故障 | 用户提示 |
| --- | --- |
| 401 | Token 无效或已过期，提供设置入口 |
| 403 | 权限不足、组织授权要求或访问受限；限流信号明确时显示限流 |
| 404 | 项目不存在或当前 Token 无权访问，不显示为没有 PR/MR |
| 429 / 平台限流 | 显示稍后重试；可用时显示服务端建议等待时间 |
| 连接、TLS、超时、5xx | 本仓查询失败，提供手动重试，不循环自动重试 |
| JSON 无效、响应超限、来源字段缺失 | 响应无法完整解析/查询不完整 |

刷新失败时可以保留同一请求身份下上次成功链接，但标注“上次结果”和查询时间。改 Token、换工作区或换分支后立即清除旧链接，不用其他身份的结果兜底。

## 6. 最小实现拆分

按现有 Kotlin、Swing 和平台测试方式实现。只提取 PR/MR 查询需要的代码，不重构全仓。

| 位置 | 改动 |
| --- | --- |
| `WorkbenchSettings.kt` | 在现有工作区 Preference 中保存服务配置，兼容旧设置缺少字段 |
| `ui/WorkbenchConfigurable.kt` | 服务列表、Token 替换/移除与保存反馈 |
| `git/NativeGit.kt` | 复用现有 Git 执行入口，补充针对 Feature 分支的 remote 查询及日志脱敏 |
| 新增 `review/ReviewClient.kt` | 必要数据类、remote 解析、两个平台查询、分页与唯一匹配判断 |
| 新增 `review/ReviewService.kt` | PasswordSafe 访问、当前 Feature 结果与请求取消；公开 API 使用宿主支持的稳定版本 |
| 新增 `ui/FeatureReviewPanel.kt` | 每仓一行、设置/刷新/候选入口，复用现有 UI 令牌 |
| `ui/WorkbenchPanel.kt`、`ui/FeatureChangesPanel.kt` | 变更页接入，传入现有 bindings；压缩已提交页并显式加载 |
| `README.md`、`docs/USER_GUIDE.md` | 配置方法、权限、查询范围与常见错误 |

不改变 `KitClient` 的 Inspect 操作，不新增 Schema 或工作区配置版本。优先直接使用小函数和数据类，不设计可插拔平台注册系统。

已按此顺序完成设置与凭据隔离、remote/API 查询与匹配、详情 UI 和生命周期接入。

## 7. 验收

| 场景 | 通过条件 |
| --- | --- |
| 网页手动创建 PR/MR | 无需写 README，按同仓与工作分支找到链接 |
| 当前 checkout 不同或来源分支已删除 | 使用 Feature 记录查询；平台保留来源信息时可找到历史评审 |
| 同名来源分支来自其他 fork、多个目标或历史重建 | 不误绑定；多候选需选择；fork 范围限制明确 |
| 多仓 Feature | 每仓一行；一个仓失败不遮蔽其他仓结果 |
| 首页、列表、不可见页面、未受信任项目 | PR/MR API 请求数为零 |
| HTTPS/SSH、SSH 别名、GitLab 多级 group、非默认端口 | 正确解析；不能唯一选择 remote 时明确提示 |
| qbit/crm 使用同一域名不同 Token | 凭据与结果互不混用；设置 XML 不含 Token |
| Token 留空、替换、删除、取消编辑、存储失败 | 分别保留/替换/删除/不变/报错；不把遮罩保存为凭据 |
| 两个平台的所有状态、分页、缺失字段、限流和失败 | 状态准确；不完整与无结果有区别，不只读第一页就判断唯一 |
| 快速切换、反复刷新、修改 Token、同根切换 Python | 旧成功/失败均不能覆盖当前状态；取消释放资源 |
| 重定向、外部分页 URL、含凭据 remote、恶意标题 | 不向其他 origin 发送 Token，日志脱敏，文本不会执行 |
| 工作区只读 | 测试前后 Kit、Feature 文档、Git 内容和配置无变化；仅允许插件设置与凭据库变更 |

新增测试使用合成 Git 仓、平台响应和可控 HTTP/凭据替身，复用现有测试依赖，不读取真实 Token，不访问真实 GitHub/GitLab。测试覆盖实际查询流程、UI 回调和设置序列化，不以关键词断言替代行为。

实现完成后执行插件现有 `test`、`buildPlugin`，以及 IDEA/Rebased 对应的宿主兼容性检查。实际宿主验收检查主题、长仓名/长标题、链接打开、后台取消与密码存储；未经真实宿主验证的项如实记录。

兼容当前 Kit 1.3.0+ 的既有字段；旧插件配置默认没有服务，只显示配置入口。插件版本已从 `0.3` 升至 `0.4`；Kit 不升级。本次不提交或发布。
