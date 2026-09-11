# Agent Workbench IntelliJ 插件

Agent Workbench 是 IntelliJ IDEA 和 Rebased 中的工作流只读工作台。它把 `agent-workbench` 记录的工作区、Feature、任务、文档、验证、流程和 Run 集中展示，并把每个已登记业务仓的 Log、Diff、Commit、Branches、冲突处理和 Fetch 入口交给宿主原生 Git。

插件解决的是“查看和定位”问题：不用在工作流仓、多个业务仓和 Git 工具之间反复切换。它不提供 Agent 终端，不调用模型，不创建 Feature，不推进工作流，也不修改 `.workspace` 中的工作流事实。

## 它和 agent-workbench 的关系

插件是数据消费方，`agent-workbench` Kit 是数据提供方：

```text
IDEA / Rebased
       │
       ├── Agent Workbench 插件（界面、筛选、文档阅读、宿主 Git 入口）
       │       │
       │       └── 本机 Python 子进程：agent-workbench/scripts/kit.py inspect
       │
       └── agent-workbench 工作流仓（.workspace、需求记录、验证与 Run）
               │
               └── .workspace 配置登记的业务仓
```

插件通过 `inspect --api-major 1 --json` 读取 Kit 的公开只读协议。业务仓列表来自 `.workspace/workspace.json` 的登记内容，Git 分支、工作区变更、上游领先/落后和冲突来自宿主 Git 或只读 Git 信息。插件不会递归扫描父目录，也不会把未登记兄弟仓加入工作台。

## 快速开始

- [🚀 5 分钟极速上手指南 (QUICKSTART.md)](QUICKSTART.md)：适合新用户的端到端安装与基础操作导览
- [📖 详细用户指南与最佳实践 (docs/USER_GUIDE.md)](docs/USER_GUIDE.md)：涵盖多仓配置规范、Git 比对模型、排障 FAQ 的完整手册
- [🎨 UI/UX 设计规范 (design.md)](design.md)：JetBrains New UI 令牌与界面布局设计契约

### 前置条件

- IntelliJ IDEA 2025.2（Build 252）或 Rebased 1.1.12（Build 262）及兼容版本。
- Kit 版本 `1.3.0` 或更高版本。`1.3.0` 开始提供插件所需的 `inspect` 查询接口。
- 可执行的 Python 3，通常是 `python3`。
- 当前项目为受信任项目，并已安装宿主自带的 Git 支持。

### 安装开发包

可以直接使用预构建的插件包（如 `build/distributions/agent-workbench-intellij-0.3.zip`），通过 `Settings | Plugins | ⚙ | Install Plugin from Disk...` 安装并重启 IDE。

如需从源码构建，在本仓执行：

```bash
JAVA_HOME='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home' \
./gradlew --no-daemon \
  '-Dorg.gradle.java.home=/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home' \
  test buildPlugin
```

安装包会生成在 `build/distributions/agent-workbench-intellij-<版本号>.zip`（版本号见 `build.gradle.kts` 的 `version`，当前为 `0.3`）。在 IDEA 或 Rebased 中打开 `Settings | Plugins | ⚙ | Install Plugin from Disk...`，选择这个 ZIP，重启 IDE。

### 首次打开工作区

推荐打开包含 Kit 和业务仓的父目录，例如：

```text
my-project-workspace/
├── agent-workbench       # 工作流 Kit
├── order-service         # 业务微服务仓 A
├── payment-service       # 业务微服务仓 B
├── inventory-service     # 业务微服务仓 C
└── admin-frontend        # 前端管理后台仓
```

打开父目录后：

1. 打开左侧 `Agent Workbench` Tool Window。
2. 插件优先恢复当前 IDEA 项目已保存的 Kit；没有保存绑定时，会检查当前项目目录和直接子目录 `agent-workbench`，找到 `scripts/kit.py` 后自动绑定并读取。
3. 如果没有自动发现，在侧栏填写 Kit 根目录 `/path/to/my-project-workspace/agent-workbench` 和 Python `python3`，点击“绑定并刷新”。也可以在 `Settings | Tools | Agent Workbench` 中配置绑定。
4. 点击“打开工作台”，进入工作区总览；也可以通过 `Tools | 打开 Agent Workbench 工作台` 菜单（支持 Search Everywhere 搜索该动作）直接打开。

父目录项目可以使用其 `.idea/vcs.xml` 中的多仓 Git mapping。若某个仓库尚未被当前 IDEA 项目接入，点击该仓库的 Git 操作时插件会先询问“接入并继续”，确认后只添加这个仓库的 mapping。

也可以直接打开 Kit 子目录。此时插件只显示当前工作区登记的业务仓；要使用跨仓原生 Git，建议改为打开上面的父目录。

