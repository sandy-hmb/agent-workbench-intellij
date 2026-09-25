# Agent Workbench IntelliJ Plugin

- 使用 Kotlin、IntelliJ Platform 公共 API 和原生 Swing；不得调用 Internal API 或反射宿主实现。
- 插件默认只读 Kit 工作流记录；仅允许用户确认后通过 Kit item complete 入口结束满足验收的 WorkItem。Git 写操作只经明确仓库范围的宿主原生入口发起。
- 用 `./gradlew` 与 IDEA JBR 构建；`build/` 和 IDE sandbox 不纳入 Git。
- 需求、设计、计划、验证记录在 `../agent-workbench/docs/development/items/workbench-v2/`，本仓不复制。