## 工作台能做什么

| 页面 | 内容 |
| --- | --- |
| 工作区总览 | Kit 路径、业务仓数量、进行中的 Feature、未提交文件和需要关注的现场 |
| 业务仓库 | 当前分支、工作区是否干净、上游同步、最近提交、搜索和状态筛选 |
| Feature 工作台 | 全部生命周期状态、名称搜索、关联仓筛选、计划进度和关注项 |
| 接手包 | 预览并复制当前任务、阶段、验证摘要和带版本来源，旧 Kit 自动回退兼容提示词 |
| 历史检索 | 按关键词、仓库和状态搜索当前工作区的需求、设计、计划与验证记录 |
| Feature 详情 | 概览、文档、计划、需求分支变更、当前工作目录、验证、流程和扩展 |
| 流程记录 | 按 Run 查看已有记录、配置匹配和结果，不会重新执行流程 |
| 读取诊断 | 显示缺失 Kit、版本不兼容、损坏记录和单仓读取失败原因 |

文档内容按需读取，支持渲染、原文、目录、搜索和任务原文定位。Markdown 中的脚本、远程图片和危险链接不会在插件内执行或自动加载。

## Git 操作边界

插件复用 IDEA / Rebased 的原生 Git 界面，不重建 Git 图、提交对话框、冲突编辑器或分支管理器。

- `Log` 只打开所选仓库的日志过滤器。
- `Diff` 只传递所选仓库的变更。
- `Commit` 只把所选仓库的变更作为初始选区，最终提交由宿主对话框和用户确认。
- `Branches` 和冲突处理只传递所选仓库的上下文。
- `Fetch` 要求明确仓库和 remote，在后台任务中执行（可取消），结束后以宿主通知汇总逐仓结果。
- 查看 Feature 不会 checkout 分支；需求分支比较使用记录中的固定 commit。

插件不会自动 Fetch、stash、checkout、merge、解决冲突、提交、推送或修改工作流记录。

## 数据和本地状态

工作流事实始终保存在 Kit 的 `.workspace` 和需求文件中。插件只在宿主本地配置目录保存绑定、筛选、当前 Tab、Run 和文档阅读位置，配置文件名为 `agent-workbench.xml`，禁用跨设备 roaming。删除插件本地缓存不会删除工作流记录或 Git 内容。

## 常见问题

### 提示“Inspect 信封格式无效”

这是旧 Kit 不支持 `inspect` 时的旧版提示。升级绑定的工作流仓到 `1.3.0` 或更高版本，然后重新点击“绑定并刷新”：

```bash
cd /path/to/agent-workbench
cat VERSION
python3 scripts/kit.py inspect --root . --api-major 1 --json workspace
```

正常响应应是 JSON 信封，且 `operation` 为 `workspace`。

### 点击仓库操作提示“宿主未识别独立 Git 仓库”

优先用包含各子仓的父目录打开 IDEA，让父项目加载全部 `.idea/vcs.xml` mapping。也可以在仓库表中选择该仓，点击“接入所选仓库”；新版插件的单仓 Git 操作会自动给出“接入并继续”确认。

### 工作台没有数据

确认项目已受信任、Kit 根目录包含 `scripts/kit.py` 和 `.workspace/`，Python 路径可执行，并且 Kit 至少为 `1.3.0`。未初始化的 Kit 只显示维护模式信息，插件不会自动初始化工作区。

### 为什么没有所有父目录项目

插件只显示 Kit 工作区配置登记的仓库，避免把无关仓库或其他项目混入当前工作区。请在 `agent-workbench` 的工作区配置中维护仓库登记，再刷新插件。

## 本地开发

依赖版本已固定在 `build.gradle.kts`。常用命令：

```bash
./gradlew test buildPlugin
./gradlew -PsmokeRoot=/path/to/test/kit runIde
./gradlew -PsmokeRoot=/path/to/test/kit runRebased
```

测试使用独立平台沙箱；集成测试只有显式传入 `integrationRoot` 和 `integrationEntry` 时才读取外部工作区，所有查询保持只读。Rebased 平台测试和 Plugin Verifier 需要单独检查，构建成功不等于所有宿主版本都已验收。

需求、设计、实施计划和宿主验收记录集中维护在相邻 Kit 仓库的 [`docs/development/features/intellij-workbench-v1/`](https://github.com/sandy-hmb/agent-workbench/tree/main/docs/development/features/intellij-workbench-v1)。Kit 的 Inspect 契约见 [`docs/reference/workbench-inspect.md`](https://github.com/sandy-hmb/agent-workbench/blob/main/docs/reference/workbench-inspect.md)，工作流仓库见 [`sandy-hmb/agent-workbench`](https://github.com/sandy-hmb/agent-workbench)。
